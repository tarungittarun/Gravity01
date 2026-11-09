package `in`.opentrack.india.remote

import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineCorridorMapTest {
    private val json by lazy {
        RuntimeEnvironment.getApplication().assets.open(OfflineCorridorMap.ASSET)
            .bufferedReader().use { it.readText() }
    }
    // A 29-halt fixture along the old infrastructure, NOT a claimed transcript of
    // today's NTES schedule. The real dated timetable is passed at runtime instead.
    private val codes = listOf("MYS", "NHY", "PANP", "Y", "MYA", "MAD", "CPT", "RMGM",
        "KGI", "SBC", "MWM", "YPR", "TK", "TTR", "ASK", "DRU", "RRB", "TKE",
        "BDVT", "SME", "SMET", "HNHL", "ARU", "ANF", "SRF", "TLGP")
    private fun official(stations: List<String> = codes): TrainRoute {
        val km = JSONObject(json).getJSONArray("points")
        val byCode = (0 until km.length()).associate { i ->
            val row = km.getJSONArray(i)
            row.getString(0) to (row.getInt(2) * 510 / 512)
        }
        val stops = stations.mapIndexed { i, c ->
            StopRow(i + 1, c, c, null, null, null, 900 + i * 8, 901 + i * 8,
                1, byCode[c] ?: i * 15, true)
        }
        return TrainRoute("16227", "Current official timetable", "MYS", "Mysuru", "TLGP",
            "Talguppa", 510, 500, null, null, stops, emptyList())
    }

    @Test fun bundledCorridorHasSourceAndGivesCurrentHaltsTheirOwnSchedule() {
        val result = OfflineCorridorMap.parse(json, official())
        assertNotNull("bundled corridor must be inside the packaged APK", result)
        val map = result!!.route
        val groups = RouteBetween.groupedPoints(map, official())
        assertTrue("YPR–TK must work even when live map services fail", groups["YPR"].orEmpty().size >= 8)
        assertEquals(listOf("HEI", "MLSA", "GBB", "NTR", "SPGR", "AMSA", "BSN",
            "ARGP", "RDI", "BNK"), groups["TK"].orEmpty().map { it.code })
        assertEquals(listOf("SPV", "KRNH"), groups["RRB"].orEmpty().map { it.code })
        assertEquals(emptyList<String>(), groups["MWM"].orEmpty().map { it.code })
        assertTrue("a mapped empty leg must not open an unavailable dialog",
            "MWM" in result.mappedLegStarts)
        assertEquals(codes, map.stops.filter { it.isHalt }.map { it.code })
        assertTrue(map.stops.filterNot { it.isHalt }.all {
            it.actualArrivalMin == null && it.actualDepartureMin == null &&
                it.arrivalMin == null && it.departureMin == null
        })
        assertTrue(groups["TK"].orEmpty().zipWithNext().all { (a, b) -> a.schedKm < b.schedKm })
    }

    @Test fun aChangedJunctionDoesNotSilentlyAdoptAnOldPath() {
        val changed = official(codes.toMutableList().apply { add(indexOf("TK") + 1, "NEWJ") })
        val result = OfflineCorridorMap.parse(json, changed)!!
        assertFalse("changed leg must remain explicitly unmapped", "TK" in result.mappedLegStarts)
        assertFalse("unknown station must not become invented pass-through data",
            "NEWJ" in result.mappedLegStarts)
        assertTrue(RouteBetween.groupedPoints(result.route, changed)["TK"].isNullOrEmpty())
        assertTrue("other independently matched legs must still work", "RRB" in result.mappedLegStarts)
        assertNull(OfflineCorridorMap.parse(json, official().copy(dstCode = "SMET")))
        assertNull(OfflineCorridorMap.parse(json, official().copy(trainNo = "16228")))
        assertNull(OfflineCorridorMap.parse("<html>maintenance</html>", official()))
    }
}
