package `in`.opentrack.india.remote

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RailGeoRouteTest {
    private val base = TrainRoute(
        trainNo = "20652", trainName = "Test", srcCode = "AAA", srcName = "Start",
        dstCode = "CCC", dstName = "End", totalKm = 25, totalMin = 120,
        runDays = 127, returnNo = null, stops = listOf(
            StopRow(1, "AAA", "Start", null, null, null, null, 600, 0, 0, true),
            StopRow(2, "BBB", "Middle", null, null, null, 650, 655, 0, 12, true),
            StopRow(3, "CCC", "End", null, null, null, 720, null, 0, 25, true),
        ), segments = emptyList(),
    )

    @Test fun passingPointsNeverBorrowScheduledMinutesOrLiveIdentity() {
        val json = """[{"STN":"AAA"},{"STN":"XYZ"},{"STN":"BBB"},
            {"STN":"Q"},{"STN":"CCC"}]"""
        val route = RailGeoRoute.parse(json, base) { if (it == "XYZ") "Pass one" else null }!!
        assertEquals(listOf("AAA", "XYZ", "BBB", "Q", "CCC"), route.stops.map { it.code })
        assertEquals("Pass one", route.stops[1].name)
        assertEquals(655, route.stops[2].departureMin)
        assertEquals(null, route.stops[1].arrivalMin)
        assertEquals(null, route.stops[1].departureMin)
        assertFalse(route.stops[1].isHalt)
        assertEquals(0, route.segments.size)
    }

    @Test fun namedPassingPointFromOrderedRouteIsNotDroppedWhenOfflineStationDictionaryLacksIt() {
        val json = """[{"STN":"AAA"},{"STN":"YBP","stationName":"Yesvantpur Bypass Running Line"},
            {"STN":"BBB"},{"STN":"Q"},{"STN":"CCC"}]"""
        val route = RailGeoRoute.parse(json, base)!!
        assertEquals("Yesvantpur Bypass Running Line", route.stops[1].name)
        assertEquals(-1, route.stops[1].schedKm) // a name does not invent kilometres
        assertNull(route.stops[1].arrivalMin)
        assertFalse(route.stops[1].isHalt)
    }

    @Test fun mismatchedEndpointsDuplicatesAndOfficialHaltReorderingAreRefused() {
        assertNull(RailGeoRoute.parse("""[{"STN":"AAA"},{"STN":"CCC"},{"STN":"BBB"},
            {"STN":"Q"},{"STN":"CCC"}]""", base))
        assertNull(RailGeoRoute.parse("""[{"STN":"AAA"},{"STN":"Q"},{"STN":"CCC"},
            {"STN":"BBB"}]""", base))
        assertNull(RailGeoRoute.parse("""[{"STN":"AAA"},{"STN":"Q"},
            {"STN":"CCC"},{"STN":"Z"}]""", base))
        assertNull(RailGeoRoute.parse("<html>not a route</html>", base))
    }
}
