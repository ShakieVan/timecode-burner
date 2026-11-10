package io.github.shakievan.timecode

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.github.shakievan.timecode.UpdateManager.State

private val OnAmber = Color(0xFF1C1300)

/** Re-evaluates whether "install unknown apps" is allowed each time the screen resumes. */
@Composable
private fun rememberCanInstall(): Boolean {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return remember(lifecycle) { UpdateManager.canInstallPackages(context) }
}

@Composable
fun UpdateBanner(state: State, onOpen: () -> Unit) {
    val text = when (state) {
        is State.Ready -> "Update ${state.release.version} ist bereit zur Installation"
        is State.Available -> "Update ${state.release.version} verfügbar"
        else -> return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Amber.copy(alpha = 0.12f))
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), fontSize = 14.sp, color = Amber, fontWeight = FontWeight.Medium)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Amber)
    }
}

/** Shown once after an in-app update while the install permission is still on. */
@Composable
fun PostUpdateNotice(onDone: () -> Unit) {
    val context = LocalContext.current
    val canInstall = rememberCanInstall()
    LaunchedEffect(canInstall) { if (!canInstall) onDone() }
    if (!canInstall) return
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(1.dp, Amber.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            "Update auf ${UpdateManager.installedVersion} installiert",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Amber,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Die Installationsfreigabe für Timecode ist noch aktiv. Du kannst sie bis zum nächsten Update wieder ausschalten.",
            fontSize = 13.sp,
            color = Muted,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("Später", color = Muted) }
            TextButton(onClick = {
                onDone()
                context.startActivity(UpdateManager.installPermissionIntent(context))
            }) { Text("Freigabe ausschalten", color = Amber) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: State, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { if (state is State.Idle) UpdateManager.check() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Bg,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text("Einstellungen", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            Text(
                "UPDATES",
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
                color = Muted,
            )
            Spacer(Modifier.height(8.dp))
            UpdatesCard(state)
        }
    }
}

@Composable
private fun UpdatesCard(state: State) {
    val context = LocalContext.current
    val canInstall = rememberCanInstall()
    val release = when (state) {
        is State.Available -> state.release
        is State.Downloading -> state.release
        is State.Verifying -> state.release
        is State.Ready -> state.release
        is State.Failed -> state.release
        else -> null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(18.dp),
    ) {
        VersionRow("Installiert", UpdateManager.installedVersion, highlight = false)
        Spacer(Modifier.height(8.dp))
        VersionRow(
            "Verfügbar",
            when {
                release != null -> release.version
                state is State.UpToDate -> UpdateManager.installedVersion
                else -> "–"
            },
            highlight = release != null,
        )

        Spacer(Modifier.height(14.dp))
        StatusLine(state)

        if (release != null && release.notes.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            Text("Neu in ${release.version}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                release.notes,
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardHigh)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
        }

        Spacer(Modifier.height(16.dp))
        when (state) {
            State.Idle, State.UpToDate -> SecondaryAction("Nach Updates suchen") { UpdateManager.check() }
            State.Checking -> Busy("Suche nach Updates …", null)
            is State.Available -> PrimaryAction("Update herunterladen") { UpdateManager.download() }
            is State.Downloading -> {
                Busy("Wird heruntergeladen …", state.progress)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = UpdateManager::cancelDownload) { Text("Abbrechen", color = Muted) }
                }
            }
            is State.Verifying -> Busy("Prüfsumme und Signatur werden geprüft …", null)
            is State.Ready -> {
                if (canInstall) {
                    PrimaryAction("Update installieren") { UpdateManager.install(context) }
                } else {
                    Text(
                        "Damit Timecode das Update installieren darf, erteile einmalig die Installationsfreigabe und kehre dann zurück.",
                        fontSize = 13.sp,
                        color = Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryAction("Installationsfreigabe erteilen") {
                        context.startActivity(UpdateManager.installPermissionIntent(context))
                    }
                }
            }
            is State.Failed -> {
                if (state.release != null) {
                    PrimaryAction("Erneut herunterladen") { UpdateManager.download() }
                } else {
                    SecondaryAction("Erneut suchen") { UpdateManager.check() }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canInstall) {
                TextButton(onClick = { context.startActivity(UpdateManager.installPermissionIntent(context)) }) {
                    Text("Installationsfreigabe ausschalten", fontSize = 13.sp, color = Muted)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.RELEASES_PAGE)))
            }) {
                Text("Alle Versionen", fontSize = 13.sp, color = Muted)
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "Timecode sucht höchstens einmal am Tag auf GitHub nach Updates. Herunterladen und Installieren geschieht nur auf deinen Wunsch. Ein GitHub-Konto ist nicht nötig.",
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = Muted,
    )
}

@Composable
private fun VersionRow(label: String, value: String, highlight: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp)
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            color = if (highlight) Amber else Muted,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun StatusLine(state: State) {
    val (text, color) = when (state) {
        State.Idle -> "Noch nicht gesucht" to Muted
        State.Checking -> "Suche läuft …" to Muted
        State.UpToDate -> "Du hast die neueste Version" to Muted
        is State.Available -> "Neue Version verfügbar" to Amber
        is State.Downloading -> "Download läuft – auch wenn du die App wechselst" to Muted
        is State.Verifying -> "Wird geprüft …" to Muted
        is State.Ready -> "Bereit · ${state.release.version}" to Amber
        is State.Failed -> state.message to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state is State.UpToDate || state is State.Ready) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (state is State.Ready) Amber else Muted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, color = color)
    }
}

@Composable
private fun Busy(text: String, progress: Float?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 14.sp)
        if (progress != null) {
            Text("${(progress * 100).toInt()} %", fontFamily = FontFamily.Monospace, color = Amber, fontSize = 14.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
    val mod = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = mod,
            color = Amber,
            trackColor = Line,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(modifier = mod, color = Amber, trackColor = Line)
    }
}

@Composable
private fun PrimaryAction(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = OnAmber),
    ) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryAction(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Line),
    ) { Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface) }
}
