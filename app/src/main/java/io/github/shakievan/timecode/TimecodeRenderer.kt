package io.github.shakievan.timecode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class TimecodeSize(val label: String, val factor: Float) {
    S("S", 0.040f),
    M("M", 0.055f),
    L("L", 0.075f),
}

enum class Corner(val label: String, val top: Boolean, val left: Boolean) {
    TOP_LEFT("Oben links", true, true),
    TOP_RIGHT("Oben rechts", true, false),
    BOTTOM_LEFT("Unten links", false, true),
    BOTTOM_RIGHT("Unten rechts", false, false),
}

data class TimecodeStyle(
    val size: TimecodeSize = TimecodeSize.M,
    val background: Boolean = true,
    val corner: Corner = Corner.TOP_LEFT,
)

/** Formats milliseconds as MM:SS:mmm. Minutes keep counting past 59 (e.g. 75:12:345). */
fun formatTimecode(ms: Long, minuteDigits: Int = 2): String {
    val t = max(0L, ms)
    val minutes = t / 60_000
    val seconds = (t / 1_000) % 60
    val millis = t % 1_000
    return "%0${minuteDigits}d:%02d:%03d".format(minutes, seconds, millis)
}

fun minuteDigitsFor(durationMs: Long): Int = max(2, (durationMs / 60_000).toString().length)

/**
 * Draws the timecode label. Everything is proportional to the shorter side of the frame, so the
 * same renderer produces an identical-looking label for the small preview and the full video.
 */
class TimecodeRenderer(
    frameWidth: Int,
    frameHeight: Int,
    private val style: TimecodeStyle,
    minuteDigits: Int,
) {
    private val unit = min(frameWidth, frameHeight).toFloat()
    private val fontPx = max(10f, unit * style.size.factor)

    /** Distance of the label from the two nearest frame edges, in pixels. */
    val marginPx: Float = unit * 0.03f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        textSize = fontPx
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        // Tabular digits keep the label from jittering as numbers change.
        fontFeatureSettings = "tnum"
        letterSpacing = 0.02f
    }
    private val outlinePaint = Paint(textPaint).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = fontPx * 0.12f
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 0, 0, 0)
    }

    private val padH = fontPx * if (style.background) 0.45f else 0.15f
    private val padV = fontPx * if (style.background) 0.32f else 0.15f
    private val radius = fontPx * 0.3f
    private val digitHeight: Int
    private val textWidth: Float

    val width: Int
    val height: Int

    init {
        val template = "8".repeat(minuteDigits) + ":88:888"
        textWidth = textPaint.measureText(template)
        val bounds = Rect()
        textPaint.getTextBounds("0", 0, 1, bounds)
        digitHeight = bounds.height()
        width = ceil(textWidth + 2 * padH).toInt()
        height = ceil(digitHeight + 2 * padV).toInt()
    }

    /** Draws [text] into a canvas whose size is exactly [width] x [height]. */
    fun drawLabel(canvas: Canvas, text: String) {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        val baseline = padV + digitHeight
        if (style.background) {
            canvas.drawRoundRect(
                RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, boxPaint,
            )
        } else {
            canvas.drawText(text, padH, baseline, outlinePaint)
        }
        canvas.drawText(text, padH, baseline, textPaint)
    }

    /** Returns a copy of [frame] with the label drawn in the chosen corner, for the in-app preview. */
    fun compose(frame: Bitmap, text: String): Bitmap {
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        canvas.save()
        val x = if (style.corner.left) marginPx else out.width - marginPx - width
        val y = if (style.corner.top) marginPx else out.height - marginPx - height
        canvas.translate(x, y)
        canvas.clipRect(0, 0, width, height)
        // Draw onto a temporary layer so CLEAR only affects the label area.
        val label = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawLabel(Canvas(label), text)
        canvas.drawBitmap(label, 0f, 0f, null)
        label.recycle()
        canvas.restore()
        return out
    }
}
