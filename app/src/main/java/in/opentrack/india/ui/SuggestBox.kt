package `in`.opentrack.india.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.Suggestion

/**
 * A text input with inline matches, used for both station and train input.
 *
 * The list is pushed into the layout instead of floating over it. That is a decision, not a shortcut:
 * an anchored popup over a keyboard is the single most reliable way to make a search input unusable with
 * one hand on a moving train, and a panel that grows inside the card is readable at any keyboard state.
 *
 * Behaviour the traveller asked for, with the reasons:
 *  - nothing appears below [MIN_QUERY] characters. "B" matching 400 stations is noise; three characters
 *    is the point where an Indian station name or code is specific enough to be worth showing.
 *  - typing pauses for [DEBOUNCE_MS] before a query runs, so five keystrokes cost one scan of 8 990
 *    rows rather than five.
 *  - tapping a row replaces the input with the *name* and keeps the 3-letter code as the resolved value,
 *    so a search never re-parses what the user picked and cannot pick a different station by accident.
 */
class SuggestBox @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0,
) : LinearLayout(context, attrs, defStyle) {

    interface Suggester {
        /**
         * Implementations must call [then] on the main thread. [miss] is called with a sentence when there
         * is no list to show *and the user should know why*; an implementation that cannot tell leaves it
         * alone and the box stays empty.
         */
        fun suggest(query: String, then: (List<Suggestion>) -> Unit, miss: (String) -> Unit = {})
    }

    private val input: EditText
    private val panel: LinearLayout
    private val scroll: View
    private val chip: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var dismiss: Runnable? = null

    var suggester: Suggester? = null
    var onPick: ((Suggestion) -> Unit)? = null
    var onSearch: (() -> Unit)? = null
    /** A human edit invalidates the displayed result, not the resolved suggestion list. */
    var onEdit: (() -> Unit)? = null

    /**
     * The resolved code (station) or number (train) once a suggestion was chosen; null while the input
     * holds free text. `suppress` exists because choosing a row writes into the EditText, and the text
     * watcher would otherwise treat our own write as an edit and clear the value we just stored.
     */
    var resolvedCode: String? = null
        set(value) {
            field = value
            chip.visibility = if (value == null) GONE else VISIBLE
            chip.text = value?.let { "${context.getString(R.string.selected_station)}  $it" }
        }

    private var suppress = false

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_suggest_box, this, true)
        input = findViewById(R.id.suggestInput)
        panel = findViewById(R.id.suggestPanel)
        scroll = findViewById(R.id.suggestScroll)
        chip = findViewById(R.id.suggestChip)
        chip.visibility = GONE

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!suppress) {
                    resolvedCode = null
                    onEdit?.invoke()
                    schedule()
                }
            }
        })
        input.setOnFocusChangeListener { _, focused ->
            if (focused) {
                dismiss?.let(handler::removeCallbacks)
                dismiss = null
                schedule()
            } else hideSoon()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                hideNow(); onSearch?.invoke(); true
            } else false
        }
    }

    var hint: CharSequence?
        get() = input.hint
        set(value) { input.hint = value }

    var text: String
        get() = input.text?.toString().orEmpty()
        set(value) = setTextShown(value)

    /** Write the visible text without invalidating a resolved code (used by tests and by "swap"). */
    fun setTextShown(value: String) {
        suppress = true
        input.setText(value)
        input.setSelection(value.length)
        suppress = false
    }

    /** Used by tests and by the "swap" button, which must move the resolved value too. */
    fun valueOrText(): String = resolvedCode ?: text.trim().uppercase()

    fun focusField() {
        input.requestFocus()
        imm()?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    fun clear() {
        resolvedCode = null
        setTextShown("")
        hideNow()
    }

    private fun imm(): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun schedule() {
        pending?.let { handler.removeCallbacks(it) }
        // Once the input changes, the previous query's suggestions are wrong. Leaving those
        // rows on screen during debounce lets a traveller pick a station/train for old text.
        // It also made the device flow mistake seven previous matches for the new miss.
        panel.removeAllViews()
        showPanel(false)
        val r = Runnable { query() }
        pending = r
        handler.postDelayed(r, DEBOUNCE_MS)
    }

    private fun query() {
        val s = suggester
        val q = text.trim()
        if (s == null || q.length < MIN_QUERY) {
            panel.removeAllViews(); showPanel(false)
            return
        }
        s.suggest(q, { rows ->
            // A late answer for an older keystroke must not overwrite a newer list. The input text is
            // the token; comparing it here is enough and avoids a request-sequence counter.
            if (text.trim() != q) return@suggest
            render(rows)
        }, { why ->
            // The reason instead of an empty box. A traveller who types "Yes" and sees nothing has no way to
            // tell "no such station" from "the 14 MB timetable is still installing" from "the lookup threw",
            // and each of those wants a different thing to do next.
            if (text.trim() != q) return@suggest
            notice(why)
        })
    }

    /** A single grey line in the dropdown's place. Not selectable, because there is nothing to select. */
    fun notice(line: String) {
        panel.removeAllViews()
        val v = LayoutInflater.from(context).inflate(R.layout.item_suggestion, panel, false)
        (v.findViewById<TextView>(R.id.sugCode)).text = "···"
        (v.findViewById<TextView>(R.id.sugPrimary)).text = line
        (v.findViewById<TextView>(R.id.sugSecondary)).text = ""
        v.isClickable = false
        panel.addView(v)
        showPanel(true)
        bringPanelIntoView()
    }

    private fun render(rows: List<Suggestion>) {
        panel.removeAllViews()
        if (rows.isEmpty()) {
            showPanel(false)
            return
        }
        rows.take(MAX_ROWS).forEach { sug ->
            val v = LayoutInflater.from(context)
                .inflate(R.layout.item_suggestion, panel, false)
            (v.findViewById<TextView>(R.id.sugCode)).text = sug.badge
            (v.findViewById<TextView>(R.id.sugPrimary)).text = sug.primary
            (v.findViewById<TextView>(R.id.sugSecondary)).text = sug.secondary
            // A screen reader/UiAutomator can target the labelled child directly,
            // rather than sending a touch to the row. Make each visible part of
            // the same suggestion choose that SAME value; never silently leave
            // the panel open while reporting that a train was selected.
            val choose = View.OnClickListener { pick(sug) }
            v.setOnClickListener(choose)
            v.findViewById<TextView>(R.id.sugPrimary).setOnClickListener(choose)
            v.findViewById<TextView>(R.id.sugCode).setOnClickListener(choose)
            v.findViewById<TextView>(R.id.sugSecondary).setOnClickListener(choose)
            panel.addView(v)
        }
        showPanel(true)
        bringPanelIntoView()
    }

    /** The rows live inside suggestScroll. Making only the inner LinearLayout visible leaves every
     * result behind the outer ScrollView's XML `gone` state: populated in JVM tests, invisible on device. */
    private fun showPanel(show: Boolean) {
        scroll.visibility = if (show) VISIBLE else GONE
    }

    /**
     * `adjustResize` + a soft keyboard means a panel that grows *below* the field can end up under the
     * keyboard, i.e. present and invisible — which reads as "suggestions do not work on my phone". So the
     * box asks its scroll parent to bring the panel up after it has a height, which is why this is posted.
     */
    private fun bringPanelIntoView() {
        panel.post {
            panel.requestRectangleOnScreen(android.graphics.Rect(0, 0, panel.width, panel.height), true)
        }
    }

    private fun pick(sug: Suggestion) {
        pending?.let(handler::removeCallbacks)
        pending = null
        setTextShown(sug.primary)
        resolvedCode = sug.value
        panel.removeAllViews()
        showPanel(false)
        onPick?.invoke(sug)
        imm()?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun hideSoon() {
        pending?.let(handler::removeCallbacks)
        dismiss?.let(handler::removeCallbacks)
        val r = Runnable { hideNow() }
        dismiss = r
        handler.postDelayed(r, 240)
    }

    private fun hideNow() {
        dismiss?.let(handler::removeCallbacks)
        dismiss = null
        showPanel(false)
    }

    /** Exposed for the render tests: how many rows are showing. */
    val rowCount: Int get() = panel.childCount
    val inputView: View get() = input

    companion object {
        const val MIN_QUERY = 3
        const val MAX_ROWS = 7
        private const val DEBOUNCE_MS = 200L
    }
}
