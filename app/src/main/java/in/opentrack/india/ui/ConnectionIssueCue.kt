package `in`.opentrack.india.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.widget.TextView
import `in`.opentrack.india.R

/** A short transport-failure cue on the TOP summary, not on the train icon.
 * Six low-contrast dips at three per second, then a persistent, readable reason.
 * The last report's badge is never replaced by the failure warning. */
class ConnectionIssueCue(private val card: View, private val alert: TextView,
                         private val batteryMode: Boolean = false) {
    companion object {
        const val PULSE_MILLIS = 333L
        const val PULSES = 6
        const val MIN_ALPHA = .72f
    }

    private var animation: AnimatorSet? = null
    var reason: String? = null
        private set

    fun show(newReason: String, source: String, showing: String? = null) {
        val fallback = showing?.takeIf { it != source }
        val key = "$source:$newReason:${fallback.orEmpty()}"
        if (reason == key) return
        stopMotion()
        reason = key
        alert.text = if (fallback == null)
            "$newReason · $source. Previous report unchanged; retry or change source."
        else "$newReason · $source. Showing $fallback instead."
        alert.visibility = View.VISIBLE
        if (batteryMode) {
            BatteryMode.paint(card)
            BatteryMode.paint(alert)
            return
        }
        card.setBackgroundResource(R.drawable.bg_summary_connection)
        val pulse = ObjectAnimator.ofFloat(card, View.ALPHA, 1f, MIN_ALPHA, 1f).apply {
            duration = PULSE_MILLIS
            repeatCount = PULSES - 1
        }
        val drift = 5f * card.resources.displayMetrics.density
        val shake = ObjectAnimator.ofFloat(card, View.TRANSLATION_X,
            0f, -drift, drift, -drift / 2f, drift / 2f, 0f).apply {
            duration = 600L
        }
        animation = AnimatorSet().apply { playTogether(pulse, shake); start() }
    }

    /** Stop an active cue on navigation; keep the explanatory message. */
    fun pause() = stopMotion()

    fun clear() {
        stopMotion()
        reason = null
        alert.text = ""
        // GONE leaves no empty warning row above the ordinary report.
        alert.visibility = View.GONE
        if (!batteryMode) card.setBackgroundResource(R.drawable.bg_summary_card)
    }

    private fun stopMotion() {
        animation?.cancel()
        animation = null
        card.alpha = 1f
        card.translationX = 0f
    }
}
