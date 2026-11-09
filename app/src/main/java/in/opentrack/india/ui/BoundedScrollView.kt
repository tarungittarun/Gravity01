package `in`.opentrack.india.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.MotionEvent
import android.widget.ScrollView

/** The top source card must remain scrollable without stealing the route viewport.
 * A large font makes three halt rows taller, so a fixed percentage of the screen
 * cannot promise that those rows stay visible. This budget reserves their space. */
internal object RouteViewportBudget {
    fun sourceCapPx(availablePx: Int, screenPx: Int, density: Float, fontScale: Float): Int {
        val available = availablePx.takeIf { it > 0 } ?: screenPx
        // Three 80dp main halts grow with text; reserve their enlarged body plus
        // the route heading/archives AND the fixed 56dp source/Auto row below the
        // report viewport. The summary scrolls when a source has a long note.
        val reserve = ((360f * fontScale.coerceIn(1f, 1.4f) + 56f) * density).toInt()
        val minimumSummary = kotlin.math.ceil(210.0 * density).toInt()
        return minOf(available, (screenPx * .53f).toInt(),
            (available - reserve).coerceAtLeast(minimumSummary)).coerceAtLeast(0)
    }
}

/** Keep every measured report line when room exists, while protecting the station
 * viewport on a small screen. Only the details scroll when the full report cannot
 * fit; the footer stays below them, never on top of a clipped line. */
internal object SummaryCardSizing {
    data class Fit(val cardPx: Int, val detailsPx: Int, val overflows: Boolean)

    /** Never trade away even the badge/headline to preserve a footer in an
     * impossible viewport. The outer card can scroll to the footer instead. */
    fun readableCap(baseCapPx: Int, chromePx: Int, firstGlancePx: Int): Int =
        maxOf(baseCapPx, chromePx.coerceAtLeast(0) + firstGlancePx.coerceAtLeast(0))

    fun fit(chromePx: Int, contentPx: Int, capPx: Int): Fit {
        val chrome = chromePx.coerceAtLeast(0)
        val content = contentPx.coerceAtLeast(0)
        val room = (capPx - chrome).coerceAtLeast(0)
        val details = minOf(content, room)
        return Fit(chrome + details, details, content > details)
    }
}

class BoundedScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {
    // A source response may change the card's measured height and cause Android
    // to auto-pan to a focused child. Until the traveller actually touches this
    // viewport, the headline and provenance must remain in the first glance.
    var touchedByUser: Boolean = false
        private set

    fun focusSourceResult() {
        touchedByUser = false
        post { scrollTo(0, 0) }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touchedByUser = true
        return super.dispatchTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = RouteViewportBudget.sourceCapPx(
            View.MeasureSpec.getSize(heightMeasureSpec), resources.displayMetrics.heightPixels,
            resources.displayMetrics.density, resources.configuration.fontScale,
        )
        super.onMeasure(widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(cap, View.MeasureSpec.AT_MOST))
    }
}
