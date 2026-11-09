package `in`.opentrack.india.v2.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field names, day, and the nested placement are from the 14-Oct-2026 CI 20689 capture. */
class ConfirmTktScheduleTest {
    private val day = "14-Oct-2026"
    private val rows = """[{"TrainNo":20689,"StationCode":"YPR","StationName":"Yesvantpur Jn",
        "ArrivalTime":"","DepartureTime":"09:15","Distance":"0.0","Day":1,
        "intermediateStations":[]},
        {"TrainNo":20689,"StationCode":"BAW","StationName":"CHIK BANAVAR",
        "ArrivalTime":"09:25","DepartureTime":"09:26","Distance":"9.0","Day":1,
        "intermediateStations":[{"StationName":"SOLDEVANAHALLI","StationCode":"SDVL",
            "ArrivalTime":"09:28","DepartureTime":"09:28","HaltMinutes":"","Day":1,"Distance":"11.0"}]},
        {"TrainNo":20689,"StationCode":"SMET","StationName":"Shivamogga Town",
        "ArrivalTime":"14:05","DepartureTime":"","Distance":"269.0","Day":1,
        "intermediateStations":[]}]"""
    private fun html(schedule: String = rows) = """<html><title>20689 Train Running Status</title>
        <div>Last Updated: 14 Oct 2026 10:34</div><div>Day 1 14-Oct</div>
        <script>var data={"TrainNo":20689,"Schedule":$schedule};</script></html>"""

    @Test fun capturedPageYieldsScheduledHaltsAndUntimedIntermediatePoints() {
        val result = ConfirmTktSchedule.parse(html(), "20689", day, "YPR", "SMET")!!
        assertEquals(listOf("YPR", "BAW", "SMET"), result.halts.stops.map { it.code })
        assertEquals(9, result.halts.stops[1].schedKm)
        assertEquals(2, result.halts.segments.size)
        assertTrue("halt-to-halt segments must exist; this is not an endpoint scaffold",
            result.halts.segments.first().legMin != null)
        assertEquals(listOf("YPR", "BAW", "SDVL", "SMET"),
            result.allStations.stops.map { it.code })
        val point = result.allStations.stops[2]
        assertFalse(point.isHalt)
        assertNull("embedded passage times are not published halt times", point.arrivalMin)
        assertNull(point.departureMin)
        assertEquals("SOLDEVANAHALLI", point.name)
        assertEquals(11, point.schedKm)
    }

    @Test fun secondCapturedTrainAlsoCarriesPointsWithoutInventingHaltMinutes() {
        val second = """[{"TrainNo":20652,"StationCode":"TLGP","StationName":"Talguppa",
            "ArrivalTime":"","DepartureTime":"05:20","Distance":"0.0","Day":1,
            "intermediateStations":[{"StationCode":"KNLE","StationName":"KANALE",
                "ArrivalTime":"05:26","DepartureTime":"05:26","Distance":"6.0"}]},
            {"TrainNo":20652,"StationCode":"YPR","StationName":"Yesvantpur Jn",
            "ArrivalTime":"11:10","DepartureTime":"11:12","Distance":"366.0","Day":1,
            "intermediateStations":[{"StationCode":"MWM","StationName":"Malleswaram",
                "ArrivalTime":"11:28","DepartureTime":"11:28","Distance":"368.0"}]},
            {"TrainNo":20652,"StationCode":"SBC","StationName":"KSR Bengaluru",
            "ArrivalTime":"12:00","DepartureTime":"","Distance":"371.0","Day":1,
            "intermediateStations":[]}]"""
        val page = html(second).replace("20689", "20652")
        val route = ConfirmTktSchedule.parse(page, "20652", day, "TLGP", "SBC")!!
        assertEquals(listOf("TLGP", "KNLE", "YPR", "MWM", "SBC"),
            route.allStations.stops.map { it.code })
        assertEquals(listOf("TLGP", "YPR", "SBC"), route.halts.stops.map { it.code })
        assertNull(route.allStations.stops[3].arrivalMin)
        assertEquals(368, route.allStations.stops[3].schedKm)
    }

    @Test fun mismatchedTrainDayCorridorAndDuplicatePointsNeverBecomeARoute() {
        assertNull(ConfirmTktSchedule.parse(html(), "20652", day, "YPR", "SMET"))
        assertNull(ConfirmTktSchedule.parse(html(), "20689", "13-Oct-2026", "YPR", "SMET"))
        assertNull(ConfirmTktSchedule.parse(html(), "20689", "14-Oct-2025", "YPR", "SMET"))
        assertNull(ConfirmTktSchedule.parse(html(), "20689", day, "SMET", "YPR"))
        assertNull(ConfirmTktSchedule.parse(html(rows.replace("\"SDVL\"", "\"YPR\"")),
            "20689", day, "YPR", "SMET"))
        assertTrue(ConfirmTktSchedule.parse(html(), "20689", day, "YPR", "SDVL") != null)
    }
}
