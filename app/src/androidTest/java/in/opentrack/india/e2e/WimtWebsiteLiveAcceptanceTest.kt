package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.TimeZone

/** A STRICT online gate on the installed APK, not the more permissive fixture/source UI suite.
 * HTTP 403, a challenge page, NTES fallback, a web-cache candidate, timetable-only cells,
 * or a page with no qualified current-run past card must all FAIL, with a screenshot.
 * The independent .org.in website is not Google's WIMT phone-app feed. */
@RunWith(AndroidJUnit4::class)
class WimtWebsiteLiveAcceptanceTest {
    @Test fun websiteRefreshMustPaintARealSourceQualifiedPastCard() {
        // The site omits the origin run date. A completed or future service cannot
        // qualify its cards for this test merely because its page answered. Use a
        // train whose known timetable is in flight at this IST hour; outside these
        // windows fail before making any site request rather than claiming success.
        val hour = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
            .get(Calendar.HOUR_OF_DAY)
        val number = when (hour) {
            in 0..4 -> "16227"  // yesterday's MYS -> TLGP overnight run;
                                    // number-mode search picks the nearest origin day
            in 5..11 -> "20652"  // TLGP -> SBC morning service
            in 12..13 -> "12002" // NDLS -> BPL scheduled 06:00-14:00; timetable candidate only
            in 15..19 -> "20651" // SBC -> TLGP afternoon service
            in 20..23 -> "16227" // MYS -> TLGP overnight service
            else -> error("no known unambiguously active website test train at IST hour $hour")
        }
        try {
            println("WIMT_DEVICE_SELECTED_TRAIN=$number (timetable candidate, not live position)")
            E2E.launch()
            E2E.awaitSearchScreen()
            // The diagnostic switch is opt-in, enabled here through the real Settings UI.
            assertTrue("Settings not reachable", E2E.tap("settings"))
            assertTrue("diagnostic switch not reachable", E2E.reveal("diagToggle", "settingsScroll"))
            assertTrue("cannot enable source diary", E2E.tap("diagToggle"))
            E2E.ui.pressBack()
            assertTrue("train mode missing", E2E.tap("modeTrain"))
            assertTrue("$number not in search", E2E.typeInBox("trainBox", number) &&
                E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
            assertTrue("could not select $number", E2E.tapSuggestion("trainBox"))
            assertTrue("Find missing", E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
            assertTrue("$number result missing", E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS))
            assertTrue("Track not opened", E2E.openTrackFromFirstResult())
            assertEquals("wrong train opened", number, E2E.text("trainNo"))
            E2E.shotWimt("wimt-before-selected-source")
            assertTrue("source selector not reachable", E2E.reveal("sourceSpinner", "scroller") &&
                E2E.tap("sourceSpinner"))
            val site = E2E.ui.wait(Until.findObject(By.textContains("Where Is My Train · website")),
                E2E.LOCAL_MS)
            assertNotNull("independent website not selectable", site)
            site!!.click() // one deliberate selected-source request; no background retry in the test
            val until = System.currentTimeMillis() + E2E.NETWORK_MS
            var accepted = false
            var warning = ""
            var headline = ""
            var badge = ""
            while (System.currentTimeMillis() < until) {
                headline = E2E.text("headline", 250)
                badge = E2E.text("provenance", 250)
                warning = E2E.text("connectionAlert", 250)
                val reported = (headline.startsWith("Last mapped website card") ||
                    headline.startsWith("Recently reported near")) &&
                    badge.contains("run inferred", true)
                if (reported) { accepted = true; break }
                if (warning.contains("HTTP 403") || warning.contains("blocks request", true) ||
                    warning.contains("HTTP 429")) break
                Thread.sleep(250)
            }
            E2E.shotWimt("wimt-after-selected-source")
            assertTrue("No qualified website past card in the installed app; " +
                "headline='$headline' badge='$badge' warning='$warning'. " +
                "NTES fallback, HTTP 200 alone and the .in web cache do not pass.", accepted)
            assertTrue("time-sheet not reachable", E2E.reveal("routeHeadActual", "scroller"))
            assertEquals("website data was mislabeled ACTUAL", "WEBSITE", E2E.text("routeHeadActual"))
            val siteCells = E2E.all(By.res(E2E.PKG, "liveArr")) +
                E2E.all(By.res(E2E.PKG, "liveDep"))
            assertTrue("site report has no painted source-attributed times",
                siteCells.any { it.contentDescription.orEmpty().startsWith("Website displayed") })
        } finally {
            // Preserve the original assertion (especially the observed 403) even if
            // this optional last shot fails. Before/after captures are mandatory.
            runCatching { E2E.shotWimt("wimt-final-even-on-failure") }
                .onFailure { println("WIMT_OPTIONAL_FINAL_SHOT_FAILED: ${it.javaClass.simpleName}") }
        }
    }
}
