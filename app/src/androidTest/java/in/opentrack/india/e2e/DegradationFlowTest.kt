package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the app does when the world is missing — the half of "offline-first" that only a device can show.
 *
 * The harness, not the test, changes the world: CI flips the radio (`adb shell svc wifi disable`,
 * `svc data disable`) and the permission (`pm revoke`/`pm grant`) and then runs this class with
 * `-e offline true` / `-e location denied`. A test that tried to toggle either would need a permission the
 * test process does not have, and a test that *assumed* the state would pass on a runner that never set
 * it — which is the kind of green that hides a bug. So each case begins by checking it was really asked to
 * run in that state, and skips loudly-by-name otherwise.
 */
@RunWith(AndroidJUnit4::class)
class DegradationFlowTest {

    private fun requireOffline() = assumeTrue("-e offline true was not passed", E2E.flag("offline"))

    // @flow airplane-search
    @Test
    fun searchWorksWithNoNetwork() {
        requireOffline()
        E2E.launch()
        E2E.awaitSearchScreen()
        val offered = E2E.typeInBox("fromBox", "mas") &&
            E2E.ui.wait(Until.findObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) != null
        assertTrue("no suggestion at three letters offline: ${E2E.debugSearch("fromBox")}", offered)
        E2E.tapSuggestion("fromBox", 0)
        // An offline station result can finish just as this test switches modes and
        // compact the form. Edit search explicitly pins it open for the train query.
        if (E2E.visible("editSearch", 2_000)) E2E.tap("editSearch")
        val switched = E2E.tap("modeTrain")
        if (E2E.visible("editSearch", 500)) E2E.tap("editSearch")
        val typed = switched && E2E.typeInBox("trainBox", "12627")
        val trainOffered = typed &&
            E2E.ui.wait(Until.findObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) != null
        assertTrue("train search offline: switched=$switched typed=$typed ${E2E.debugSearch("trainBox")}",
            trainOffered)
        assertTrue("the train row could not be selected", E2E.tapSuggestion("trainBox", 0))
        assertTrue("Find missing after selecting train",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        val answered = E2E.ui.wait(Until.hasObject(E2E.res("hitNo")), E2E.LOCAL_MS) ||
            E2E.text("statusLine").isNotBlank() || E2E.resultRows() > 0
        assertTrue("offline search produced neither a list nor a sentence", answered)
        E2E.shot("offline-search")
    }

    // @flow airplane-track
    @Test
    fun trackScreenAdmitsNoLiveReport() {
        requireOffline()
        E2E.launch()
        E2E.awaitSearchScreen()
        val found = E2E.tap("modeTrain") && E2E.typeInBox("trainBox", "12627") &&
            E2E.ui.wait(Until.findObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) != null
        assertTrue("offline train search gave no answer: ${E2E.debugSearch("trainBox")}", found)
        assertTrue("the train row could not be selected", E2E.tapSuggestion("trainBox", 0))
        assertTrue("Find missing after selecting train",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        assertTrue("no result offline for a train in the timetable",
            E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.LOCAL_MS))
        E2E.openTrackFromFirstResult()
        val provenance = E2E.text("provenance")
        val age = E2E.text("age")
        val note = E2E.text("note")
        assertTrue("no provenance label while offline", provenance.isNotBlank())
        assertTrue("no age label while offline", age.isNotBlank())
        // The claim that must be impossible: "live" with no network. A cached snapshot is allowed and is
        // labelled as cached; "live" is not a word this screen may use with the radio off.
        assertTrue(
            "offline screen says provenance='$provenance', age='$age', note='$note'",
            !age.equals("live", ignoreCase = true),
        )
        if (!provenance.contains("CACHED", ignoreCase = true)) {
            assertTrue(
                "no live data offline and no explanation on screen (note='$note')",
                note.isNotBlank() || listOf("OFFLINE", "SCHEDULE", "DERIVED", "NO DATA")
                    .any { provenance.contains(it, ignoreCase = true) },
            )
        }
        E2E.shot("offline-track")
    }

    // @flow location-denied
    @Test
    fun noLocationPermissionIsNotAnError() {
        assumeTrue("-e location denied was not passed", E2E.arg("location") == "denied")
        E2E.launch()
        E2E.awaitSearchScreen()
        assertTrue(E2E.tap("modeTrain") && E2E.typeInBox("trainBox", "12627") &&
            E2E.ui.wait(Until.findObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) != null)
        assertTrue("the train row could not be selected", E2E.tapSuggestion("trainBox", 0))
        assertTrue("Find missing after selecting train",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        assumeTrue("no result to open", E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
        E2E.openTrackFromFirstResult()
        E2E.assertNoSystemDialog("location denied")
        assertTrue("the screen is blank without location permission", E2E.text("headline").isNotBlank())
        assertTrue(
            "position claimed to be GPS-derived without permission: '${E2E.text("provenance")}'",
            !E2E.text("provenance").contains("GPS", ignoreCase = true),
        )
    }

    // @flow location-granted
    @Test
    fun grantedLocationNeverFabricatesPosition() {
        assumeTrue("-e location granted was not passed", E2E.arg("location") == "granted")
        E2E.launch()
        E2E.awaitSearchScreen()
        assertTrue(E2E.tap("modeTrain") && E2E.typeInBox("trainBox", "12627") &&
            E2E.ui.wait(Until.findObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) != null)
        assertTrue("the train row could not be selected", E2E.tapSuggestion("trainBox", 0))
        assertTrue("Find missing after selecting train",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        assumeTrue("no result to open", E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
        E2E.openTrackFromFirstResult()
        val provenance = E2E.text("provenance")
        val allowed = listOf("LIVE", "CACHED", "DERIVED", "OFFLINE", "NO DATA", "SNAPSHOT")
        assertTrue(
            "the provenance chip says something that is not a state this app can be in: '$provenance'",
            allowed.any { provenance.contains(it, ignoreCase = true) },
        )
        E2E.shot("location-granted")
    }
}
