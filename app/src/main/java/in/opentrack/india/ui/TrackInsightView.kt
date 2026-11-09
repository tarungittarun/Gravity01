package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import `in`.opentrack.india.R
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.TrainPosition
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Distance against time for one journey — the picture that answers "is it late, and how much of the
 * trip is left" without reading a number.
 *
 * Two paths, because the comparison is the product. The pale dashed path is the timetable; the solid one
 * is where the train actually is, or where the timetable puts it right now when nothing reported — in
 * which case it is drawn amber and dotted, never in the green that means "a feed said so". The gap
 * between the two *is* the delay, and it widens where the train lost time and closes where it made the
 * time back, which a single "14 min late" cannot express.
 *
 * The vertical axis is relative to the boarding stop, so a 950-halt train that starts at halt 600 does
 * not squash into the left edge, and it falls back to halt index when the reference data has no
 * distances. Both cases are labelled inside the view: an axis must never be able to imply a unit it
 * does not have. (It used to: with the distance column silently all zeros, the axis printed 0 1 1 1.)
 */
class TrackInsightView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val padL = dp(40f)
    private val padR = dp(10f)
    private val padTop = dp(28f)
    private val padBot = dp(20f)

    private val grid = Paint().apply { color = getColor(R.color.stroke); strokeWidth = dp(1f) }
    private val dayLine = Paint().apply { color = 0x55FFFFFF; strokeWidth = dp(1f) }
    private val yLabel = Paint().apply {
        color = getColor(R.color.text3); textSize = sp(10f); textAlign = Paint.Align.RIGHT
    }
    private val xLabel = Paint().apply {
        color = getColor(R.color.text3); textSize = sp(10f); textAlign = Paint.Align.CENTER
    }
    private val caption = Paint().apply { color = getColor(R.color.text2); textSize = sp(10f) }
    private val schedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = getColor(R.color.text3); style = Paint.Style.STROKE; strokeWidth = dp(1.4f)
        pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val shownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.4f); strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hollowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.6f)
    }
    private val nowLine = Paint().apply { color = 0x66FFFFFF; strokeWidth = dp(1f) }
    private val nowRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.4f)
    }
    private val nowText = Paint().apply { textSize = sp(10f); textAlign = Paint.Align.CENTER }
    private val emptyText = Paint().apply {
        color = getColor(R.color.text2); textSize = sp(12f); textAlign = Paint.Align.CENTER
    }

    private val schedPath = Path()
    private val shownPath = Path()
    private val areaPath = Path()
    private val dots = ArrayList<FloatArray>()

    private var x0 = 0f
    private var x1 = 1f
    private var yMax = 1f
    private var yStep = 1f
    private var xStepMin = 60
    private var hasDistance = false
    private var isLive = false
    private var ready = false
    private var plotted = false
    private var nowX = -1f
    private var nowY = -1f
    private var captionText = ""

    /**
     * @param window indices into [TrackModel.View.rows] delimiting the passenger's own segment.
     * @param pos the engine's position, for the marker; null draws the schedule alone.
     */
    fun setJourney(view: TrackModel.View, pos: TrainPosition?, nowRailMinute: Int, window: IntRange) {
        ready = view.rows.isNotEmpty()
        if (!ready) { invalidate(); return }
        hasDistance = view.hasDistances
        isLive = view.isLive
        val rows = view.rows
        val lo = window.first.coerceIn(0, rows.size - 1)
        val hi = window.last.coerceIn(lo + 1, rows.size - 1)
        val slice = rows.subList(lo, hi + 1)
        val baseKm = slice.firstOrNull()?.kmFromOrigin ?: 0

        val times = ArrayList<Int>(slice.size * 2 + 1)
        var top = 0
        for (s in slice) {
            s.schedDep?.let { times += it }
            s.schedArr?.let { times += it }
            s.showDep?.let { times += it }
            s.showArr?.let { times += it }
            top = max(top, if (hasDistance) s.kmFromBoard else s.seq - (slice.first().seq))
        }
        times += nowRailMinute
        if (times.size < 2) { plotted = false; invalidate(); return }

        x0 = (times.minOrNull()!! - 25).toFloat()
        x1 = max(times.maxOrNull()!!.toFloat() + 25f, x0 + 45f)
        yMax = max(top, 1).toFloat()
        yStep = niceStep(yMax)
        yMax = ceil(yMax / yStep) * yStep
        if (!hasDistance) yMax = max(yMax, slice.size - 1f)

        xStepMin = when {
            x1 - x0 <= 180f -> 30
            x1 - x0 <= 420f -> 60
            x1 - x0 <= 900f -> 120
            x1 - x0 <= 1500f -> 180
            else -> 360
        }

        // Build the two polylines from their own columns. Feeding both into one list — which an earlier
        // revision did — drew a line that zigzagged between the schedule and the report at every other
        // stop, which is the opposite of the point.
        schedPath.rewind(); shownPath.rewind(); areaPath.rewind(); dots.clear()
        var any = false
        var lastShownMinute = -1
        slice.forEachIndexed { i, s ->
            val km = if (hasDistance) (s.kmFromOrigin - baseKm).toFloat() else i.toFloat()
            val st = s.schedDep ?: s.schedArr
            if (st != null) {
                val x = tx(st.toFloat()); val y = ty(km)
                if (schedPath.isEmpty) schedPath.moveTo(x, y) else schedPath.lineTo(x, y)
                any = true
            }
            // `lastShownMinute` exists because the x axis *is* time: one reported minute that lands before the
            // previous stop's — a stale row from a provider, a halt whose day the feed left out — would draw
            // the line back on itself, and a loop in a plot of "distance so far" reads as the train reversing.
            // Only the drawn path is clamped; the table keeps printing what was reported.
            val lt = s.showDep ?: s.showArr
            val shownX = if (lt == null) -1f else {
                val minute = if (lastShownMinute < 0) lt else max(lt, lastShownMinute)
                lastShownMinute = minute
                tx(minute.toFloat())
            }
            if (lt != null && shownX >= 0f) {
                val y = ty(km)
                if (shownPath.isEmpty) { shownPath.moveTo(shownX, y); areaPath.moveTo(shownX, height - padBot) }
                else shownPath.lineTo(shownX, y)
                areaPath.lineTo(shownX, y)
            }
            if (s.isAnchor || s.isBoarding || s.isAlighting ||
                (i % max(1, ceil(slice.size / 24f).toInt()) == 0)) {
                val x = if (shownX >= 0f) shownX else st?.let { tx(it.toFloat()) } ?: -1f
                if (x >= 0f) dots.add(
                    floatArrayOf(x, ty(km), if (s.isAnchor || s.isBoarding || s.isAlighting) 1f else 0f)
                )
            }
        }
        val lastShownX = lastXOf(areaPath)
        if (lastShownX > 0f) { areaPath.lineTo(lastShownX, height - padBot); areaPath.close() }
        plotted = any
        nowX = if (pos != null && nowRailMinute >= x0 && nowRailMinute <= x1) tx(nowRailMinute.toFloat()) else -1f
        nowY = if (pos != null && hasDistance) {
            ty(min((pos.kmAlong - baseKm).coerceAtLeast(0f), yMax))
        } else if (pos != null) {
            ty(slice.indexOfFirst { it.isAnchor }.coerceAtLeast(0).toFloat())
        } else -1f
        captionText = if (hasDistance) {
            val total = slice.lastOrNull()?.let { it.kmFromOrigin - baseKm } ?: 0
            "${slice.firstOrNull()?.code ?: "?"} → ${slice.lastOrNull()?.code ?: "?"} · $total km"
        } else "halt index · this train has no distance data offline"
        invalidate()
    }

    private fun lastXOf(p: Path): Float {
        val m = android.graphics.RectF()
        p.computeBounds(m, true)
        return if (m.width() > 0f || m.height() > 0f) m.right else -1f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = max(dp(120f).toInt(), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (!ready) return
        val l = padL; val r = width - padR; val t = padTop; val b = height - padBot
        if (r - l < dp(48f) || b - t < dp(48f)) return
        if (!plotted) {
            canvas.drawText("not enough of this run to plot yet", (l + r) / 2f, (t + b) / 2f, emptyText)
            return
        }
        val tone = if (isLive) getColor(R.color.accent) else getColor(R.color.warn)
        shownPaint.color = tone
        shownPaint.pathEffect = if (isLive) null else DashPathEffect(floatArrayOf(dp(2.5f), dp(3f)), 0f)
        fillPaint.color = (tone and 0x00FFFFFF) or 0x22000000
        dotPaint.color = tone
        hollowPaint.color = (tone and 0x00FFFFFF) or 0x99000000.toInt()
        nowRing.color = tone
        nowText.color = tone

        // A while loop, not `0f..yMax step yStep`: FloatRange.step is deprecated in Kotlin 1.9 and a
        // floating progression can also skip the last gridline on a rounding edge.
        var km = 0f
        while (km <= yMax + 0.0001f) {
            val y = ty(km)
            canvas.drawLine(l, y, r, y, grid)
            canvas.drawText(km.toInt().toString(), l - dp(5f), y + sp(3.5f), yLabel)
            km += yStep
        }
        var m = (ceil((x0 / xStepMin).toDouble()) * xStepMin).toInt()
        while (m.toFloat() <= x1) {
            val x = tx(m.toFloat())
            val dayRoll = m % RailTime.MINUTES_PER_DAY == 0
            canvas.drawLine(x, t, x, b, if (dayRoll) dayLine else grid)
            canvas.drawText(xLabelText(m), x, b + sp(13f), xLabel)
            m += xStepMin
        }
        canvas.drawText(if (hasDistance) "km" else "halt #", l - dp(5f), t - dp(12f), yLabel)
        canvas.drawText(captionText, l, sp(11f), caption)

        canvas.clipRect(l - dp(1f), t - dp(8f), r + dp(1f), b + dp(2f))
        canvas.drawPath(schedPath, schedPaint)
        canvas.drawPath(areaPath, fillPaint)
        canvas.drawPath(shownPath, shownPaint)
        for (d in dots) {
            if (d[2] > 0.5f) canvas.drawCircle(d[0], d[1], dp(4.6f), dotPaint)
            else canvas.drawCircle(d[0], d[1], dp(2.6f), hollowPaint)
        }
        if (nowX > 0f) {
            canvas.drawLine(nowX, t - dp(8f), nowX, b, nowLine)
            if (nowY > 0f) canvas.drawCircle(nowX, nowY.coerceIn(t, b), dp(6f), nowRing)
            canvas.drawText("now", nowX.coerceIn(l + dp(14f), r - dp(12f)), t - dp(11f), nowText)
        }
    }

    private fun xLabelText(minute: Int): String {
        val hour = RailTime.formatRailMinuteBare(minute).substringBefore(':')
        return hour + "h" + if (minute >= RailTime.MINUTES_PER_DAY) "·+1" else ""
    }

    /** A step of 1/2/2.5/5 × 10^n, at least 1, so gridline labels are whole numbers. */
    private fun niceStep(span: Float): Float {
        val raw = span / 4f
        if (raw <= 1f) return 1f
        val pow = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
        for (m in floatArrayOf(1f, 2f, 2.5f, 5f, 10f)) if (pow * m >= raw) return max(pow * m, 1f)
        return max(pow * 10f, 1f)
    }

    private fun tx(minute: Float): Float =
        padL + (minute - x0) / max(x1 - x0, 1f) * (width - padL - padR)

    private fun ty(km: Float): Float =
        height - padBot - (km / max(yMax, 1f)) * (height - padBot - padTop)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
    private fun getColor(id: Int): Int = context.getColor(id)
}
