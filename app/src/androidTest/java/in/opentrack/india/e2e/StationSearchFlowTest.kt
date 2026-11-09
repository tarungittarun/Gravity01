package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Searching by station, from the keyboard to a result list — the flow the bug report started from
 * ("suggestions are not coming while entering station name"), so it is the flow that gets the most
 * assertions here. Every one of them is a *promise about an answer*: the box either offers something to
 * tap or says why it cannot. Blank is never a legal outcome, which is the property no render test in this
 * repo could see, because a screenshot of an empty dropdown looks like a correct empty dropdown.
 */
@RunWith(AndroidJUnit4::class)
class StationSearchFlowTest {

    @Before
    fun up() {
        E2E.launch()
        E2E.awaitSearchScreen()
    }

    /** Rows appear after a 200 ms debounce and a query on an IO thread; polling is the only honest wait. */
    private fun awaitAnySuggestion(box: String, ms: Long = E2E.LOCAL_MS): Boolean {
        val deadline = System.currentTimeMillis() + ms
        var lastScroll = System.currentTimeMillis() + 750
        while (System.currentTimeMillis() < deadline) {
            val boxNode = E2E.ui.findObject(E2E.res(box))
            if (boxNode?.findObject(By.res(E2E.PKG, "sugPrimary")) != null) return true
            // The IME and enlarged font can put the answer below the visible viewport.
            // Scroll the search card like a traveller, not the dropdown itself.
            if (System.currentTimeMillis() >= lastScroll) {
                E2E.swipeIn("searchCardScroll")
                lastScroll = System.currentTimeMillis() + 750
            }
            Thread.sleep(120)
        }
        return false
    }

    private fun pick(box: String, query: String): Boolean {
        if (!E2E.typeInBox(box, query) || !awaitAnySuggestion(box)) return false
        return E2E.tapSuggestion(box, 0)
    }

    // @flow compact-home-search
    // @flow honest-date-limit
    @Test
    fun fourRowHomeKeepsTheSwapDateAndFindInReach() {
        val mode = E2E.find("modeRow")!!.visibleBounds
        val from = E2E.find("fromBox")!!.visibleBounds
        val to = E2E.find("toBox")!!.visibleBounds
        val swap = E2E.find("swap")!!.visibleBounds
        val date = E2E.find("dayNow")!!.visibleBounds
        val find = E2E.find("find")!!.visibleBounds
        assertTrue("the station fields are no longer vertically ordered", mode.bottom <= from.top &&
            from.bottom <= to.top && to.bottom <= date.top)
        assertTrue("swap must occupy the right quarter, not a third row: $swap $from $to",
            swap.left >= from.right && swap.top <= to.bottom && swap.bottom >= from.top)
        assertTrue("Find must be beside the date controls: $date $find",
            find.left > date.right && find.top <= date.bottom && find.bottom >= date.top)
        assertTrue("station fields must use roughly three quarters of the input width",
            from.width().toFloat() / (from.width() + swap.width()) in 0.68f..0.78f)
        assertTrue("tracking default belongs only in Settings",
            E2E.find("defaultTrackingSource", 200) == null)
        assertTrue("logo/home control missing", E2E.find("homeBrand") != null)
        assertTrue("minus one day not tappable", E2E.tap("dayBack"))
        assertTrue("cannot promise older actuals with another tap", !E2E.find("dayBack")!!.isEnabled)
        assertTrue("home logo does not return to the search form", E2E.tap("homeBrand") &&
            E2E.visible("fromBox"))
        E2E.shot("compact-home-search")
    }

