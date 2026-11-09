package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import `in`.opentrack.india.R

/**
 * The journey bar: how far this train has come between *the passenger's* two stations.
 *
 * Two marks, because the app's whole claim is the comparison: the filled length is where the train is
 * (live if a report reached us, otherwise what the timetable implies), and the pale tick is where the
 * schedule says it should be at this minute. When the two sit on top of each other the train is on time,
 * and you can see that without reading a number.
 */
class JourneyProgressView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var pct: Int = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }

    /** Where the timetable puts the train, same axis as [pct]. Negative hides the tick. */
    var schedulePct: Int = -1
        set(v) { field = v.coerceIn(-1, 100); invalidate() }

    /** True when the position came from a live report; changes only the colour, never the geometry. */
    var live: Boolean = false
        set(v) { field = v; invalidate() }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B2A36.toInt(); style = Paint.Style.FILL
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9FB4C2.toInt(); strokeWidth = dp(2f); style = Paint.Style.STROKE
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (dp(12f)).toInt()
        setMeasuredDimension(w, maxOf(h, MeasureSpec.getSize(heightMeasureSpec)))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2f
        val w = width.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)

        val accent = getColor(R.color.accent)
        val dim = getColor(R.color.text3)
        fill.color = if (live) accent else dim
        knob.color = if (live) accent else getColor(R.color.text2)

        val x = (w * pct / 100f)
        if (pct > 0) {
            rect.set(0f, 0f, x.coerceAtMost(w), h)
            canvas.drawRoundRect(rect, r, r, fill)
        }
        // The knob sits at the head of the fill; a hairline outline keeps it visible on the accent fill
        // as well as on the empty track, which a single-colour dot cannot do.
        val kr = h * 0.46f
        canvas.drawCircle(x.coerceIn(kr, w - kr), h / 2f, kr, knob)
        if (schedulePct >= 0) {
            val sx = (w * schedulePct / 100f).coerceIn(dp(1f), w - dp(1f))
            canvas.drawLine(sx, -h * 0.35f, sx, h * 1.35f, tick)
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun getColor(id: Int): Int = context.getColor(id)
}
