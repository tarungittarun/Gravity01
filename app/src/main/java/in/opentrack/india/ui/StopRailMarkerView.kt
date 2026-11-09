package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import `in`.opentrack.india.R

/** The locomotive replaces (rather than follows) the dot of the reported station.
 * Its centre is exactly on RouteStopGroup's x=36dp rail. There is no implication
 * that a departure report proves movement beyond the station. */
class StopRailMarkerView @JvmOverloads constructor(context: Context,
    attrs: AttributeSet? = null, defStyle: Int = 0) : View(context, attrs, defStyle) {
    var ageSeconds: Long? = null
        set(value) { field = value; invalidate() }
    var batteryOutline: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private fun dp(v: Float) = v * resources.displayMetrics.density
    internal fun paintForTest(canvas: Canvas) = onDraw(canvas)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width / 2f
        val y = height / 2f
        if (batteryOutline) {
            BatteryTrainSketch.draw(canvas, x, y, resources.displayMetrics.density, paint)
            return
        }
        paint.style = Paint.Style.FILL
        paint.color = context.getColor(
            if (ageSeconds?.let { it >= SourceFreshness.STALE_AFTER_MS / 1_000L } == true)
                R.color.late else R.color.live)
        canvas.drawCircle(x, y, dp(18f), paint)
        paint.color = context.getColor(R.color.bg)
        box.set(x - dp(9f), y - dp(10f), x + dp(9f), y + dp(7f))
        canvas.drawRoundRect(box, dp(4f), dp(4f), paint)
        paint.color = context.getColor(R.color.text1)
        canvas.drawRoundRect(x - dp(6f), y - dp(7f), x - dp(1f), y - dp(1f), dp(1f), dp(1f), paint)
        canvas.drawRoundRect(x + dp(1f), y - dp(7f), x + dp(6f), y - dp(1f), dp(1f), dp(1f), paint)
        canvas.drawCircle(x - dp(5f), y + dp(9f), dp(2f), paint)
        canvas.drawCircle(x + dp(5f), y + dp(9f), dp(2f), paint)
        if (ageSeconds?.let { it in 0L..59L } == true) {
            // An under-one-minute SOURCE EVENT, not a GPS fix or page-update clock.
            val cx = x + dp(15f)
            val cy = y - dp(14f)
            paint.color = context.getColor(R.color.bg)
            canvas.drawCircle(cx, cy, dp(9f), paint)
            paint.color = context.getColor(R.color.accent)
            canvas.drawCircle(cx, cy, dp(7f), paint)
            paint.color = context.getColor(R.color.bg)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.8f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val check = Path().apply {
                moveTo(cx - dp(3f), cy)
                lineTo(cx - dp(.5f), cy + dp(2.5f))
                lineTo(cx + dp(3.5f), cy - dp(2f))
            }
            canvas.drawPath(check, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
