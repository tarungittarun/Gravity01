package `in`.opentrack.india.remote

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialRunCacheLedgerTest {
    private val day = 20753L // 27 Oct, day-two actuals are still on this run
    private val stamp = RailTime.railMinuteToEpochMillis(day, 1733)
    private val route = TrainRoute("16227", "MYS TLGP", "MYS", "Mysuru", "TLGP", "Talguppa",
        510, null, null, null, listOf(
            StopRow(1, "MYS", "Mysuru", null, null, null, null, 1180, 0, 0, true),
            StopRow(2, "BDVT", "Bhadravati", null, null, null, 1708, 1710, 1, 394, true),
            StopRow(3, "SME", "Shimoga", null, null, null, 1724, 1726, 1, 410, true),
            StopRow(4, "SMET", "Shimoga Town", null, null, null, 1740, 1745, 1, 412, true),
            StopRow(5, "TLGP", "Talguppa", null, null, null, 1900, null, 1, 510, true),
        ), emptyList())
    private val fresh = LiveSnapshot("16227", day, "SMET", 4, "Shimoga Town",
        StopStatus.ARRIVED, 1742, 1740, "TLGP", null, 2, null,
        mapOf("SMET" to LiveStopFields(actualArrivalMin = 1742)), stamp,
        SourceKind.PROVIDER_NTES, feedStampMillis = stamp, reportedRoute = route)

    @Test fun acceptedClocksSurviveOmissionButDoNotChangeTheLatestEvent() {
        val previous = fresh.copy(fetchedAtMillis = stamp - 120_000,
            sourceKind = SourceKind.CACHED, perStop = mapOf(
                "BDVT" to LiveStopFields(actualArrivalMin = 1709, actualDepartureMin = 1712),
                "SME" to LiveStopFields(actualArrivalMin = 1723),
                "SMET" to LiveStopFields(actualArrivalMin = 1741)))
        val merged = OfficialRunCache.retainAccepted(fresh, previous)
        assertEquals("SMET", merged.atCode)
        assertEquals(1742, merged.eventMinute)
        assertEquals(1742, merged.perStop["SMET"]?.actualArrivalMin) // newer correction wins
        assertEquals(1709, merged.perStop["BDVT"]?.actualArrivalMin)
        assertEquals(setOf("BDVT:arr", "BDVT:dep", "SME:arr"), merged.savedOfficialClocks)
        val restored = OfficialRunCache.decode(OfficialRunCache.encode(merged),
            "16227", day, "MYS", "TLGP", stamp + 60_000)!!
        assertEquals(merged.savedOfficialClocks, restored.savedOfficialClocks)
        assertEquals(merged.perStop["BDVT"]?.actualDepartureMin,
            restored.perStop["BDVT"]?.actualDepartureMin)
        val answer = PositionEngine.locate(PositionEngine.Request(route, merged,
            1745, stamp + 60_000))
        assertEquals("SMET", answer.anchorStopCode)
        assertTrue("BDVT:arr" in answer.savedOfficialClocks)
        assertEquals(merged.savedOfficialClocks,
            OfficialRunCache.retainAccepted(fresh.copy(fetchedAtMillis = stamp + 60_000), restored)
                .savedOfficialClocks)
    }

    @Test fun runRouteFutureAndScheduleEchoNeverEnterTheLedger() {
        val previous = fresh.copy(sourceKind = SourceKind.CACHED, fetchedAtMillis = stamp - 60_000,
            perStop = mapOf(
                "BDVT" to LiveStopFields(actualArrivalMin = 1708, actualDepartureMin = 1714),
                "TLGP" to LiveStopFields(actualArrivalMin = 1902)))
        val merged = OfficialRunCache.retainAccepted(fresh, previous)
        assertNull(merged.perStop["BDVT"]?.actualArrivalMin) // schedule echo
        assertEquals(1714, merged.perStop["BDVT"]?.actualDepartureMin)
        assertNull(merged.perStop["TLGP"]?.actualArrivalMin) // ahead of official anchor
        assertTrue(OfficialRunCache.retainAccepted(fresh, previous.copy(journeyEpochDay = day - 1))
            .savedOfficialClocks.isEmpty())
        assertTrue(OfficialRunCache.retainAccepted(fresh,
            previous.copy(reportedRoute = route.copy(stops = route.stops.map {
                if (it.code == "BDVT") it.copy(departureMin = 1711) else it
            }))).savedOfficialClocks.isEmpty())
        assertFalse("SMET:arr" in merged.savedOfficialClocks)
    }
}
