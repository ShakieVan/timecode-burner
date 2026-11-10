package io.github.shakievan.timecode

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState

internal val Amber = Color(0xFFFFB547)
internal val Bg = Color(0xFF0F1115)
internal val Card = Color(0xFF181B21)
internal val CardHigh = Color(0xFF20242B)
internal val Muted = Color(0xFF9AA0A9)
internal val Line = Color(0xFF2A2F37)

private val Colors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1C1300),
    secondary = Amber,
    background = Bg,
    onBackground = Color(0xFFE8EAED),
    surface = Card,
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = CardHigh,
    onSurfaceVariant = Muted,
    outline = Line,
    outlineVariant = Line,
    error = Color(0xFFFF6B6B),
)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UpdateManager.init(this)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            MaterialTheme(colorScheme = Colors) {
                Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
                    App(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let(vm::openVideo)
        }
    }
}

@Composable
private fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        if (it != null) vm.openVideo(it)
    }
    val pick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    KeepScreenOn(state.export is ExportState.Running)

    val update by UpdateManager.state.collectAsStateWithLifecycle()
    val justUpdated by UpdateManager.justUpdated.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    LaunchedEffect(lifecycle) {
        if (lifecycle == Lifecycle.State.RESUMED) UpdateManager.checkIfDue()
    }
    val updateWaiting = update is UpdateManager.State.Available || update is UpdateManager.State.Ready

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Header(
            showClose = state.video != null && state.export !is ExportState.Running,
            onClose = vm::clearVideo,
            updateDot = updateWaiting,
            onSettings = { showSettings = true },
        )
        Spacer(Modifier.height(20.dp))

        if (justUpdated) {
            PostUpdateNotice(onDone = UpdateManager::dismissJustUpdated)
            Spacer(Modifier.height(16.dp))
        } else if (updateWaiting && !showSettings) {
            UpdateBanner(update, onOpen = { showSettings = true })
            Spacer(Modifier.height(16.dp))
        }

        val video = state.video
        when {
            state.loading -> LoadingCard()
            video == null -> EmptyCard(onPick = pick)
            else -> Editor(state, video, vm)
        }

        state.error?.let {
            Spacer(Modifier.height(16.dp))
            Notice(it, isError = true, onDismiss = vm::dismissError)
        }
    }

    if (showSettings) {
        SettingsSheet(update, onDismiss = { showSettings = false })
    }
}

@Composable
private fun Header(
    showClose: Boolean,
    onClose: () -> Unit,
    updateDot: Boolean,
    onSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Timecode",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Brennt MM:SS:mmm unten links ins Video",
                fontSize = 14.sp,
                color = Muted,
            )
        }
        if (showClose) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Anderes Video", tint = Muted)
            }
        }
        IconButton(onClick = onSettings) {
            Box {
                Icon(Icons.Rounded.Settings, contentDescription = "Einstellungen", tint = Muted)
                if (updateDot) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Amber),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCard(onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Card)
            .border(1.dp, Line, RoundedCornerShape(24.dp))
            .clickable(onClick = onPick)
            .padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(Amber.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Amber, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Video auswählen", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "oder aus der Galerie hierher teilen",
            fontSize = 14.sp,
            color = Muted,
        )
        Spacer(Modifier.height(22.dp))
        TimecodeChip("00:00:000")
    }
}

@Composable
private fun LoadingCard() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Card),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Amber)
    }
}

