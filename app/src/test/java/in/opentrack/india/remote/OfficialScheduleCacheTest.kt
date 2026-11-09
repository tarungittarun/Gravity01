package `in`.opentrack.india.remote

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A live-list-only train (20689) must reopen with all VALIDATED static halts before live. */
class OfficialScheduleCacheTest {
    private val day = 20740L
    private val at = 1_791_900_000_000L
    private val route = TrainRoute("20689", "SMET INTERCITY", "YPR", "Yesvantpur",
        "SMET", "Shivamogga", 275, 290, null, null, listOf(
            StopRow(1, "YPR", "Yesvantpur", null, null, null, null, 555, 0, 0, true),
            StopRow(2, "TK", "Tumakuru", null, null, null, 605, 607, 0, 70, true),
            StopRow(3, "ASK", "Arsikere", null, null, null, 680, 682, 0, 140, true),
            StopRow(4, "SMET", "Shivamogga", null, null, null, 845, null, 0, 275, true),
        ), emptyList())

    @Test fun staticOnlyCacheReopensAllStopsWithoutAnyActualCell() {
        val raw = OfficialScheduleCache.encode(route, day, at)
        val decoded = OfficialScheduleCache.decode(raw, "20689", day, "YPR", "SMET", at + 60_000)!!
        assertEquals(listOf("YPR", "TK", "ASK", "SMET"), decoded.stops.map { it.code })
        assertEquals(607, decoded.stops[1].departureMin)
        assertNull(decoded.stops[1].actualDepartureMin)
        assertNull(OfficialScheduleCache.decode(raw, "20689", day + 1, "YPR", "SMET", at + 60_000))
        assertNull(OfficialScheduleCache.decode(raw, "20689", day, "SMET", "YPR", at + 60_000))
        assertNull(OfficialScheduleCache.decode(raw, "20689", day, "YPR", "SMET",
            at + OfficialScheduleCache.MAX_AGE_MS + 1))
    }

    @Test fun intermediatePointSurvivesReopenWithoutAcquiringInventedTimesOrHaltStatus() {
        val extra = StopRow(3, "SDVL", "Soldevanahalli", null, null, null,
            null, null, 0, -1, false)
        val withPoint = route.copy(stops = route.stops.take(2) + listOf(extra) +
            route.stops.drop(2).map { it.copy(seq = it.seq + 1) })
        val raw = OfficialScheduleCache.encode(withPoint, day, at)
        val decoded = OfficialScheduleCache.decode(raw, "20689", day, "YPR", "SMET", at + 1)!!
        assertEquals(listOf("YPR", "TK", "SDVL", "ASK", "SMET"),
            decoded.stops.map { it.code })
        assertEquals(false, decoded.stops[2].isHalt)
        assertNull(decoded.stops[2].arrivalMin)
        assertNull(decoded.stops[2].departureMin)
    }

    @Test fun corruptedScheduleCannotBecomeTheFirstPaint() {
        val raw = OfficialScheduleCache.encode(route, day, at)
        assertNull(OfficialScheduleCache.decode(raw.replace("\"TK\"", "\"YPR\""),
            "20689", day, "YPR", "SMET", at + 1))
        assertNull(OfficialScheduleCache.decode("not JSON", "20689", day, "YPR", "SMET", at + 1))
    }
}
