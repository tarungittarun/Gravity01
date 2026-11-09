package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import `in`.opentrack.india.R
import kotlin.math.hypot
import kotlin.math.max

/** A finite refresh RECEIPT, not simulated movement of the train. The parent viewport
 * clips all three concentric rings to the status table; nothing escapes into the hero. */
class StatusRippleView @JvmOverloads constructor(context: Context,
    attrs: AttributeSet? = null, defStyle: Int = 0) : View(context, attrs, defStyle) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private var started = 0L
    private var originX = 0f
    private var originY = 0f

    fun pulse(x: Float, y: Float) {
        if (width == 0 || height == 0) return
        originX = x.coerceIn(0f, width.toFloat())
        originY = y.coerceIn(0f, height.toFloat())
        started = SystemClock.uptimeMillis().coerceAtLeast(1L)
        invalidate()
    }

    fun cancelPulse() {
        started = 0L
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelPulse()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (started == 0L) return
        val elapsed = (SystemClock.uptimeMillis() - started).coerceAtLeast(0L)
        if (elapsed >= DURATION_MS) { started = 0L; return }
        val t = elapsed.toFloat() / DURATION_MS
        val far = hypot(max(originX, width - originX), max(originY, height - originY))
        val ink = context.getColor(R.color.live)
        val save = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        for (i in 0..2) {
            val phase = (t - i * .17f).coerceIn(0f, 1f)
            if (phase > 0f) {
                paint.color = ink
                paint.alpha = (110 * (1f - phase) * (1f - phase)).toInt()
                canvas.drawCircle(originX, originY, 12f * resources.displayMetrics.density +
                    phase * far, paint)
            }
        }
        canvas.restoreToCount(save)
        postInvalidateDelayed(16L)
    }

    companion object { const val DURATION_MS = 1_000L }
}
