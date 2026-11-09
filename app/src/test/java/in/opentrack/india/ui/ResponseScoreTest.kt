package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseScoreTest {
    private val checked = RailTime.railMinuteToEpochMillis(20_000L, 13 * 60 + 40)

    @Test fun unavailableEventNeverBorrowsTheCheckOrPageClock() {
        val receipt = ResponseScore.text(751, null, checked)
        assertEquals("Took: 0.8 sec\nFreshness: unknown\nChecked: " +
            RailTime.formatClockSeconds(checked), receipt)
        assertFalse(receipt.contains("At: —"))
        assertEquals("Took: 0.0 sec\nFreshness: unknown\nChecked: " +
            RailTime.formatClockSeconds(checked), ResponseScore.text(-3, null, checked))
    }

    @Test fun twentyFiveMinuteOldEventIsRedBoundaryEvenIfTheWebsiteJustUpdated() {
        val event = checked - 25 * 60_000L
        assertTrue(SourceFreshness.isStale(event, checked))
        assertFalse(SourceFreshness.isStale(event + 1, checked))
        assertEquals("Took: 1.0 sec\nFreshness: 25m old\nChecked: " +
            RailTime.formatClockSeconds(checked), ResponseScore.text(999, event, checked))
        assertEquals("unknown", SourceFreshness.label(checked + 60_000L, checked))
        assertEquals(751L, ResponseScore.durationMillis("answered · checked in 751 ms"))
        assertEquals(null, ResponseScore.durationMillis("no response"))
    }
}
