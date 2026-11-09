package `in`.opentrack.india.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/** Wrap the edited search form without consuming the results viewport on tall phones.
 * A short/large-font phone can still scroll the form to reach Find. Reserve a
 * real result-row viewport, not only the summary and sort controls. */
class BoundedSearchScroll @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : ScrollView(context, attrs, defStyle) {
    var capWhenEditingResults = false
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!capWhenEditingResults) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val screenCap = (resources.displayMetrics.heightPixels * 0.48f).toInt()
        val available = View.MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (available > 0) minOf(screenCap, available) else screenCap
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
    }
}