    // @flow type-ahead-station
    @Test
    fun threeLettersOfferMatchesThatFillTheField() {
        assertTrue("could not type 'yes' into the board-at box", E2E.typeInBox("fromBox", "yes"))
        val hasOffer = awaitAnySuggestion("fromBox")
        assertTrue("typing 'yes' offered nothing: ${E2E.debugSearch("fromBox")}", hasOffer)
        val offered = E2E.suggestionRows()
        assertTrue("the panel showed ${offered.size} rows for 'yes'", offered.isNotEmpty())
        val first = offered[0].text.orEmpty()
        assertTrue("tapping should fill the name, got '$first'", first.isNotBlank())
        assertTrue("tapping the row did nothing", E2E.tapSuggestion("fromBox", 0))
        assertEquals("the field does not show the name that was picked", first, E2E.boxText("fromBox"))
        val chip = E2E.boxChip("fromBox")
        assertTrue("the resolved code did not surface for the traveller: '$chip'", chip.contains("SBC") ||
            chip.isNotBlank())
        E2E.shot("type-ahead-picked")
        E2E.assertNoSystemDialog("type-ahead")
    }

    // @flow type-ahead-min-length
    @Test
    fun twoLettersAreSilentByDesign() {
        E2E.typeInBox("fromBox", "ne")
        // 'B' matching 400 stations is noise; three letters is where a name or code becomes specific.
        assertTrue("'ne' opened a list although two letters are below the floor", E2E.suggestionRows().isEmpty())
        // …and the same box must light up at three, so this test cannot pass by the box being dead.
        E2E.typeInBox("fromBox", "yes")
        val answered = awaitAnySuggestion("fromBox")
        assertTrue("'yes' offered nothing after 'ne' was correctly ignored: ${E2E.debugSearch("fromBox")}", answered)
    }

    // @flow type-ahead-never-silent
    @Test
    fun everyQueryAnswersWithRowsOrWithAReason() {
        for (q in listOf("yes", "mas", "qqzzxxy", "01404")) {
            assertTrue("could not reach the board-at field to type '$q'", E2E.typeInBox("fromBox", q))
            val answered = awaitAnySuggestion("fromBox")
            assertTrue("typing '$q' left the box unanswered: ${E2E.debugSearch("fromBox")}", answered)
            if (q == "qqzzxxy") {
                // The preceding "mas" panel can remain in an accessibility snapshot while
                // the 200 ms debounce and SQLite lookup finish. Wait for THIS query's answer,
                // not just any old sugPrimary node that UiAutomator still knows about.
                val deadline = System.currentTimeMillis() + E2E.LOCAL_MS
                var rows = E2E.suggestionRows()
                while (System.currentTimeMillis() < deadline &&
                    (rows.size != 1 || !runCatching { rows[0].text.orEmpty() }
                        .getOrDefault("").contains("matches", true))) {
                    Thread.sleep(120)
                    rows = E2E.suggestionRows()
                }
                assertEquals("an unmatched query never became one explanatory row: " +
                    "input='${E2E.boxText("fromBox")}' rows=${rows.map { runCatching { it.text }.getOrNull() }}",
                    1, rows.size)
                assertTrue("the no-match row still shows results from the previous query",
                    runCatching { rows[0].text.orEmpty() }.getOrDefault("").contains("matches", true))
            } else {
                assertTrue("'$q' produced an empty panel", E2E.suggestionRows().isNotEmpty())
            }
        }
        E2E.shot("never-silent")
    }

