package `in`.opentrack.india.ui

import `in`.opentrack.india.domain.TrainFinder
import `in`.opentrack.india.model.StationHit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exact contradiction found by the published-APK acceptance run: the offline
 * SAME_STATION refusal must not be replaced by a late website listing. */
class PairSearchGateTest {
    private fun station(code: String) = StationHit(code, code, null, null, null, null, 1)
    private fun report(from: String?, to: String?, reason: TrainFinder.Reason) =
        TrainFinder.Report(from?.let(::station), to?.let(::station), emptyList(), reason)

    @Test fun invalidPairNeverStartsAnUpstreamRequest() {
        assertFalse(PairSearchGate.eligible(report("YES", "YES", TrainFinder.Reason.SAME_STATION)))
        assertFalse(PairSearchGate.eligible(report("YES", "yes", TrainFinder.Reason.OK)))
        assertFalse(PairSearchGate.eligible(report(null, "NDLS", TrainFinder.Reason.STATION_NOT_FOUND)))
        assertFalse(PairSearchGate.eligible(report("YES", "NDLS", TrainFinder.Reason.STATION_NOT_FOUND)))
    }

    @Test fun validPairMayAskTheIndependentSourceEvenIfOfflineHasNoRows() {
        assertTrue(PairSearchGate.eligible(report("YES", "NDLS", TrainFinder.Reason.OK)))
        assertTrue(PairSearchGate.eligible(report("YES", "NDLS", TrainFinder.Reason.NO_DIRECT_TRAINS)))
    }

    @Test fun oldResponseCannotOverwriteANewerQueryOrMode() {
        assertTrue(PairSearchGate.current(4, 4))
        assertFalse(PairSearchGate.current(4, 5))
    }
}
