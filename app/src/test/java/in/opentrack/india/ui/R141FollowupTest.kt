package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class R141FollowupTest {
    @Test fun routeGapIsNotRemainingDistanceAndNeverUsesRefreshClock() {
        val label = MarkerDistanceLabel.next("Adihalli", 4, 9 * 60 + 16, false)
        assertTrue(label.contains("4 km route gap to Adihalli"))
        assertTrue(label.contains("as on 09:16"))
        assertTrue(label.contains("position unknown"))
        assertTrue(!label.contains("remaining") && !label.contains("Between"))
        assertTrue(MarkerDistanceLabel.next("Tiptur", null, null, true)
            .contains("distance unavailable"))
    }

    @Test fun onlyFutureUnreportedOriginRunIsScheduledNotStarted() {
        val today = 20746L
        val planned = 9 * 60 + 50
        val now = RailTime.railMinuteToEpochMillis(today, 9 * 60 + 24)
        assertEquals(planned, JourneyStartUi.scheduledStart(today, planned, now, false))
        assertNull(JourneyStartUi.scheduledStart(today, planned, now, true))
        assertNull(JourneyStartUi.scheduledStart(today - 1, planned, now, false))
        assertNull(JourneyStartUi.scheduledStart(today, 9 * 60, now, false))
    }
}