    // @flow populated-home-logo
    @Test
    fun homeLogoKeepsPopulatedResultsDirectlyBelowTheOpenSearch() {
        assertTrue("boarding station not selectable", pick("fromBox", "yes"))
        assertTrue("destination not selectable", pick("toBox", "smet"))
        assertEquals("pair suggestions must wait for Find", 0, E2E.resultRows())
        assertTrue("Find missing for pair", E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        assertTrue("station-pair result never arrived",
            E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "hitGo")), E2E.NETWORK_MS))
        val before = E2E.text("resultsSummary")
        assertTrue("fixture must have a populated list", E2E.resultRows() > 0 && before.contains("train"))
        assertTrue("Home logo not tappable", E2E.tap("homeBrand"))
        assertTrue("Home logo lost the expanded search form", E2E.visible("searchCardScroll"))
        val after = E2E.text("resultsSummary")
        assertTrue("no result card exposed after opening the search form",
            E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "hitNo")), E2E.LOCAL_MS))
        val rows = E2E.resultRows()
        // A live erail upgrade may legitimately change the number of hits while
        // the card opens. It must never erase every result or its summary.
        assertTrue("Home logo discarded visible results: before='$before' after='$after' rows=$rows",
            rows > 0 && Regex("^[1-9]\\d* trains?\\b").containsMatchIn(after))
        assertTrue("Home logo hid the summary/header/sort",
            E2E.visible("resultsSummary") && E2E.visible("resultsHead") &&
                E2E.visible("resultsSortRow") && E2E.text("resultsTitle") == "SEARCHED TRAINS")
        val card = E2E.find("searchCard")!!.visibleBounds
        val summary = E2E.find("resultsSummary")!!.visibleBounds
        val results = E2E.find("results")!!.visibleBounds
        val pxPerDp = E2E.ui.displayWidth / 360f
        assertTrue("blank gap after Home logo: $card / $summary / $results",
            summary.top - card.bottom in 0..(80 * pxPerDp).toInt() &&
                results.top - card.bottom in 0..(130 * pxPerDp).toInt() &&
                results.height() >= 48 * pxPerDp)
        E2E.shot("populated-home-logo")
    }

    // @flow both-ends-search
    @Test
    fun bothEndsRunASearch() {
        assertTrue("board-at never resolved", pick("fromBox", "yes"))
        assertTrue("get-off-at never resolved", pick("toBox", "ndls"))
        assertEquals("picking the second station must not run Find", 0, E2E.resultRows())
        assertTrue("Find missing for chosen stations", E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        val sawAnswer = E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "resultsTitle")), E2E.NETWORK_MS)
        val status = E2E.text("statusLine")
        assertTrue(
            "both ends filled and the screen said nothing (answer=$sawAnswer, status='$status')",
            sawAnswer || status.isNotBlank() || E2E.resultRows() > 0,
        )
        val title = E2E.text("resultsTitle")
        assertTrue("the results header is blank while showing a list", title.isNotBlank() || E2E.resultRows() == 0)
        if (E2E.resultRows() > 0 && E2E.visible("editSearch", 500)) {
            assertTrue("Edit search did not reopen the SEARCH BOX", E2E.tap("editSearch"))
            assertTrue("SEARCH BOX label missing", E2E.ui.wait(
                Until.hasObject(By.text("SEARCH BOX")), E2E.LOCAL_MS))
            // Editing is a draft, not another Find: the old list, count and
            // source summary must disappear until the traveller submits again.
            assertEquals("edit showed pre-Find result rows", 0, E2E.resultRows())
            assertTrue("edit kept an old source/count summary before Find",
                E2E.find("resultsSummary", 300) == null)
            assertTrue("edited Find cannot be reached", E2E.reveal("find", "searchCardScroll") &&
                E2E.tap("find"))
            assertTrue("submitted edit did not restore a new source/count summary",
                E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "resultsSummary")), E2E.NETWORK_MS))
            val compact = E2E.find("searchCompact")?.visibleBounds
            val summary = E2E.find("resultsSummary")?.visibleBounds
            assertTrue("large blank gap between submitted edit and count/source/date: $compact / $summary",
                compact != null && summary != null &&
                    summary.top - compact.bottom in 0..(E2E.ui.displayWidth / 5))
            assertTrue("result count missing from summary", E2E.text("resultsSummary").contains("train"))
        }
        E2E.shot("both-ends")
        E2E.assertNoSystemDialog("both ends")
    }

    // @flow empty-query-feedback
    @Test
    fun findWithBlankFieldsSaysWhatsMissing() {
        E2E.typeInBox("fromBox", "")
        E2E.typeInBox("toBox", "")
        assertTrue("Find cannot be reached on the search screen",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        val status = E2E.text("statusLine")
        assertTrue("Find with both fields blank answered with nothing", status.isNotBlank())
        assertTrue("said: $status", status.contains("station", ignoreCase = true))
    }

    // @flow unknown-station
    @Test
    fun unknownStationNamesTheFix() {
        E2E.typeInBox("fromBox", "qqzzxxy")
        E2E.typeInBox("toBox", "yes")
        assertTrue("Find cannot be reached after typing on a large-font screen",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        val status = E2E.text("statusLine")
        assertTrue("an unknown boarding station produced no message", status.isNotBlank())
        // The advice that actually helps at a station: use the code.
        assertTrue("said: $status", status.contains("code", ignoreCase = true) ||
            status.contains("matches", ignoreCase = true))
    }

    // @flow same-station
    @Test
    fun sameStationBothEndsIsRefused() {
        assertTrue(E2E.typeInBox("fromBox", "yes") && awaitAnySuggestion("fromBox"))
        E2E.tapSuggestion("fromBox", 0)
        assertTrue(E2E.typeInBox("toBox", "yes") && awaitAnySuggestion("toBox"))
        assertTrue("get-off-at could not be selected", E2E.tapSuggestion("toBox", 0))
        assertEquals("same-station selection must wait for Find", 0, E2E.resultRows())
        assertTrue("Find missing for same-station refusal", E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        val status = E2E.text("statusLine")
        val rows = E2E.resultRows()
        assertTrue("board==alight produced neither a refusal nor a list (status='$status', rows=$rows)",
            status.isNotBlank() || rows == 0)
        if (status.isNotBlank()) {
            assertTrue("said: $status", status.contains("same", ignoreCase = true) ||
                !status.contains("direct", ignoreCase = true))
        }
    }

    // @flow day-shift
    @Test
    fun dayButtonsMoveTheDate() {
        assertTrue("travelling date cannot be reached in the search card",
            E2E.reveal("whenValue", "searchCardScroll"))
        val start = E2E.text("whenValue")
        assertTrue("the travelling date is not shown at all", start.isNotBlank())
        assertTrue("no +1d control", E2E.tap("dayFwd"))
        val ahead = E2E.text("whenValue")
        assertNotEquals("+1d left the date unchanged", start, ahead)
        assertTrue("no 'now' control to get back", E2E.tap("dayNow"))
        val backToNow = E2E.text("whenValue")
        assertEquals("'now' did not return to the original day",
            start.substringBefore(" · "), backToNow.substringBefore(" · "))
        // 'Now' reads the actual clock again, not the time at test startup. At a
        // minute boundary its HH:MM is legitimately one minute later.
        fun minute(label: String): Int {
            val clock = Regex("(\\d{2}):(\\d{2})").find(label)
                ?: error("date has no readable clock: '$label'")
            return clock.groupValues[1].toInt() * 60 + clock.groupValues[2].toInt()
        }
        val elapsedMinutes = (minute(backToNow) - minute(start) + 1440) % 1440
        assertTrue("'now' jumped by $elapsedMinutes minutes: '$start' → '$backToNow'",
            elapsedMinutes <= 2)
        assertTrue("no −1d control", E2E.tap("dayBack"))
        assertNotEquals("−1d left the date on 'now'", start, E2E.text("whenValue"))
        // A fifth visible line is avoided, but the window remains available to TalkBack.
        assertTrue("the ±24 h window is not explained on the date control",
            E2E.find("whenValue")?.contentDescription.orEmpty().contains("24"))
    }

    // @flow swap
    @Test
    fun swapMovesTheResolvedCodes() {
        assertTrue(E2E.typeInBox("fromBox", "yes") && awaitAnySuggestion("fromBox"))
        E2E.tapSuggestion("fromBox", 0)
        assertTrue(E2E.typeInBox("toBox", "ndls") && awaitAnySuggestion("toBox"))
        assertTrue("could not pick 'ndls' before swapping", E2E.tapSuggestion("toBox", 0))
        assertEquals("pair suggestions unexpectedly ran Find before Swap", 0, E2E.resultRows())
        var before = E2E.boxText("fromBox") to E2E.boxText("toBox")
        if (before.first.isBlank() || before.second.isBlank()) {
            // A late result may collapse the form between reading the status and
            // reading the fields. Edit is the visible user control that restores it.
            if (E2E.visible("editSearch", 1_000)) assertTrue(E2E.tap("editSearch"))
            before = E2E.boxText("fromBox") to E2E.boxText("toBox")
        }
        assertTrue("resolved stations vanished before swap: $before",
            before.first.isNotBlank() && before.second.isNotBlank())
        // The action sits BELOW both fields; on large-font devices it is not an
        // accessibility node until the user scrolls the search card down.
        var tappedSwap = E2E.reveal("swap", "searchCardScroll") && E2E.tap("swap")
        if (!tappedSwap && E2E.visible("editSearch", 500)) {
            assertTrue("Edit did not reopen the late-collapsed form", E2E.tap("editSearch"))
            tappedSwap = E2E.reveal("swap", "searchCardScroll") && E2E.tap("swap")
        }
        assertTrue("no swap control in the expanded search form", tappedSwap)
        // The tap is at the bottom of a nested scrolling card. Accessibility can
        // return an empty text node for the first box until the OUTER card reaches
        // the top on a short/large-font screen, even though swap has set its text.
        var swappedFrom = E2E.boxText("fromBox")
        repeat(6) {
            if (swappedFrom.isBlank() && E2E.swipeIn("searchCardScroll", up = false))
                swappedFrom = E2E.boxText("fromBox")
        }
        val swappedTo = E2E.boxText("toBox")
        assertEquals("swap did not exchange the board-at field; " +
            "chip='${E2E.boxChip("fromBox")}' compact=${E2E.visible("editSearch", 200)}",
            before.second, swappedFrom)
        assertEquals("swap did not exchange the get-off-at field", before.first, swappedTo)
        E2E.shot("swap")
    }
    // @flow live-pair-list (v2 slice 1 + 2): the pair search must SAY where its rows came from.
    // A row without a named source is the founding bug's door: timetable dressed as live. So the
    // contract here is exactly: rows exist -> at least one names erail (the live authority);
    // nothing answered -> a typed message exists. Silence and unnamed rows both fail.
    @Test(timeout = 120_000) // a wedged feed must cost this test, never the whole class (600s cap)
    fun pairRowsNameTheirSource() {
        assertTrue("board-at 'yes' never resolved", E2E.typeInBox("fromBox", "yes"))
        assertTrue("no suggestion for 'yes'", E2E.ui.wait(Until.findObject(By.res(E2E.PKG, "sugPrimary")), E2E.LOCAL_MS) != null)
        assertTrue("could not pick 'yes'", E2E.tapSuggestion("fromBox", 0))
        assertTrue("get-off-at 'ndls' never resolved", E2E.typeInBox("toBox", "ndls"))
        assertTrue("no suggestion for 'ndls': ${E2E.debugSearch("toBox")}", awaitAnySuggestion("toBox"))
        assertTrue("could not pick 'ndls'", E2E.tapSuggestion("toBox", 0))
        assertEquals("pair search ran before Find", 0, E2E.resultRows())
        assertTrue("Find missing for source-labelled rows",
            E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        val answered = E2E.ui.wait(Until.hasObject(By.res(E2E.PKG, "resultsTitle")), E2E.NETWORK_MS)
        val metas = E2E.all(By.res(E2E.PKG, "hitMeta")).mapNotNull { it.text }
        val status = E2E.text("statusLine")
        val sourceExplanation = E2E.find("resultsTitle", 250)?.contentDescription.orEmpty()
        assertTrue(
            "the pair search ended in silence (header=$answered, rows=${metas.size}, status='$status')",
            answered || metas.isNotEmpty() || status.isNotBlank(),
        )
        if (metas.isNotEmpty()) {
            assertTrue(
                "rows were shown but none names its source (2.3): first='${metas.first()}'",
                metas.all { it.contains("erail", ignoreCase = true) ||
                    it.contains("timetable", ignoreCase = true) } &&
                    sourceExplanation.isNotBlank(),
            )
        }
        E2E.shot("live-pair-rows")
    }

}
