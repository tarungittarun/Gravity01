package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import `in`.opentrack.india.R

/** Arrival ↘, departure ↙. Only a reported time gets a travelling light;
 * the animation is a reading aid, NEVER an indication that the train is moving. */
class DirectionArrowDrawable(private val context: Context,
                             val arriving: Boolean, val reported: Boolean) : Drawable(), Animatable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var opacity = 255
    private var running = false
    private val frame = Runnable { if (running) { invalidateSelf(); scheduleNext() } }
    private val density get() = context.resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    internal var phaseForTest: Float? = null
    private fun phase() = phaseForTest ?: ((SystemClock.uptimeMillis() % 1_500L) / 1_500f)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        // Both arrows descend. Departure points to the LOWER LEFT, not the upper left.
        val x0 = b.left + dp(if (arriving) 3f else 18f)
        val y0 = b.top + dp(3f)
        val x1 = b.left + dp(if (arriving) 18f else 3f)
        val y1 = b.top + dp(17f)
        val tip = if (reported) context.getColor(R.color.live) else Color.rgb(111, 185, 226)
        val tail = if (reported) Color.argb(opacity * 120 / 255, 52, 127, 177)
                   else Color.argb(opacity * 100 / 255, 89, 129, 158)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.8f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.shader = LinearGradient(x0, y0, x1, y1, tail, tip, Shader.TileMode.CLAMP)
        canvas.drawLine(x0, y0, x1, y1, paint)
        paint.shader = null
        paint.color = tip
        paint.alpha = opacity
        // Tip wings: arrival ↘, departure ↙. Both arrows have their head BELOW.
        val sign = if (arriving) 1f else -1f
        canvas.drawLine(x1 - sign * dp(7f), y1, x1, y1, paint)
        canvas.drawLine(x1, y1 - dp(7f), x1, y1, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x1, y1, dp(1.6f), paint)
        if (reported) {
            // A small highlight travels along the shaft tail → head, then resets.
            // Never animate the train marker: its last confirmed position stays pinned.
            val travel = phase().coerceIn(0f, 1f)
            val cx = x0 + (x1 - x0) * travel
            val cy = y0 + (y1 - y0) * travel
            paint.color = tip
            paint.alpha = opacity * 35 / 255
            canvas.drawCircle(cx, cy, dp(5.2f), paint)
            paint.alpha = opacity * 200 / 255
            canvas.drawCircle(cx, cy, dp(2.1f), paint)
        }
        paint.alpha = opacity
    }

    private fun scheduleNext() { scheduleSelf(frame, SystemClock.uptimeMillis() + 75L) }
    override fun start() { if (reported && !running) { running = true; invalidateSelf(); scheduleNext() } }
    override fun stop() { running = false; unscheduleSelf(frame) }
    override fun isRunning(): Boolean = running
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        fun attach(text: TextView, arriving: Boolean, reported: Boolean) {
            val old = text.compoundDrawables[0] as? DirectionArrowDrawable
            if (old?.arriving == arriving && old.reported == reported) return
            old?.stop()
            val d = text.resources.displayMetrics.density
            val arrow = DirectionArrowDrawable(text.context, arriving, reported)
            arrow.setBounds(0, 0, (22 * d).toInt(), (20 * d).toInt())
            text.setCompoundDrawables(arrow, null, null, null)
            text.compoundDrawablePadding = (3 * d).toInt()
            // RecyclerView recycles TextViews: release the ticker when a row leaves the window.
            if (old == null) text.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    (text.compoundDrawables[0] as? DirectionArrowDrawable)?.start()
                }
                override fun onViewDetachedFromWindow(v: View) {
                    (text.compoundDrawables[0] as? DirectionArrowDrawable)?.stop()
                }
            })
            if (text.isAttachedToWindow) arrow.start()
        }
    }
}
