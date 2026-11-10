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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.text.style.TextAlign
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
        when (intent?.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { vm.addVideos(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { vm.addVideos(it) }
        }
    }
}

@Composable
private fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        vm.addVideos(it)
    }
    val pick = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    KeepScreenOn(state.running)

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
            showClose = state.items.isNotEmpty() && !state.running,
            onClose = vm::clearAll,
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

        val video = state.selectedVideo
        when {
            video != null -> Editor(state, video, vm, onAdd = pick)
            state.loading -> LoadingCard()
            else -> EmptyCard(onPick = pick)
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
                "Brennt MM:SS:mmm ins Video",
                fontSize = 14.sp,
                color = Muted,
            )
        }
        if (showClose) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Alle Videos entfernen", tint = Muted)
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
        Text("Videos auswählen", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Eins oder mehrere – oder aus der Galerie hierher teilen",
            fontSize = 14.sp,
            color = Muted,
            textAlign = TextAlign.Center,
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
private fun Editor(state: UiState, video: VideoInfo, vm: MainViewModel, onAdd: () -> Unit) {
    val running = state.running
    val digits = minuteDigitsFor(video.durationMs)
    val batch = state.items.size > 1

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

    Spacer(Modifier.height(16.dp))
    VideoList(state, vm, onAdd)

    Spacer(Modifier.height(20.dp))
    SettingsCard(state, video, enabled = !running, vm = vm)

    Spacer(Modifier.height(20.dp))
    val phase = when (state.batch) {
        BatchState.Idle -> 0
        is BatchState.Running -> 1
        BatchState.Finished -> 2
    }
    AnimatedContent(
        targetState = phase,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "export",
    ) { p ->
        when (p) {
            0 -> PrimaryButton(
                if (batch) "${state.items.size} Videos einbrennen" else "Timecode einbrennen",
                onClick = vm::startExport,
            )
            1 -> ProgressCard(state, onCancel = vm::cancelExport)
            else -> ResultCard(state, onAgain = vm::resetExport)
        }
    }
}

@Composable
private fun VideoList(state: UiState, vm: MainViewModel, onAdd: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(vertical = 6.dp),
    ) {
        if (state.items.size > 1) {
            Text(
                "${state.items.size} VIDEOS",
                Modifier.padding(start = 18.dp, top = 8.dp, bottom = 4.dp),
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold,
                color = Muted,
            )
        }
        state.items.forEachIndexed { i, item ->
            VideoRow(
                item = item,
                selected = i == state.selected && state.items.size > 1,
                removable = !state.running,
                onClick = { vm.select(i) },
                onRemove = { vm.remove(i) },
            )
        }
        if (!state.running) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.loading, onClick = onAdd)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Amber, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Add, null, tint = Amber, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (state.loading) "Wird geladen …" else "Weitere Videos hinzufügen",
                    fontSize = 14.sp,
                    color = Amber,
                )
            }
        }
    }
}

