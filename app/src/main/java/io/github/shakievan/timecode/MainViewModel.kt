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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
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

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val percent: Int?) : ExportState
    data class Done(val uri: Uri, val fileName: String) : ExportState
    data class Failed(val message: String) : ExportState
}

data class UiState(
    val video: VideoInfo? = null,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val preview: Bitmap? = null,
    val style: TimecodeStyle = TimecodeStyle(),
    val export: ExportState = ExportState.Idle,
    val error: String? = null,
)

@OptIn(UnstableApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // MediaMetadataRetriever isn't thread-safe; all access goes through this one-at-a-time dispatcher.
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private val retrieverDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var retriever: MediaMetadataRetriever? = null
    private val previewRequest = MutableSharedFlow<Pair<Long, TimecodeStyle>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var transformer: Transformer? = null
    private var progressJob: Job? = null

    init {
        viewModelScope.launch {
            previewRequest.collectLatest { (pos, style) -> renderPreview(pos, style) }
        }
    }

    fun openVideo(uri: Uri) {
        if (_state.value.export is ExportState.Running) return
        _state.update { UiState(loading = true, style = it.style) }
        viewModelScope.launch {
            val info = withContext(retrieverDispatcher) {
                runCatching {
                    retriever?.release()
                    val r = MediaMetadataRetriever().also { retriever = it }
                    r.setDataSource(getApplication(), uri)
                    val duration = r.meta(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val w = r.meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = r.meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val rot = r.meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    require(w > 0 && h > 0) { "Keine Videospur gefunden" }
                    val swap = rot % 180 != 0L
                    VideoInfo(
                        uri = uri,
                        name = displayName(uri),
                        durationMs = duration,
                        width = if (swap) h.toInt() else w.toInt(),
                        height = if (swap) w.toInt() else h.toInt(),
                    )
                }
            }
            info.onSuccess { v ->
                _state.update { it.copy(video = v, loading = false, positionMs = 0) }
                requestPreview()
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, error = "Video konnte nicht geöffnet werden: ${e.message}")
                }
            }
        }
    }

    fun seek(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
        requestPreview()
    }

    fun setSize(size: TimecodeSize) = setStyle(_state.value.style.copy(size = size))

    fun setBackground(on: Boolean) = setStyle(_state.value.style.copy(background = on))

    private fun setStyle(style: TimecodeStyle) {
        _state.update { it.copy(style = style) }
        requestPreview()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun resetExport() = _state.update { it.copy(export = ExportState.Idle) }

    fun clearVideo() {
        if (_state.value.export is ExportState.Running) return
        _state.update { UiState(style = it.style) }
        viewModelScope.launch(retrieverDispatcher) {
            retriever?.release()
            retriever = null
        }
    }

    private fun requestPreview() {
        val s = _state.value
        if (s.video != null) previewRequest.tryEmit(s.positionMs to s.style)
    }

    private suspend fun renderPreview(positionMs: Long, style: TimecodeStyle) {
        val video = _state.value.video ?: return
        val bmp = withContext(retrieverDispatcher) {
            val r = retriever ?: return@withContext null
            val scale = minOf(1f, 1080f / maxOf(video.width, video.height))
            val w = (video.width * scale).roundToInt()
            val h = (video.height * scale).roundToInt()
            val frame = runCatching {
                r.getScaledFrameAtTime(
                    positionMs * 1_000,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    w,
                    h,
                )
            }.getOrNull() ?: return@withContext null
            val renderer = TimecodeRenderer(
                frame.width, frame.height, style, minuteDigitsFor(video.durationMs),
            )
            renderer.compose(frame, formatTimecode(positionMs, minuteDigitsFor(video.durationMs)))
                .also { frame.recycle() }
        }
        if (bmp != null && _state.value.video?.uri == video.uri) {
            _state.update { it.copy(preview = bmp) }
        }
    }

    fun startExport() {
        val s = _state.value
        val video = s.video ?: return
        if (s.export is ExportState.Running) return
        val app = getApplication<Application>()
        val tmp = File(app.cacheDir, "export.mp4").apply { delete() }

        val overlay = TimecodeOverlay(s.style, minuteDigitsFor(video.durationMs))
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(video.uri))
            .setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf(overlay)))))
            .build()
        // Phones often record HDR (HLG/HDR10). The output is H.264, so tone-map to SDR;
        // otherwise HDR clips would fail on most devices or come out with odd colors.
        @Suppress("DEPRECATION")
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        val t = Transformer.Builder(app)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressJob?.cancel()
                    transformer = null
                    viewModelScope.launch { publish(tmp, video) }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    progressJob?.cancel()
                    transformer = null
                    tmp.delete()
                    _state.update {
                        it.copy(
                            export = ExportState.Failed(
                                exportException.message ?: exportException.errorCodeName,
                            ),
                        )
                    }
                }
            })
            .build()

        transformer = t
        _state.update { it.copy(export = ExportState.Running(null)) }
        t.start(composition, tmp.absolutePath)

        progressJob = viewModelScope.launch {
            val holder = ProgressHolder()
            while (isActive) {
                val state = t.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    _state.update {
                        if (it.export is ExportState.Running) {
                            it.copy(export = ExportState.Running(holder.progress))
                        } else {
                            it
                        }
                    }
                }
                delay(200)
            }
        }
    }

    fun cancelExport() {
        transformer?.cancel()
        transformer = null
        progressJob?.cancel()
        File(getApplication<Application>().cacheDir, "export.mp4").delete()
        _state.update { it.copy(export = ExportState.Idle) }
    }

    /** Copies the finished file into DCIM/Timecode so it shows up in the gallery. */
    private suspend fun publish(tmp: File, video: VideoInfo) {
        val app = getApplication<Application>()
        val base = video.name.substringBeforeLast('.').ifBlank { "video" }
        val fileName = "${base}_timecode.mp4"
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = app.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_DCIM + "/Timecode",
                    )
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val collection =
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = requireNotNull(resolver.insert(collection, values)) {
                    "Speichern nicht möglich"
                }
                try {
                    resolver.openOutputStream(uri)!!.use { out ->
                        tmp.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                uri
            }.also { tmp.delete() }
        }
        result.onSuccess { uri ->
            _state.update { it.copy(export = ExportState.Done(uri, fileName)) }
        }.onFailure { e ->
            _state.update { it.copy(export = ExportState.Failed(e.message ?: "Fehler beim Speichern")) }
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
        transformer?.cancel()
        retriever?.release()
        super.onCleared()
    }
}
