package io.github.shakievan.timecode

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings

/**
 * Media3 overlay that redraws a small label bitmap for every frame and anchors it to the
 * bottom-left corner. Only the label (not a full-frame bitmap) is uploaded per frame.
 */
@UnstableApi
class TimecodeOverlay(
    private val style: TimecodeStyle,
    private val minuteDigits: Int,
) : BitmapOverlay() {

    private lateinit var renderer: TimecodeRenderer
    private lateinit var bitmap: Bitmap
    private lateinit var canvas: Canvas
    private var settings: OverlaySettings = StaticOverlaySettings.Builder().build()
    private var lastText: String? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val w = videoSize.width
        val h = videoSize.height
        renderer = TimecodeRenderer(w, h, style, minuteDigits)
        bitmap = Bitmap.createBitmap(renderer.width, renderer.height, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap)
        lastText = null
        // Anchors are in normalized device coordinates: (-1, -1) is the bottom-left corner.
        settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                -1f + 2f * renderer.marginPx / w,
                -1f + 2f * renderer.marginPx / h,
            )
            .setOverlayFrameAnchor(-1f, -1f)
            .build()
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val text = formatTimecode(presentationTimeUs / 1_000, minuteDigits)
        if (text != lastText) {
            renderer.drawLabel(canvas, text)
            lastText = text
        }
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun release() {
        super.release()
        if (::bitmap.isInitialized) bitmap.recycle()
    }
}
