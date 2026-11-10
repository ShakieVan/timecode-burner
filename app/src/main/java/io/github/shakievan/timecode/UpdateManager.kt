package io.github.shakievan.timecode

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updater backed by the public GitHub releases of this repository.
 *
 * - No token: uses the anonymous GitHub API.
 * - Checks automatically at most once a day while the app is open; downloads and installs only on request.
 * - Downloads keep running when the user switches apps (process-wide scope, not tied to the screen).
 * - Before offering installation, the APK must match the release's SHA256SUMS.txt, carry this app's
 *   package name, a higher version code and the same signing certificate as the installed app.
 */
object UpdateManager {

    private const val REPO = "ShakieVan/timecode-burner"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    private const val SUMS_FILE = "SHA256SUMS.txt"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private const val PREFS = "updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_UPDATE_FROM = "update_started_from_code"

    data class Release(
        val version: String,
        val notes: String,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val sumsUrl: String?,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float?) : State
        data class Verifying(val release: Release) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** True once after the app was updated through the updater; used to suggest revoking the permission. */
    private val _justUpdated = MutableStateFlow(false)
    val justUpdated: StateFlow<Boolean> = _justUpdated.asStateFlow()

    private lateinit var app: Context
    private var job: Job? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val prefs = prefs()
        val from = prefs.getLong(KEY_UPDATE_FROM, -1)
        if (from >= 0) {
            if (installedCode > from) _justUpdated.value = true
            prefs.edit { remove(KEY_UPDATE_FROM) }
        }
        // Leftover APKs from earlier sessions are never reused.
        File(app.cacheDir, "updates").deleteRecursively()
    }

    val installedVersion: String get() = packageInfo().versionName ?: "?"
    private val installedCode: Long get() = packageInfo().longVersionCode

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun dismissJustUpdated() {
        _justUpdated.value = false
    }

    private val busy: Boolean
        get() = _state.value.let {
            it is State.Checking || it is State.Downloading || it is State.Verifying
        }

    /** Silent daily check: shows nothing on failure or when already up to date. */
    fun checkIfDue() {
        if (busy || _state.value is State.Available || _state.value is State.Ready) return
        val last = prefs().getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - last >= DAY_MS) check(silent = true)
    }

    fun check(silent: Boolean = false) {
        if (busy) return
        job = scope.launch {
            if (!silent) _state.value = State.Checking
            val result = runCatching { fetchLatest() }
            result.onSuccess { release ->
                prefs().edit { putLong(KEY_LAST_CHECK, System.currentTimeMillis()) }
                _state.value = when {
                    release == null -> State.UpToDate
                    isNewer(release.version, installedVersion) -> State.Available(release)
                    else -> State.UpToDate
                }
            }.onFailure { e ->
                _state.value = if (silent) State.Idle else State.Failed(describe(e), null)
            }
        }
    }

    fun download() {
        val release = when (val s = _state.value) {
            is State.Available -> s.release
            is State.Failed -> s.release ?: return
            else -> return
        }
        job = scope.launch {
            val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
            val file = File(dir, release.apkName)
            try {
                _state.value = State.Downloading(release, 0f)
                val sha = downloadTo(release, file)
                ensureActive()
                _state.value = State.Verifying(release)
                verify(release, file, sha)
                _state.value = State.Ready(release, file)
            } catch (e: kotlinx.coroutines.CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                file.delete()
                _state.value = State.Failed(describe(e), release)
            }
        }
    }

    fun cancelDownload() {
        val release = (_state.value as? State.Downloading)?.release ?: return
        job?.cancel()
        File(app.cacheDir, "updates").deleteRecursively()
        _state.value = State.Available(release)
    }

    /** Hands the verified APK to Android's installer. Caller makes sure the permission is granted. */
    fun install(context: Context) {
        val ready = _state.value as? State.Ready ?: return
        if (!ready.file.exists()) {
            _state.value = State.Available(ready.release)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", ready.file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        prefs().edit { putLong(KEY_UPDATE_FROM, installedCode) }
        context.startActivity(intent)
    }

    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    // ---- network ---------------------------------------------------------------------------

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TimecodeBurner/$installedVersion")
        }

    /** Returns the latest release with an APK, or null if the repository has none yet. */
    private fun fetchLatest(): Release? {
        val conn = open(LATEST_API)
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> return null
                HttpURLConnection.HTTP_FORBIDDEN, 429 ->
                    throw IOException("GitHub hat zu viele Anfragen gemeldet. Bitte später erneut versuchen.")
                HttpURLConnection.HTTP_OK -> Unit
                else -> throw IOException("GitHub antwortet mit Fehler ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            var apk: JSONObject? = null
            var sums: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                when {
                    name.endsWith(".apk", ignoreCase = true) && apk == null -> apk = a
                    name.equals(SUMS_FILE, ignoreCase = true) -> sums = a
                }
            }
            apk ?: return null
            return Release(
                version = json.getString("tag_name").removePrefix("v").removePrefix("V"),
                notes = cleanNotes(json.optString("body")),
                apkName = apk.getString("name"),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size", -1),
                sumsUrl = sums?.getString("browser_download_url"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun downloadTo(release: Release, file: File): String {
        val conn = open(release.apkUrl).apply { setRequestProperty("Accept", "application/octet-stream") }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download fehlgeschlagen (${conn.responseCode})")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var lastEmit = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (read - lastEmit > 128 * 1024 || read == total) {
                            lastEmit = read
                            _state.value = State.Downloading(
                                release,
                                if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else null,
                            )
                        }
                    }
                }
            }
            if (release.apkSize > 0 && read != release.apkSize) {
                throw IOException("Download unvollständig")
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            conn.disconnect()
        }
    }

    // ---- verification ----------------------------------------------------------------------

    private fun verify(release: Release, file: File, sha256: String) {
        val sumsUrl = release.sumsUrl
            ?: throw SecurityException("Dem Release fehlt die Prüfsumme ($SUMS_FILE)")
        val conn = open(sumsUrl).apply { setRequestProperty("Accept", "application/octet-stream") }
        val sums = try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Prüfsumme nicht abrufbar")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val expected = sums.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == release.apkName }
            ?.get(0)?.lowercase()
            ?: throw SecurityException("Keine Prüfsumme für ${release.apkName} gefunden")
        if (expected != sha256) throw SecurityException("Prüfsumme stimmt nicht überein")

        val pm = app.packageManager
        val archive = archiveInfo(pm, file.absolutePath)
            ?: throw SecurityException("Die heruntergeladene Datei ist keine gültige App")
        if (archive.packageName != app.packageName) throw SecurityException("Falsche App im Update")
        if (archive.longVersionCode <= installedCode) {
            throw SecurityException("Das Update ist nicht neuer als die installierte Version")
        }
        val installedSigners = signers(packageInfo())
        val updateSigners = signers(archive)
        if (installedSigners.isEmpty() || installedSigners != updateSigners) {
            throw SecurityException("Signatur des Updates passt nicht zur installierten App")
        }
    }

    private fun signers(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val sigs = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun packageInfo(): PackageInfo {
        val pm = app.packageManager
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(
                app.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    }

    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(
                path,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    // ---- helpers ---------------------------------------------------------------------------

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Compares dotted version strings numerically: 1.10 > 1.9, 1.1 == 1.1.0. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Light Markdown cleanup so release notes read well as plain text. */
    private fun cleanNotes(md: String): String = md
        .replace("\r", "")
        .lines()
        .joinToString("\n") { line ->
            line.replace(Regex("^#+\\s*"), "")
                .replace(Regex("^\\s*[-*]\\s+"), "• ")
                .replace("**", "")
                .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                .replace("`", "")
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun describe(e: Throwable): String = when (e) {
        is SecurityException -> "Prüfung fehlgeschlagen: ${e.message}"
        is java.net.UnknownHostException -> "Keine Internetverbindung"
        is java.net.SocketTimeoutException -> "Zeitüberschreitung. Bitte erneut versuchen."
        else -> e.message ?: e.javaClass.simpleName
    }
}
