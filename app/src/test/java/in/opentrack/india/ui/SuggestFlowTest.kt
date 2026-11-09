package `in`.opentrack.india.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import `in`.opentrack.india.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * Typing into the real search screen, in the real Activity — not a layout painted to look like it.
 *
 * The rest of the UI tests bind values into views and assert what the pixels contain. That is the right tool
 * for "does this screen look right", and it was the wrong tool for the bug this file exists for: the traveller
 * reported that no suggestions appear while typing a station name, and every render test stayed green, because
 * nothing had ever pressed a key. Here the flow is the unit under test: `MainActivity` is created, text is put
 * into the box as a person types it, the debounce and the coroutine are idled, and the panel is read back.
 *
 * The property asserted is deliberately weaker than "the right station came first" and stronger than "a list
 * was built": **the box never answers with silence**. Either matches appear, or a line appears saying why they
 * cannot (timetable still installing, nothing matches, the lookup threw) — because the actual bug was
 * `?: return@launch`, a refusal to answer that looked like a broken app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SuggestFlowTest {

    private fun box(activity: MainActivity, id: Int): SuggestBox = activity.findViewById(id)

    private fun panel(box: SuggestBox): ViewGroup? = box.findViewById(R.id.suggestPanel)

    private fun rowText(box: SuggestBox, index: Int): String {
        val row = panel(box)?.getChildAt(index) ?: return "<no row $index>"
        return (row.findViewById<TextView>(R.id.sugPrimary))?.text?.toString().orEmpty()
    }

    /**
     * Types `text`, then waits — in real time, bounded — for the box to answer.
     *
     * The first version of this helper drained Robolectric's *virtual* clock (`idleFor` advances shadow
     * time and returns immediately), which fires the 200 ms debounce but gives the query itself no wall
     * clock at all: the lookup sits behind a readiness job on `Dispatchers.IO` that, on a cold process,
     * unpacks a 14 MB SQLite file before it can answer. So the assertions ran while the job was still
     * suspended and the box had every right to be empty — a test of scheduling, mistakenly read as a
     * test of behaviour. Idling the looper inside a real sleep loop is what waits for both clocks.
     */
    private fun type(box: SuggestBox, text: String) {
        (box.inputView as TextView).setText(text)
        // The focus listener also schedules a query; setting text alone is what a hardware keyboard does, and
        // both paths must land, so no requestFocus() is faked here.
        val looper = shadowOf(android.os.Looper.getMainLooper())
        repeat(300) {
            looper.idleFor(400, TimeUnit.MILLISECONDS)
            if (box.rowCount > 0) return
            Thread.sleep(40)
        }
    }

    @Test
    fun firstFrameKeepsInstallProgressUntilInstalledChipHasText() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val status = activity.findViewById<TextView>(R.id.statusLine)
        val chip = activity.findViewById<TextView>(R.id.dataChip)
        // The initial null live-note observer used to overwrite the layout's opening text,
        // while the asynchronous database extraction had not produced a Ready chip yet.
        assertTrue("cold screen has neither progress nor installed data: " +
            "status='${status.text}' (${status.visibility}), chip='${chip.text}' (${chip.visibility})",
            (status.visibility == View.VISIBLE && status.text.isNotBlank()) ||
                (chip.visibility == View.VISIBLE && chip.text.isNotBlank()))
    }

    @Test
    fun typingThreeLettersIntoTheBoardBoxNeverAnswersWithSilence() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val from = box(activity, R.id.fromBox)
        assertNotNull("no board-at box on the search screen", from)

        type(from!!, "yes")
        val scroll = from.findViewById<View>(R.id.suggestScroll)
        assertTrue(
            "typing \"yes\" left no visible answer — rows=${from.rowCount}, " +
                "panel=${panel(from)?.visibility}, scroll=${scroll?.visibility}",
            from.rowCount > 0 && scroll?.visibility == View.VISIBLE &&
                panel(from)?.visibility == View.VISIBLE,
        )
        // Whatever came back has to be about what was typed, not a stale or empty row.
        val first = rowText(from, 0)
        assertTrue("the top row reads '$first', which does not look like a station or a reason",
            first.isNotBlank())
    }

    @Test
    fun aMatchingStationCanBePickedAndResolvesToItsCode() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val from = box(activity, R.id.fromBox)
        type(from!!, "yesvant")
        val rows = panel(from)
        val clickable = (0 until (rows?.childCount ?: 0))
            .filter { rows!!.getChildAt(it).isClickable }
        assertTrue("no selectable suggestion among ${rows?.childCount} rows: " +
            (0 until (rows?.childCount ?: 0)).joinToString { "'${rowText(from, it)}'" }, clickable.isNotEmpty())
        rows!!.getChildAt(clickable.first()).performClick()
        val code = from.resolvedCode
        assertNotNull("picking a row left no resolved code, so the search would re-fuzzy the name", code)
        assertEquals("a station code is three or four letters, got '$code'", true,
            code!!.length in 3..4 && code.all { it.isLetterOrDigit() })
        assertEquals("the field should show the name, not the code", "yesvant",
            from.text.take(7).lowercase())
        shadowOf(android.os.Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals("a picked suggestion must stay closed", View.GONE,
            from.findViewById<View>(R.id.suggestScroll).visibility)
    }

    @Test
    fun tappingTheSuggestionLabelActuallySelectsAndClosesThePanel() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val from = box(activity, R.id.fromBox)
        type(from, "yesvant")
        val label = panel(from)!!.getChildAt(0).findViewById<TextView>(R.id.sugPrimary)
        assertTrue("the accessibility-labelled suggestion is not clickable", label.isClickable)
        assertTrue("tapping its label did not activate the suggestion", label.performClick())
        assertNotNull("label tap left the station unresolved", from.resolvedCode)
        assertEquals("label tap left the suggestion overlay visible", View.GONE,
            from.findViewById<View>(R.id.suggestScroll).visibility)
    }

    @Test
    fun aQueryThatMatchesNothingSaysSoInTheBox() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val from = box(activity, R.id.fromBox)
        type(from!!, "qqzzxxy")
        assertEquals("an unmatched query should show one explanatory row, got ${from.rowCount}", 1, from.rowCount)
        assertTrue("the row should say it found nothing, but reads '${rowText(from, 0)}'",
            rowText(from, 0).contains("matches", ignoreCase = true) ||
                rowText(from, 0).contains("installing", ignoreCase = true))
    }

    @Test
    fun aCompletedNoMatchSearchKeepsItsFeedbackVisibleAfterReady() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.findViewById<Button>(R.id.modeTrain).performClick()
        val train = box(activity, R.id.trainBox)
        type(train, "99999")
        activity.findViewById<Button>(R.id.find).performClick()
        val status = activity.findViewById<TextView>(R.id.statusLine)
        val looper = shadowOf(android.os.Looper.getMainLooper())
        repeat(150) {
            looper.idleFor(100, TimeUnit.MILLISECONDS)
            if (status.text.toString().contains("no direct train", ignoreCase = true) ||
                status.text.toString().contains("matches", ignoreCase = true)) {
                // Let the subsequent Phase.Ready observer run; it used to hide this answer.
                looper.idleFor(100, TimeUnit.MILLISECONDS)
                assertEquals("the completed search hid its explanation", View.VISIBLE, status.visibility)
                return
            }
            Thread.sleep(25)
        }
        throw AssertionError("no completed answer for 99999: '${status.text}'")
    }

    @Test
    fun theActivityUnderTestSharesTheAppThatInstallsTheTimetable() {
        // Guards this file rather than the app: the type-ahead reads the database off the Application, so if
        // the built activity ever got its own process object these tests would be typing into an app whose
        // timetable lived somewhere else, and "no rows" would stop meaning anything.
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertSame(RuntimeEnvironment.getApplication(), activity.application)
    }
}
