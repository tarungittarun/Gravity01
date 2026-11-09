package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRecencyTest {
    @Test fun onlySameStopRecentEventsCanBeDescribedAsAgreeing() {
        assertEquals(1, SourceRecency.agreementMinutes("ASK", 1047, "ASK", 1046,
            30_000L, 240_000L))
        assertNull(SourceRecency.agreementMinutes("TK", 982, "TTR", 1026,
            30_000L, 180_000L))
        assertNull(SourceRecency.agreementMinutes("ASK", 1047, "ASK", 1046,
            301_000L, 240_000L))
        assertNull(SourceRecency.agreementMinutes("ASK", 1047, "ASK", 1046,
            30_000L, 601_000L))
        assertNull(SourceRecency.agreementMinutes("ASK", null, "ASK", 1046,
            30_000L, 240_000L))
    }

    @Test fun newerPastMarkedHaltCanBeOfferedAsUnverifiedComparison() {
        assertTrue(SourceRecency.showComparison(10, 11, 12 * 60_000L, 30_000L, 60_000L))
        assertTrue("a source-reported intermediate just beyond the same official halt must be offered",
            SourceRecency.showComparison(SourceRecency.progressOrdinal(10, false),
                SourceRecency.progressOrdinal(10, true), 12 * 60_000L, 30_000L, 60_000L))
        assertFalse("a past-marked card at the same halt is not a later point",
            SourceRecency.showComparison(SourceRecency.progressOrdinal(10, false),
                SourceRecency.progressOrdinal(10, false), 12 * 60_000L, 30_000L, 60_000L))
        assertFalse("site page clock alone is not movement",
            SourceRecency.showComparison(10, 10, 12 * 60_000L, 0L, 60_000L))
        assertFalse("regressing page cannot be offered",
            SourceRecency.showComparison(10, 9, 12 * 60_000L, 0L, 60_000L))
        assertFalse("stale website page cannot win",
            SourceRecency.showComparison(10, 11, 12 * 60_000L, 6 * 60_000L, 60_000L))
        assertFalse("fresh official report should not be displaced",
            SourceRecency.showComparison(10, 11, 60_000L, 0L, 60_000L))
        assertFalse("a freshly checked page cannot redate an old stop event",
            SourceRecency.showComparison(10, 11, 12 * 60_000L, 0L, 12 * 60_000L))
        assertFalse("new NTES response stamp does not redate a 15-minute-old stop",
            SourceRecency.officialIsNewer(15 * 60_000L, 60_000L))
        assertTrue("a genuinely newer official stop can outrank an older site claim",
            SourceRecency.officialIsNewer(60_000L, 15 * 60_000L))
        assertFalse("an undated official event cannot win via a fresh feed header",
            SourceRecency.officialIsNewer(null, 15 * 60_000L))
        assertFalse("a future-looking clock is not a source event",
            SourceRecency.officialIsNewer(-1L, 15 * 60_000L))
    }
}
