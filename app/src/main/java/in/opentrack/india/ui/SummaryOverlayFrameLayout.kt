package `in`.opentrack.india.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** The two-second refresh receipt paints OVER the existing summary. A visible
 * match-parent FrameLayout is otherwise included in FrameLayout's wrap-content
 * measurement when its text wraps beyond the (especially compact) card. That
 * silently grows the whole summary and steals space from the train table.
 * The first child alone determines height; the receipt gets exactly that box. */
class SummaryOverlayFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val card = getChildAt(0) ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        measureChildWithMargins(card, widthMeasureSpec, 0, heightMeasureSpec, 0)
        fun bounded(wanted: Int, spec: Int): Int = when (View.MeasureSpec.getMode(spec)) {
            View.MeasureSpec.EXACTLY -> View.MeasureSpec.getSize(spec)
            View.MeasureSpec.AT_MOST -> minOf(wanted, View.MeasureSpec.getSize(spec))
            else -> wanted
        }
        setMeasuredDimension(
            bounded(card.measuredWidth + paddingLeft + paddingRight, widthMeasureSpec),
            bounded(card.measuredHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
        val receipt = getChildAt(1)?.takeIf { it.visibility != View.GONE } ?: return
        receipt.measure(
            View.MeasureSpec.makeMeasureSpec((measuredWidth - paddingLeft - paddingRight)
                .coerceAtLeast(0), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((measuredHeight - paddingTop - paddingBottom)
                .coerceAtLeast(0), View.MeasureSpec.EXACTLY),
        )
    }
}