@Composable
private fun VideoRow(
    item: Item,
    selected: Boolean,
    removable: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val v = item.video
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Amber.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(32.dp)
                .clip(CircleShape)
                .background(if (selected) Amber else Color.Transparent),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                v.name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${v.width}×${v.height}  ·  ${formatDuration(v.durationMs)}",
                fontSize = 12.sp,
                color = Muted,
            )
        }
        Spacer(Modifier.width(8.dp))
        when (val s = item.status) {
            ItemStatus.Waiting -> if (removable) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Close, contentDescription = "Entfernen", tint = Muted, modifier = Modifier.size(18.dp))
                }
            } else {
                Text("wartet", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(end = 12.dp))
            }
            is ItemStatus.Running -> Text(
                s.percent?.let { "$it %" } ?: "…",
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = Amber,
                modifier = Modifier.padding(end = 12.dp),
            )
            is ItemStatus.Done -> Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "Fertig",
                tint = Amber,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
            )
            is ItemStatus.Failed -> Icon(
                Icons.Rounded.Warning,
                contentDescription = "Fehlgeschlagen: ${s.message}",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(end = 12.dp).size(20.dp),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun SettingsCard(state: UiState, video: VideoInfo, enabled: Boolean, vm: MainViewModel) {
    val style = state.settings.style
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        // Position
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Position", fontSize = 15.sp)
                Text(style.corner.label, fontSize = 12.sp, color = Muted)
            }
            CornerPicker(style.corner, enabled, onPick = vm::setCorner)
        }

        Divider()

        // Size
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

        Divider()

        // Background
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

        Divider()

        // Resolution
        val res = state.settings.resolution
        val (ow, oh) = outputSize(video, res)
        Text("Auflösung", fontSize = 15.sp)
        Text(
            if (state.items.size > 1) {
                "Ausgabe für dieses Video: $ow×$oh · kleinere Videos werden nicht vergrößert"
            } else if (res.targetFor(video) == null && res != Resolution.ORIGINAL) {
                "Video ist schon kleiner – bleibt bei $ow×$oh"
            } else {
                "Ausgabe: $ow×$oh"
            },
            fontSize = 12.sp,
            color = Muted,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val largest = state.items.maxOf { minOf(it.video.width, it.video.height) }
            Resolution.entries.forEach { r ->
                // Hide steps that would be an upscale for every selected video.
                val useful = r.shortSide == null || r.shortSide < largest
                if (useful || r == res) {
                    FilterChip(
                        selected = r == res,
                        onClick = { vm.setResolution(r) },
                        enabled = enabled,
                        label = { Text(r.label, fontSize = 13.sp) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber.copy(alpha = 0.18f),
                            selectedLabelColor = Amber,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = enabled,
                            selected = r == res,
                            borderColor = Line,
                            selectedBorderColor = Amber.copy(alpha = 0.5f),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    Spacer(Modifier.height(14.dp))
}

/** A tiny video frame; tap one of its four corners. */
@Composable
private fun CornerPicker(selected: Corner, enabled: Boolean, onPick: (Corner) -> Unit) {
    Box(
        Modifier
            .size(width = 92.dp, height = 58.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.5.dp, Line, RoundedCornerShape(10.dp))
            .background(CardHigh),
    ) {
        Corner.entries.forEach { c ->
            val align = when (c) {
                Corner.TOP_LEFT -> Alignment.TopStart
                Corner.TOP_RIGHT -> Alignment.TopEnd
                Corner.BOTTOM_LEFT -> Alignment.BottomStart
                Corner.BOTTOM_RIGHT -> Alignment.BottomEnd
            }
            val on = c == selected
            Box(
                Modifier
                    .align(align)
                    .size(width = 46.dp, height = 29.dp)
                    .clickable(enabled = enabled) { onPick(c) },
                contentAlignment = align,
            ) {
                Box(
                    Modifier
                        .padding(6.dp)
                        .size(width = 22.dp, height = 8.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (on) Amber else Line),
                )
            }
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
private fun ProgressCard(state: UiState, onCancel: () -> Unit) {
    val index = (state.batch as? BatchState.Running)?.index ?: 0
    val n = state.items.size
    val current = (state.items.getOrNull(index)?.status as? ItemStatus.Running)?.percent
    val progress = if (n > 1) state.overallProgress else (current ?: 0) / 100f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (n > 1) "Video ${index + 1} von $n wird eingebrannt …" else "Wird eingebrannt …",
                Modifier.weight(1f),
                fontSize = 15.sp,
            )
            Text(
                "${(progress * 100).toInt()} %",
                fontFamily = FontFamily.Monospace,
                color = Amber,
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = Amber,
            trackColor = Line,
            drawStopIndicator = {},
        )
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
private fun ResultCard(state: UiState, onAgain: () -> Unit) {
    val context = LocalContext.current
    val done = state.doneItems
    val failed = state.failedCount
    val total = state.items.size
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Card)
            .border(
                1.dp,
                if (done.isEmpty()) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else Amber.copy(alpha = 0.35f),
                RoundedCornerShape(20.dp),
            )
            .padding(18.dp),
    ) {
        Text(
            when {
                done.isEmpty() -> "Export fehlgeschlagen"
                total == 1 -> "Fertig"
                failed == 0 -> "Alle $total Videos fertig"
                else -> "${done.size} von $total Videos fertig"
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (done.isEmpty()) MaterialTheme.colorScheme.error else Amber,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                done.size == 1 && total == 1 -> "Gespeichert unter DCIM/Timecode/${done[0].fileName}"
                done.isNotEmpty() -> "Gespeichert unter DCIM/Timecode"
                else -> ""
            },
            fontSize = 13.sp,
            color = Muted,
        )
        state.items.mapNotNull { it.status as? ItemStatus.Failed }.distinctBy { it.message }.take(2).forEach {
            Spacer(Modifier.height(6.dp))
            Text(it.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }

        if (done.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (done.size == 1) {
                    Button(
                        onClick = {
                            val i = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(done[0].uri, "video/mp4")
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
                }
                OutlinedButton(
                    onClick = {
                        val uris = ArrayList(done.map { it.uri })
                        val i = if (uris.size == 1) {
                            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                        } else {
                            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        }
                            .setType("video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(i, null))
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Line),
                ) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(6.dp))
                    Text(if (done.size > 1) "Alle teilen" else "Teilen", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        TextButton(onClick = onAgain, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (done.isEmpty()) "Zurück" else "Andere Einstellung ausprobieren", color = Muted)
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
