package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first minute of a fresh install — which is where this app was actually broken for a real user: the
 * type-ahead answered with nothing while the 14 MB offline timetable was still being unpacked, and there
 * was no way to tell "no such station" from "not ready yet".
 *
 * The CI job runs `adb shell pm clear` before this class so the extraction genuinely happens; without that
 * a "cold start" test is a warm start test with a longer name.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartFlowTest {

    // @flow cold-start
    @Test
    fun coldStartInstallsThenAnswers() {
        E2E.launch(cold = true)
        val status = E2E.text("statusLine")
        val chip = E2E.text("dataChip")
        E2E.shot("cold-start-first-frame")
        // Either the install is announced or the data is already there. What is not acceptable is a screen
        // that says nothing while work happens in the background — that was the report from the phone.
        assertTrue(
            "cold start showed neither progress nor a data state (status='$status', chip='$chip')",
            status.isNotBlank() || chip.isNotBlank(),
        )
        E2E.awaitSearchScreen()
        assertNotNull("no board-at box once the install finished", E2E.find("fromBox"))
        E2E.assertNoSystemDialog("cold start")
    }

    // @flow data-chip
    @Test
    fun dataChipStatesWhatIsInstalled() {
        E2E.launch()
        E2E.awaitSearchScreen()
        val chip = E2E.text("dataChip")
        assertTrue("the data chip is blank — 'no data' and 'nothing to say' look identical", chip.isNotBlank())
        E2E.shot("data-chip")
        E2E.assertNoSystemDialog("data chip")
    }
}
