package `in`.opentrack.india.ui

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import `in`.opentrack.india.R

/** Explicitly chosen Android share target. No background interception, package impersonation,
 * network request, persistence, clipboard write, diary line or promotion into the train table. */
class SharedTrainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showShare(intent)
    }

    private fun showShare(incoming: Intent?) {
        val text = runCatching { incoming?.getCharSequenceExtra(Intent.EXTRA_TEXT) }.getOrNull()
        val preview = SharedTrainIntake.preview(incoming?.action, incoming?.type, text)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val gap = (14 * dp).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(getColor(R.color.bg))
        }
        fun label(message: String, style: Int): TextView = TextView(this).apply {
            setTextAppearance(style)
            this.text = message
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = gap
            }
            column.addView(this)
        }
        label("Shared train status · local preview", R.style.TextHeadline)
        label("You chose to share this text with TrackTrain. Android shares do not prove which app " +
            "sent them. This is not a live feed, a verified WIMT result or a confirmed train event.",
            R.style.TextBody)
        if (preview == null) {
            label("No supported text received. Use Share current train location in the " +
                "Where is my Train app, then choose TrackTrain. Only short plain-text shares are accepted.",
                R.style.TextBody)
        } else {
            label(if (preview.linkOnly)
                "This share contains only a link, not station times. No link was opened or fetched."
            else "The share format is not yet verified. No clock or position has been placed in the table.",
                R.style.TextBody)
            label("Shared text (kept on this screen only):", R.style.TextTitle)
            label(preview.text, R.style.TextBody).setTextIsSelectable(true)
            label("If you want this mapped, share a sample with us after removing personal " +
                "details. We need its train number, operating day and station-event meaning " +
                "before adding a source-labelled, user-shared comparison.", R.style.TextMeta)
        }
        column.addView(Button(this).apply {
            this.text = "Close"
            setOnClickListener { finish() }
        })
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
        BatteryMode.paint(scroll, window)
    }
}
