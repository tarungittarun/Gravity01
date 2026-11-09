package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import `in`.opentrack.india.R

/** An icon on ONE reported halt-to-halt leg. Geometry is a projection of the last reported
 * departure, never a GPS dot or proof of passage at an intermediate point. Hidden when stale. */
class LegPositionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {
    var fraction = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carriage = RectF()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(54f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(26f)
        val right = (width - dp(26f)).coerceAtLeast(left + 1f)
        val railY = dp(39f)
        paint.color = context.getColor(R.color.stroke)
        paint.strokeWidth = dp(4f)
        canvas.drawLine(left, railY, right, railY, paint)
        paint.color = context.getColor(R.color.live)
        canvas.drawLine(left, railY, left + (right - left) * fraction, railY, paint)
        canvas.drawCircle(left, railY, dp(6f), paint)
        canvas.drawCircle(right, railY, dp(6f), paint)

        // Tiny train carriage, not a location pin: relative placement is the estimate.
        val x = left + (right - left) * fraction
        carriage.set(x - dp(15f), dp(6f), x + dp(15f), dp(29f))
        paint.color = context.getColor(R.color.live)
        canvas.drawRoundRect(carriage, dp(6f), dp(6f), paint)
        paint.color = context.getColor(R.color.ink_on_accent)
        canvas.drawRoundRect(x - dp(10f), dp(10f), x + dp(10f), dp(18f),
            dp(2f), dp(2f), paint)
        canvas.drawCircle(x - dp(8f), dp(31f), dp(3f), paint)
        canvas.drawCircle(x + dp(8f), dp(31f), dp(3f), paint)
    }
}
