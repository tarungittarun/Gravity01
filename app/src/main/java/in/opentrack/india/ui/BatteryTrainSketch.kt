package `in`.opentrack.india.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Static white pen strokes on black OLED pixels. No filled halo, animation,
 * page-refresh movement or second train; use only at a source's last event. */
internal object BatteryTrainSketch {
    fun draw(canvas: Canvas, x: Float, y: Float, density: Float, paint: Paint) {
        fun dp(value: Float) = value * density
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.8f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawRoundRect(x - dp(11f), y - dp(12f), x + dp(11f), y + dp(7f),
            dp(3f), dp(3f), paint)
        canvas.drawRoundRect(x - dp(7f), y - dp(8f), x - dp(1f), y - dp(2f),
            dp(1f), dp(1f), paint)
        canvas.drawRoundRect(x + dp(1f), y - dp(8f), x + dp(7f), y - dp(2f),
            dp(1f), dp(1f), paint)
        canvas.drawCircle(x - dp(6f), y + dp(10f), dp(1.8f), paint)
        canvas.drawCircle(x + dp(6f), y + dp(10f), dp(1.8f), paint)
        canvas.drawLine(x - dp(12f), y + dp(14f), x + dp(12f), y + dp(14f), paint)
        paint.style = Paint.Style.FILL
    }
}
