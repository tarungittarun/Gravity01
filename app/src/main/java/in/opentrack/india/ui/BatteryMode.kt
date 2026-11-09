package `in`.opentrack.india.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import `in`.opentrack.india.data.Prefs
import `in`.opentrack.india.R

/** Opt-in OLED presentation. Never changes a source, report, clock or normal-mode resource.
 * Paint after binding (which can reset backgrounds and semantic text colours), and paint newly
 * attached list children as well. No global animator scale, system theme or process-wide palette. */
object BatteryMode {
    val BLACK = Color.BLACK
    val INK = Color.WHITE
    const val OUTLINE = 0xFF858585.toInt()

    fun enabled(view: View): Boolean = Prefs(view.context).batteryMode

    fun paint(root: View, window: Window? = null) {
        if (!enabled(root)) return
        window?.let {
            it.statusBarColor = BLACK
            it.navigationBarColor = BLACK
            @Suppress("DEPRECATION")
            it.decorView.systemUiVisibility = it.decorView.systemUiVisibility and
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            it.setBackgroundDrawable(GradientDrawable().apply { setColor(BLACK) })
        }
        paintSubtree(root)
    }

    private class BlackSurface : GradientDrawable()

    private fun paintSubtree(view: View) {
        // Non-text custom canvases and indeterminate progress paint bright fills / run frames.
        // They carry no unique timetable data; the row's labelled clocks and summary remain.
        if (view is ImageView && view !is ImageButton) {
            view.visibility = View.GONE // decorative logos/arrows; Refresh has its own text label
            return
        }
        // The sole source-event train is a few static white pen strokes, not
        // the coloured route canvas. Untimed/static legs remain fully off.
        if (view is InlineLegView && !view.batteryOutline ||
            view is StopRailMarkerView && !view.batteryOutline ||
            view is StatusRippleView || view is JourneyProgressView ||
            view is TrackInsightView || view is LegPositionView ||
            view is ScreenLoadGlowView || view is ProgressBar) {
            view.visibility = View.GONE
            return
        }
        if ((view.background != null || view.parent == null) &&
            view.background !is BlackSurface) {
            val outlined = view.isClickable || view is TextView && view.background != null ||
                view is ViewGroup && view.background != null && view.parent != null &&
                view !is RecyclerView
            val d = view.resources.displayMetrics.density
            view.background = BlackSurface().apply {
                setColor(BLACK)
                if (outlined) {
                    cornerRadius = 5f * d
                    setStroke((1f * d).toInt().coerceAtLeast(1), OUTLINE)
                }
            }
        }
        if (view is TextView) {
            if (view.currentTextColor != INK) view.setTextColor(INK)
            if (view.currentHintTextColor != OUTLINE) view.setHintTextColor(OUTLINE)
            if (view.compoundDrawableTintList?.defaultColor != INK)
                view.compoundDrawableTintList = ColorStateList.valueOf(INK)
        }
        if (view is SwitchCompat) {
            view.thumbTintList = ColorStateList.valueOf(INK)
            view.trackTintList = ColorStateList.valueOf(OUTLINE)
        }
        if (view is ImageView) view.imageTintList = ColorStateList.valueOf(INK)
        if (view is Spinner && view.popupBackground !is BlackSurface)
            view.setPopupBackgroundDrawable(BlackSurface().apply { setColor(BLACK) })
        if (view is RecyclerView) {
            view.itemAnimator = null
            // Once per list; its descendants may be rebound after attachment, so adapters also paint.
            if (view.getTag(R.id.battery_painted) != true) {
                view.setTag(R.id.battery_painted, true)
                view.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(child: View) = paintSubtree(child)
                    override fun onChildViewDetachedFromWindow(child: View) = Unit
                })
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) paintSubtree(view.getChildAt(i))
    }
}
