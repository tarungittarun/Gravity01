package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SearchResultSummaryTest {
    @Test fun sourceStampIsDataVintageNotPhoneFetchTime() {
        val note = "live list via erail · erail data stamp 2026-10-17-19-1-0 " +
            "(the feed's own date, not our fetch time) · cached, fetched 8 min ago"
        val line = SearchResultSummary.line(3, note, listOf("via erail", "timetable"))
        assertEquals("3 trains · cached erail + timetable · source data as of 17 Oct 2026, 19:01 IST", line)
        assertFalse(line.contains("fetched 8 min ago"))
    }

    @Test fun noFeedStampIsNeverMadeUpFromTheClock() {
        assertEquals("1 train · on-device timetable", SearchResultSummary.line(1, null))
        assertEquals("4 trains · erail · source date not supplied",
            SearchResultSummary.line(4, "live list via erail", listOf("via erail")))
        assertEquals("2 trains · erail · source date not supplied",
            SearchResultSummary.line(2, "live list via erail · erail data stamp 2026-99-17-25-5-0",
                listOf("via erail")))
        assertEquals("2 trains · on-device timetable",
            SearchResultSummary.line(2, "no live list reached this device"))
    }
}
