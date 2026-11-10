package io.github.shakievan.timecode

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

data class VideoInfo(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    /** Display size, i.e. already corrected for rotation metadata. */
    val width: Int,
    val height: Int,
)

sealed interface ItemStatus {
    data object Waiting : ItemStatus
    data class Running(val percent: Int?) : ItemStatus
    data class Done(val uri: Uri, val fileName: String) : ItemStatus
    data class Failed(val message: String) : ItemStatus
}

data class Item(val video: VideoInfo, val status: ItemStatus = ItemStatus.Waiting)

sealed interface BatchState {
    data object Idle : BatchState
    data class Running(val index: Int) : BatchState
    data object Finished : BatchState
}

data class UiState(
    val items: List<Item> = emptyList(),
    val selected: Int = 0,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val preview: Bitmap? = null,
    val settings: ExportSettings = ExportSettings(),
    val batch: BatchState = BatchState.Idle,
    val error: String? = null,
) {
    val selectedVideo: VideoInfo? get() = items.getOrNull(selected)?.video
    val running: Boolean get() = batch is BatchState.Running
    val doneItems: List<ItemStatus.Done> get() = items.mapNotNull { it.status as? ItemStatus.Done }
    val failedCount: Int get() = items.count { it.status is ItemStatus.Failed }

    /** Overall progress over all videos, 0..1. */
    val overallProgress: Float
        get() {
            if (items.isEmpty()) return 0f
            val sum = items.sumOf { item ->
                when (val s = item.status) {
                    is ItemStatus.Done, is ItemStatus.Failed -> 1.0
                    is ItemStatus.Running -> (s.percent ?: 0) / 100.0
                    ItemStatus.Waiting -> 0.0
                }
            }
            return (sum / items.size).toFloat()
        }
}

