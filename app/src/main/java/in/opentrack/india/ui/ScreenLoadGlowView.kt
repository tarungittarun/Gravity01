package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import `in`.opentrack.india.R

/** A soft light wave across the entire local load-clock box, NOT train motion. */
class ScreenLoadGlowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : View(context, attrs, defStyle) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val live = context.getColor(R.color.live)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // The crest starts outside the left edge and travels off the right edge.
        // A full-height radial falloff gives a luminous wave, never an underline.
        val phase = (SystemClock.uptimeMillis() % 2100L) / 2100f
        val center = -w * .45f + phase * w * 1.9f
        val glow = Color.rgb(Color.red(live), Color.green(live), Color.blue(live))
        paint.shader = RadialGradient(center, h * .5f, w * .67f,
            intArrayOf(Color.argb(84, Color.red(glow), Color.green(glow), Color.blue(glow)),
                Color.argb(35, Color.red(glow), Color.green(glow), Color.blue(glow)),
                Color.TRANSPARENT),
            floatArrayOf(0f, .43f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(1f, 1f, w - 1f, h - 1f, h * .3f, h * .3f, paint)
        paint.shader = null
        if (isAttachedToWindow && isShown) postInvalidateDelayed(50L)
    }
}
