package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.LinearLayout
import `in`.opentrack.india.R

/** One continuous route spine behind the stop, its time sheet, the compact
 * train leg and optional map points. Row padding changes with km; a line only
 * inside the stop header would break exactly where those gaps are largest. */
class RouteStopGroup @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.stroke)
        strokeWidth = 2f * resources.displayMetrics.density
    }

    init { setWillNotDraw(false) }

    internal fun paintForTest(canvas: Canvas) = onDraw(canvas)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = RouteRailGeometry.X_DP * resources.displayMetrics.density
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
    }
}
