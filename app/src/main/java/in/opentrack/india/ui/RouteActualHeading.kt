package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** Fixed right-hand table heading. Keep a real TextView for selection and TalkBack,
 * but paint its short label explicitly: right-gravity TextView layout supplied a
 * nonzero rectangle and accessible text while drawing no ACTUAL pixels in the
 * rendered-phone snapshots. The neighbouring headers remain framework text. */
class RouteActualHeading @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setWillNotDraw(false) }

    override fun onDraw(canvas: Canvas) {
        // The framework path is essential on hardware-accelerated phones; the
        // explicit draw also protects the rendered-pixel regression on Robolectric.
        super.onDraw(canvas)
        val label = text.toString()
        if (label.isEmpty()) return
        headingPaint.color = currentTextColor
        headingPaint.textSize = textSize
        headingPaint.typeface = typeface
        val fm = headingPaint.fontMetrics
        val x = width - paddingRight - headingPaint.measureText(label)
        val baseline = (height - fm.ascent - fm.descent) / 2f
        canvas.drawText(label, x, baseline, headingPaint)
    }
}
