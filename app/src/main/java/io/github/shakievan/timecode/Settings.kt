package io.github.shakievan.timecode

import android.content.Context
import androidx.core.content.edit
import kotlin.math.min
import kotlin.math.roundToInt

/** Output resolution, defined by the short side so portrait and landscape videos behave the same. */
enum class Resolution(val label: String, val shortSide: Int?) {
    ORIGINAL("Original", null),
    P2160("2160p", 2160),
    P1440("1440p", 1440),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360),
}

data class ExportSettings(
    val style: TimecodeStyle = TimecodeStyle(),
    val resolution: Resolution = Resolution.ORIGINAL,
)

/** Target short side for this video, or null if the video stays as it is (never upscales). */
fun Resolution.targetFor(video: VideoInfo): Int? {
    val target = shortSide ?: return null
    return if (target < min(video.width, video.height)) target else null
}

/** Size of the exported video, rounded to even numbers like the encoder does. */
fun outputSize(video: VideoInfo, resolution: Resolution): Pair<Int, Int> {
    val target = resolution.targetFor(video) ?: return video.width to video.height
    val scale = target.toFloat() / min(video.width, video.height)
    fun even(v: Float) = ((v / 2).roundToInt() * 2).coerceAtLeast(2)
    return even(video.width * scale) to even(video.height * scale)
}

/** Remembers the last used settings between app starts. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): ExportSettings {
        val d = ExportSettings()
        return ExportSettings(
            style = TimecodeStyle(
                size = enumOr(prefs.getString(KEY_SIZE, null), d.style.size),
                background = prefs.getBoolean(KEY_BACKGROUND, d.style.background),
                corner = enumOr(prefs.getString(KEY_CORNER, null), d.style.corner),
            ),
            resolution = enumOr(prefs.getString(KEY_RESOLUTION, null), d.resolution),
        )
    }

    fun save(s: ExportSettings) = prefs.edit {
        putString(KEY_SIZE, s.style.size.name)
        putBoolean(KEY_BACKGROUND, s.style.background)
        putString(KEY_CORNER, s.style.corner.name)
        putString(KEY_RESOLUTION, s.resolution.name)
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private companion object {
        const val KEY_SIZE = "size"
        const val KEY_BACKGROUND = "background"
        const val KEY_CORNER = "corner"
        const val KEY_RESOLUTION = "resolution"
    }
}