@Composable
private fun Editor(state: UiState, video: VideoInfo, vm: MainViewModel) {
    val running = state.export is ExportState.Running
    val digits = minuteDigitsFor(video.durationMs)

    // Preview with the label rendered exactly as it will be burned in.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(max = 460.dp)
                .aspectRatio(
                    video.width.toFloat() / video.height,
                    matchHeightConstraintsFirst = video.height > video.width,
                )
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(state.preview, label = "preview") { bmp ->
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = "Vorschau",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Amber, strokeWidth = 3.dp)
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Slider(
        value = state.positionMs.toFloat(),
        onValueChange = { vm.seek(it.toLong()) },
        valueRange = 0f..maxOf(1f, video.durationMs.toFloat()),
        enabled = !running,
        colors = SliderDefaults.colors(
            thumbColor = Amber,
            activeTrackColor = Amber,
            inactiveTrackColor = Line,
        ),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(
            formatTimecode(state.positionMs, digits),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = Amber,
        )
        Spacer(Modifier.weight(1f))
        Text(
            formatTimecode(video.durationMs, digits),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = Muted,
        )
    }

    Spacer(Modifier.height(10.dp))
    Text(
        "${video.name}  ·  ${video.width}×${video.height}",
        fontSize = 13.sp,
        color = Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

    Spacer(Modifier.height(20.dp))
    SettingsCard(state.style, enabled = !running, vm = vm)

    Spacer(Modifier.height(20.dp))
    AnimatedContent(
        targetState = state.export,
        contentKey = { it::class },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "export",
    ) { export ->
        when (export) {
            ExportState.Idle -> PrimaryButton("Timecode einbrennen", onClick = vm::startExport)
            is ExportState.Running -> ProgressCard(export.percent, onCancel = vm::cancelExport)
            is ExportState.Done -> DoneCard(export, onAgain = vm::resetExport)
            is ExportState.Failed -> Column {
                Notice("Export fehlgeschlagen: ${export.message}", isError = true, onDismiss = vm::resetExport)
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Nochmal versuchen", onClick = vm::startExport)
            }
        }
    }
}

@Composable
private fun SettingsCard(style: TimecodeStyle, enabled: Boolean, vm: MainViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Größe", Modifier.weight(1f), fontSize = 15.sp)
            SingleChoiceSegmentedButtonRow(Modifier.width(180.dp)) {
                TimecodeSize.entries.forEachIndexed { i, size ->
                    SegmentedButton(
                        selected = style.size == size,
                        onClick = { vm.setSize(size) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(i, TimecodeSize.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Amber.copy(alpha = 0.18f),
                            activeContentColor = Amber,
                            activeBorderColor = Amber.copy(alpha = 0.5f),
                            inactiveContainerColor = Color.Transparent,
                            inactiveBorderColor = Line,
                        ),
                        icon = {},
                    ) { Text(size.label) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hintergrund", fontSize = 15.sp)
                Text(
                    if (style.background) "Dunkle Box hinter dem Text" else "Nur Text mit Kontur",
                    fontSize = 12.sp,
                    color = Muted,
                )
            }
            Switch(
                checked = style.background,
                onCheckedChange = vm::setBackground,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF1C1300),
                    checkedTrackColor = Amber,
                    uncheckedTrackColor = CardHigh,
                    uncheckedBorderColor = Line,
                ),
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF1C1300)),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProgressCard(percent: Int?, onCancel: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wird eingebrannt …", Modifier.weight(1f), fontSize = 15.sp)
            Text(
                percent?.let { "$it %" } ?: "",
                fontFamily = FontFamily.Monospace,
                color = Amber,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = Amber,
                trackColor = Line,
                drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = Amber,
                trackColor = Line,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text(
                "Bildschirm bleibt an. App bitte geöffnet lassen.",
                Modifier.weight(1f).align(Alignment.CenterVertically),
                fontSize = 12.sp,
                color = Muted,
            )
            TextButton(onClick = onCancel) { Text("Abbrechen", color = Muted) }
        }
    }
}

@Composable
private fun DoneCard(done: ExportState.Done, onAgain: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .border(1.dp, Amber.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(18.dp),
    ) {
        Text("Fertig", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Amber)
        Spacer(Modifier.height(4.dp))
        Text(
            "Gespeichert unter DCIM/Timecode/${done.fileName}",
            fontSize = 13.sp,
            color = Muted,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(done.uri, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    runCatching { context.startActivity(i) }
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color(0xFF1C1300)),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Abspielen")
            }
            OutlinedButton(
                onClick = {
                    val i = Intent(Intent.ACTION_SEND)
                        .setType("video/mp4")
                        .putExtra(Intent.EXTRA_STREAM, done.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(i, null))
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Line),
            ) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Text("Teilen", color = MaterialTheme.colorScheme.onSurface)
            }
        }
        TextButton(onClick = onAgain, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Andere Einstellung ausprobieren", color = Muted)
        }
    }
}

@Composable
internal fun Notice(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val tint = if (isError) MaterialTheme.colorScheme.error else Amber
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(tint.copy(alpha = 0.1f))
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), fontSize = 13.sp, color = tint)
        IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = "Schließen", tint = tint)
        }
    }
}

@Composable
private fun TimecodeChip(text: String) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = Color.White,
    )
}

@Composable
private fun KeepScreenOn(on: Boolean) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    DisposableEffect(on) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}
