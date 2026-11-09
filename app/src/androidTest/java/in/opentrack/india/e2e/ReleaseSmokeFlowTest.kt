package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The APK that would actually ship, driven once.
 *
 * Release builds differ from debug in two ways that have broken Android apps forever: R8 renames and
 * strips, and resource shrinking removes anything the linker did not see being used. Both are invisible to
 * every test in this repo, because every other test runs the debug variant — a JVM test cannot even build
 * against the shrunk resources. This class is run by CI against the signed release APK with
 * `-e release true`, and it is deliberately short: the point is "the shipping artifact can be started,
 * searched with, and can open a tracking screen", not a second full pass over the flows.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeFlowTest {

    // @flow release-smoke
    @Test
    fun theShippingApkDoesTheBasicFlow() {
        assumeTrue("this run is not against the release APK", E2E.flag("release"))
        E2E.launch(cold = true)
        E2E.awaitSearchScreen()
        // A string used only from Java (never from a layout) is the one resource shrinker class of mistake
        // this can catch: the label is simply missing at runtime.
        assertTrue("the ±24 h window is not stated on the shipping build", E2E.find("whenValue")?.contentDescription.orEmpty().contains("24 hours"))
        assertTrue("the search button has no label on the shipping build", E2E.text("find").isNotBlank())
        assertTrue(
            "type-ahead is dead in the release build (a shrinking or R8 difference, not a data one)",
            E2E.typeInBox("fromBox", "mas") &&
                E2E.ui.wait(Until.findObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) != null,
        )
        E2E.tapSuggestion("fromBox", 0)
        assertTrue(E2E.tap("modeTrain") && E2E.typeInBox("trainBox", "12627") &&
            E2E.ui.wait(Until.findObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) != null)
        assertTrue("the release train row could not be selected", E2E.tapSuggestion("trainBox", 0))
        assertTrue("Find missing for release train",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        assertTrue("no results and no message in the release build",
            E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS) ||
                E2E.text("statusLine").isNotBlank() || E2E.resultRows() > 0)
        if (E2E.resultRows() > 0) {
            assertTrue("Track does not open in the release build", E2E.openTrackFromFirstResult())
            assertTrue("the tracking screen has no headline in the release build", E2E.text("headline").isNotBlank())
            assertTrue("no halt rows in the release build", E2E.count(E2E.res("stopName")) > 0)
        }
        E2E.assertNoSystemDialog("release smoke")
        E2E.shot("release-smoke")
    }
}