@OptIn(UnstableApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)
    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    // MediaMetadataRetriever isn't thread-safe; all access goes through this one-at-a-time dispatcher.
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private val retrieverDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUri: Uri? = null
    private val previewRequest = MutableSharedFlow<PreviewRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private data class PreviewRequest(val video: VideoInfo, val positionMs: Long, val style: TimecodeStyle)

    private var batchJob: Job? = null

    init {
        viewModelScope.launch {
            previewRequest.collectLatest { renderPreview(it) }
        }
    }

    // ---- videos ----------------------------------------------------------------------------

    fun addVideos(uris: List<Uri>) {
        if (uris.isEmpty() || _state.value.running) return
        if (_state.value.batch is BatchState.Finished) resetExport()
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val known = _state.value.items.map { it.video.uri }.toSet()
            val results = withContext(Dispatchers.IO) {
                uris.distinct().filter { it !in known }.map { uri -> runCatching { probe(uri) } }
            }
            val added = results.mapNotNull { it.getOrNull() }
            val failed = results.count { it.isFailure }
            val wasEmpty = _state.value.items.isEmpty()
            _state.update {
                it.copy(
                    items = it.items + added.map(::Item),
                    loading = false,
                    error = when {
                        failed == 0 -> it.error
                        failed == 1 && uris.size == 1 ->
                            "Video konnte nicht geöffnet werden: ${results.first().exceptionOrNull()?.message}"
                        else -> "$failed Datei(en) konnten nicht geöffnet werden"
                    },
                )
            }
            if (wasEmpty && added.isNotEmpty()) select(0)
        }
    }

    fun select(index: Int) {
        val s = _state.value
        if (index !in s.items.indices) return
        if (index == s.selected && s.preview != null) return
        _state.update { it.copy(selected = index, positionMs = 0, preview = null) }
        requestPreview()
    }

    fun remove(index: Int) {
        val s = _state.value
        if (s.running || index !in s.items.indices) return
        val items = s.items.toMutableList().apply { removeAt(index) }
        if (items.isEmpty()) return clearAll()
        val newSelected = when {
            index < s.selected -> s.selected - 1
            index == s.selected -> index.coerceAtMost(items.lastIndex)
            else -> s.selected
        }
        _state.update { it.copy(items = items, selected = newSelected, positionMs = 0, preview = null) }
        requestPreview()
    }

    fun clearAll() {
        if (_state.value.running) return
        _state.update { UiState(settings = it.settings) }
        viewModelScope.launch(retrieverDispatcher) {
            retriever?.release()
            retriever = null
            retrieverUri = null
        }
    }

    // ---- settings --------------------------------------------------------------------------

    fun seek(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
        requestPreview()
    }

    fun setSize(size: TimecodeSize) = updateStyle { it.copy(size = size) }
    fun setBackground(on: Boolean) = updateStyle { it.copy(background = on) }
    fun setCorner(corner: Corner) = updateStyle { it.copy(corner = corner) }

    fun setResolution(resolution: Resolution) = updateSettings { it.copy(resolution = resolution) }

    private fun updateStyle(change: (TimecodeStyle) -> TimecodeStyle) =
        updateSettings { it.copy(style = change(it.style)) }

    private fun updateSettings(change: (ExportSettings) -> ExportSettings) {
        if (_state.value.running) return
        val next = change(_state.value.settings)
        _state.update { it.copy(settings = next) }
        store.save(next)
        requestPreview()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Leaves the result view; statuses go back to "waiting" so the batch can be run again. */
    fun resetExport() = _state.update { s ->
        s.copy(batch = BatchState.Idle, items = s.items.map { it.copy(status = ItemStatus.Waiting) })
    }

    // ---- preview ---------------------------------------------------------------------------

    private fun requestPreview() {
        val s = _state.value
        val video = s.selectedVideo ?: return
        previewRequest.tryEmit(PreviewRequest(video, s.positionMs, s.settings.style))
    }

    private suspend fun renderPreview(req: PreviewRequest) {
        val video = req.video
        val bmp = withContext(retrieverDispatcher) {
            val r = runCatching { retrieverFor(video.uri) }.getOrNull() ?: return@withContext null
            val scale = minOf(1f, 1080f / maxOf(video.width, video.height))
            val w = (video.width * scale).roundToInt()
            val h = (video.height * scale).roundToInt()
            val frame = runCatching {
                r.getScaledFrameAtTime(req.positionMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
            }.getOrNull() ?: return@withContext null
            val digits = minuteDigitsFor(video.durationMs)
            TimecodeRenderer(frame.width, frame.height, req.style, digits)
                .compose(frame, formatTimecode(req.positionMs, digits))
                .also { frame.recycle() }
        }
        if (bmp != null && _state.value.selectedVideo?.uri == video.uri) {
            _state.update { it.copy(preview = bmp) }
        }
    }

    /** Must run on [retrieverDispatcher]. Keeps one retriever open for the previewed video. */
    private fun retrieverFor(uri: Uri): MediaMetadataRetriever {
        retriever?.takeIf { retrieverUri == uri }?.let { return it }
        retriever?.release()
        retriever = null
        val r = MediaMetadataRetriever()
        r.setDataSource(getApplication(), uri)
        retriever = r
        retrieverUri = uri
        return r
    }

    private fun probe(uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(getApplication(), uri)
            val duration = r.meta(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val w = r.meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = r.meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rot = r.meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            require(w > 0 && h > 0) { "Keine Videospur gefunden" }
            val swap = rot % 180 != 0L
            return VideoInfo(
                uri = uri,
                name = displayName(uri),
                durationMs = duration,
                width = if (swap) h.toInt() else w.toInt(),
                height = if (swap) w.toInt() else h.toInt(),
            )
        } finally {
            r.release()
        }
    }

    // ---- export ----------------------------------------------------------------------------

    fun startExport() {
        val s = _state.value
        if (s.items.isEmpty() || s.running) return
        val settings = s.settings
        _state.update { st -> st.copy(items = st.items.map { it.copy(status = ItemStatus.Waiting) }) }

        batchJob = viewModelScope.launch {
            for (index in _state.value.items.indices) {
                val video = _state.value.items[index].video
                _state.update { it.copy(batch = BatchState.Running(index)) }
                setStatus(index, ItemStatus.Running(null))
                val status = try {
                    val (uri, name) = exportOne(index, video, settings)
                    ItemStatus.Done(uri, name)
                } catch (e: CancellationException) {
                    setStatus(index, ItemStatus.Waiting)
                    throw e
                } catch (e: Exception) {
                    ItemStatus.Failed(e.message ?: e.javaClass.simpleName)
                }
                setStatus(index, status)
            }
            _state.update { it.copy(batch = BatchState.Finished) }
        }
    }

    fun cancelExport() {
        batchJob?.cancel()
        batchJob = null
        _state.update { s ->
            s.copy(
                batch = BatchState.Idle,
                items = s.items.map {
                    if (it.status is ItemStatus.Running) it.copy(status = ItemStatus.Waiting) else it
                },
            )
        }
    }

    private fun setStatus(index: Int, status: ItemStatus) = _state.update { s ->
        s.copy(items = s.items.mapIndexed { i, item -> if (i == index) item.copy(status = status) else item })
    }

    /** Burns the timecode into one video and saves it to the gallery. */
    private suspend fun exportOne(index: Int, video: VideoInfo, settings: ExportSettings): Pair<Uri, String> {
        val app = getApplication<Application>()
        val tmp = File(app.cacheDir, "export.mp4").apply { delete() }
        try {
            transform(index, video, settings, tmp)
            return publish(tmp, video)
        } finally {
            tmp.delete()
        }
    }

    /** Runs on the main thread: Transformer must be created, used and cancelled there. */
    private suspend fun transform(index: Int, video: VideoInfo, settings: ExportSettings, out: File) {
        val app = getApplication<Application>()
        // Scale first so the label is sized for the final frame.
        val effects = buildList<Effect> {
            settings.resolution.targetFor(video)?.let { add(Presentation.createForShortSide(it)) }
            add(OverlayEffect(listOf(TimecodeOverlay(settings.style, minuteDigitsFor(video.durationMs)))))
        }
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(video.uri))
            .setEffects(Effects(emptyList(), effects))
            .build()
        // Phones often record HDR (HLG/HDR10). The output is H.264, so tone-map to SDR;
        // otherwise HDR clips would fail on most devices or come out with odd colors.
        @Suppress("DEPRECATION")
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(app)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    done.completeExceptionally(
                        Exception(exportException.message ?: exportException.errorCodeName),
                    )
                }
            })
            .build()
        transformer.start(composition, out.absolutePath)

        coroutineScope {
            val progress = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        setStatus(index, ItemStatus.Running(holder.progress))
                    }
                    delay(200)
                }
            }
            try {
                done.await()
            } catch (e: CancellationException) {
                transformer.cancel()
                throw e
            } finally {
                progress.cancel()
            }
        }
    }

    /** Copies the finished file into DCIM/Timecode so it shows up in the gallery. */
    private suspend fun publish(tmp: File, video: VideoInfo): Pair<Uri, String> {
        val app = getApplication<Application>()
        val base = video.name.substringBeforeLast('.').ifBlank { "video" }
        val fileName = "${base}_timecode.mp4"
        return withContext(Dispatchers.IO) {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Timecode")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = requireNotNull(resolver.insert(collection, values)) { "Speichern nicht möglich" }
            try {
                resolver.openOutputStream(uri)!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri to fileName
        }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "video"
    }

    private fun MediaMetadataRetriever.meta(key: Int): Long =
        extractMetadata(key)?.toLongOrNull() ?: 0L

    override fun onCleared() {
        batchJob?.cancel()
        retriever?.release()
        super.onCleared()
    }
}
