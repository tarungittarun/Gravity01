package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Acceptance tests against the RELEASE-DOWNLOAD BYTES, not a separately assembled app APK.
 * Drive only visible controls; a spinner, an arbitrary nonempty status or an empty result is NOT success.
 * We intentionally keep these independent from the older, more permissive flow-counting suite. */
@RunWith(AndroidJUnit4::class)
class TravellerAcceptanceTest {
    @Before fun start() {
        E2E.launch()
        E2E.awaitSearchScreen()
    }

    @After fun evidence() {
        E2E.shot("traveller-acceptance-final")
        E2E.assertNoSystemDialog("traveller acceptance")
    }

    private fun openTicketTrain() {
        assertTrue("Train mode was not reachable", E2E.tap("modeTrain"))
        assertTrue("Cannot enter the train number", E2E.typeInBox("trainBox", "12627"))
        assertTrue("No suggestion for a known offline train: ${E2E.debugSearch("trainBox")}",
            E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
        assertTrue("Could not select the train", E2E.tapSuggestion("trainBox"))
        assertTrue("Suggestion published train results without Find", E2E.resultRows() == 0)
        assertTrue("Find is not reachable after selecting a train",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        assertTrue("Known train did not produce a tappable result; ${E2E.debugSearch("trainBox")}",
            E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
        assertTrue("Result is not the train requested: ${E2E.all(E2E.res("hitNo")).map { it.text }}",
            E2E.all(E2E.res("hitNo")).any { it.text.orEmpty().contains("12627") })
        assertTrue("Track on the result did not open a route", E2E.openTrackFromFirstResult())
        assertEquals("Track opened a DIFFERENT train from the ticket", "12627", E2E.text("trainNo"))
    }

    /** On a missing badge, distinguish an absent source label from a clipped
     * details viewport. Keep the traveller assertion; never accept a hidden
     * badge just because the model populated it internally. */
    private fun reportGeometry(): String {
        var result = "not laid out"
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance().getActivitiesInStage(
                    androidx.test.runner.lifecycle.Stage.RESUMED).first()
            val badge = activity.findViewById<android.widget.TextView>(
                `in`.opentrack.india.R.id.provenance)
            val details = activity.findViewById<android.widget.ScrollView>(
                `in`.opentrack.india.R.id.heroDetailsScroll)
            val card = activity.findViewById<android.view.View>(
                `in`.opentrack.india.R.id.trackingSummaryCard)
            val scroller = activity.findViewById<android.widget.ScrollView>(
                `in`.opentrack.india.R.id.scroller)
            val footer = activity.findViewById<android.view.View>(
                `in`.opentrack.india.R.id.sourceSpinner).parent as android.view.View
            result = "badge='${badge.text}' visible=${badge.isShown} " +
                "alpha=${badge.alpha} badgeHeight=${badge.height} " +
                "details=${details.height}/${details.getChildAt(0).height} " +
                "detailsScroll=${details.scrollY} card=${card.height} " +
                "footerBottom=${footer.bottom} scroller=${scroller.height}/${scroller.scrollY}"
        }
        return result
    }

    @Test fun ticketToTimetableHasRealRowsAndClearlyQualifiedPosition() {
        openTicketTrain()
        val route = E2E.text("routeLabel")
        assertTrue("Opened train has no route", route.isNotBlank())
        assertTrue("Opened train has no run date", E2E.text("runDate").isNotBlank())
        assertTrue("Too few visible main stops to understand the journey", E2E.count(E2E.res("stopName")) >= 3)
        assertTrue("No scheduled arrival/departure is readable without opening a station",
            E2E.all(E2E.res("schedArr")).plus(E2E.all(E2E.res("schedDep")))
                .any { Regex("\\d{1,2}:\\d{2}").containsMatchIn(it.text.orEmpty()) })
        val badge = E2E.text("provenance")
        val heading = E2E.text("heroEyebrow")
        assertTrue("A traveller cannot tell whether the position is measured or only a timetable: " +
            "$badge / $heading · ${reportGeometry()}",
            badge.isNotBlank() && heading.isNotBlank())
        assertTrue("Offline screen calls a position live: $badge / $heading",
            !E2E.flag("offline") || (!badge.contains("LIVE", true) &&
                !heading.contains("LIVE POSITION", true)))
        E2E.shot("traveller-route")
    }

    @Test fun manualRefreshKeepsTicketAndShowsAnAnswer() {
        openTicketTrain()
        val route = E2E.text("routeLabel")
        // The initial source check can still be in flight after the timetable
        // appears. A second tap during that check is intentionally ignored by
        // the app; time the MANUAL request only after the first one completes.
        val initialDeadline = System.currentTimeMillis() + E2E.NETWORK_MS
        while (System.currentTimeMillis() < initialDeadline && E2E.visible("checkingBar", 250)) {
            Thread.sleep(250)
        }
        assertTrue("Initial source check never stopped; manual refresh cannot begin",
            !E2E.visible("checkingBar", 250))
        assertTrue("No refresh control a traveller can reach", E2E.reveal("refresh", "scroller") && E2E.tap("refresh"))
        val until = System.currentTimeMillis() + E2E.NETWORK_MS
        var receipt = ""
        while (System.currentTimeMillis() < until) {
            // The three-line overlay lasts only two seconds. Its complete
            // receipt stays accessible on the More/Less control afterward.
            E2E.reveal("heroToggle", "scroller", swipes = 3, up = false)
            // The warning can cover the badge, and Android's Spinner can expose
            // its selected child instead of its own description. The always-
            // reachable More/Less control must speak the completed receipt.
            val accessible = E2E.find("heroToggle", 250)?.contentDescription.orEmpty()
            receipt = Regex("""Checked \d{2}:\d{2}:\d{2} · [^.]+""")
                .find(accessible)?.value.orEmpty()
            if (receipt.startsWith("Checked ") && !receipt.contains("Checking", true) &&
                !receipt.contains("Refreshing", true) && !receipt.contains("Loading", true)) break
            Thread.sleep(250)
        }
        assertTrue("Refresh must not leave a persistent receipt below the card",
            E2E.find("refreshFeedback", 250) == null)
        assertTrue("Refresh never gave a completed, accessible receipt: '$receipt'; " +
            "toggle='${E2E.find("heroToggle", 250)?.contentDescription}' " +
            "badge='${E2E.find("provenance", 250)?.contentDescription}' " +
            "source='${E2E.find("sourceSpinner", 250)?.contentDescription}' " +
            "warning='${E2E.text("connectionAlert", 250)}' " +
            "busy=${E2E.visible("checkingBar", 250)}", receipt.startsWith("Checked ") &&
            !receipt.contains("Checking", true) && !receipt.contains("Refreshing", true) &&
            !receipt.contains("Loading", true))
        assertEquals("Refresh changed the ticket's train", "12627", E2E.text("trainNo"))
        val afterRoute = E2E.text("routeLabel")
        val baseRoute = "SBC → NDLS · 2406 km"
        val confirmedRoute = "$baseRoute · identity confirmed live (via erail)"
        // A parallel erail identity request may finish during refresh. Assert the
        // exact corridor/distance AND permit only the one-way addition of its
        // explicitly attributed identity; no other route mutation is acceptable.
        assertTrue("Initial ticket had a different corridor or unsupported identity: $route",
            route == baseRoute || route == confirmedRoute)
        assertTrue("Refresh erased or changed the route: $route -> $afterRoute",
            afterRoute == route || route == baseRoute && afterRoute == confirmedRoute)
        assertTrue("Refresh erased the station list", E2E.count(E2E.res("stopName")) > 0)
        E2E.shot("traveller-refresh")
    }

    @Test fun invalidOrImpossibleTripCannotMasqueradeAsAResult() {
        assertTrue("Board-at search is unavailable", E2E.typeInBox("fromBox", "yes"))
        assertTrue("Known station has no suggestion", E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
        assertTrue("Could not pick boarding station", E2E.tapSuggestion("fromBox"))
        assertEquals("A single station must not return trains before Find", 0, E2E.resultRows())
        assertTrue("Get-off-at search is unavailable", E2E.typeInBox("toBox", "yes"))
        // On the resized keyboard viewport a populated suggestion can sit below
        // the search-card fold. Scroll to the actual result; never accept a blank
        // panel as a match or skip the same-station refusal check.
        val sameStationSuggestion = E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) ||
            E2E.reveal("sugPrimary", "searchCardScroll", swipes = 4)
        if (!sameStationSuggestion) E2E.shot("same-station-suggestion-hidden")
        assertTrue("Same station has no reachable suggestion: ${E2E.debugSearch("toBox")}",
            sameStationSuggestion)
        assertTrue("Could not pick destination", E2E.tapSuggestion("toBox"))
        assertEquals("Picking the second station must wait for Find", 0, E2E.resultRows())
        assertTrue("Find missing for same-station validation",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        assertTrue("Same-station trip was not explicitly refused: '${E2E.text("statusLine")}'",
            listOf("same", "different").any { E2E.text("statusLine").contains(it, ignoreCase = true) })
        // ListAdapter diffs asynchronously: a previous search can still be in
        // the accessibility snapshot for a frame after the refusal is painted.
        assertTrue("Impossible trip kept stale train results after 3 seconds",
            E2E.ui.wait(Until.gone(E2E.res("hitNo")), 3_000L))
        assertEquals("Impossible trip still displays train results", 0, E2E.resultRows())
        E2E.shot("traveller-invalid-trip")
    }

    @Test fun settingsRoundTripReturnsToUsableSearch() {
        assertTrue("Settings entry does not respond", E2E.tap("settings"))
        assertTrue("Settings opened no usable screen", E2E.visible("settingsScroll"))
        // Battery mode must be a separate opt-in, and turning it OFF must restore
        // the ordinary search in this same published APK, without an uninstall.
        assertTrue("Battery toggle missing or unexpectedly enabled",
            E2E.find("batteryMode")?.isChecked == false)
        assertTrue("Battery toggle cannot be enabled", E2E.tap("batteryMode"))
        assertTrue("Battery toggle did not persist through screen recreation",
            E2E.ui.wait(Until.hasObject(E2E.res("batteryMode")), E2E.LOCAL_MS) &&
                E2E.find("batteryMode")?.isChecked == true)
        var bars = -1
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
                .getInstance().getActivitiesInStage(
                    androidx.test.runner.lifecycle.Stage.RESUMED).first()
            bars = activity.window.statusBarColor
        }
        assertEquals("Battery mode did not black out system bars", android.graphics.Color.BLACK, bars)
        E2E.shot("traveller-battery-settings")
        assertTrue("Battery toggle cannot be disabled", E2E.tap("batteryMode"))
        assertTrue("Battery mode did not switch off",
            E2E.ui.wait(Until.hasObject(E2E.res("batteryMode")), E2E.LOCAL_MS) &&
                E2E.find("batteryMode")?.isChecked == false)
        E2E.ui.pressBack()
        assertTrue("Back from Settings did not return to search", E2E.visible("modeTrain"))
        assertTrue("Search cannot be used after Settings", E2E.tap("modeTrain") &&
            E2E.typeInBox("trainBox", "12627") &&
            E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
        E2E.shot("traveller-settings-return")
    }

    @Test fun primaryControlsHaveLegibleTouchTargets() {
        val dp = E2E.densityDpi().toFloat() / 160f
        for (id in listOf("settings", "modeTrain", "modeStations")) {
            val control = E2E.find(id) ?: throw AssertionError("Missing visible $id control")
            val bounds = control.visibleBounds
            assertTrue("$id touch target too narrow: ${bounds.width() / dp}dp", bounds.width() / dp >= 48f)
            assertTrue("$id touch target too short: ${bounds.height() / dp}dp", bounds.height() / dp >= 48f)
            assertTrue("$id has no accessible spoken label",
                control.contentDescription.orEmpty().isNotBlank() || control.text.orEmpty().isNotBlank())
        }
        E2E.shot("traveller-controls")
    }
}
