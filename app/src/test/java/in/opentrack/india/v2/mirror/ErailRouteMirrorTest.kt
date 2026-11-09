package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.ui.LiveViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FULL route comes from erail's running-status page when the device timetable carries only part
 * of the journey (16228: the device has the middle 11 halts SMET→SBC; the run is 27 TLGP→MYS, and the
 * owner searched TLGP→MYS). Parser pinned against the captured page; the merge must keep the device
 * halts' seqs EXACTLY, so the position answer stays valid on the grown route.
 */
class ErailRouteMirrorTest {

    private val page: String by lazy {
        java.io.File("src/test/resources/captures/erail-rstrain-16228-2026-10-12.txt").readText()
    }

    @Test
    fun thePageYieldsAll27HaltsWithCleanNames() {
        val halts = ErailRouteMirror.parse(page)
        assertEquals(27, halts.size)
        assertEquals("Talguppa" to "TLGP", halts.first().name to halts.first().code)
        assertEquals("Mysore Jn" to "MYS", halts.last().name to halts.last().code)
        // No head junk, no "H" halt flags, no nav fragments survive.
        assertTrue(halts.none { it.name.contains("Fare") || it.name.contains("Route") })
        assertTrue(halts.none { it.name.endsWith(" H") })
        // The single-letter code is real: Yeliyur - Y.
        assertEquals("Yeliyur" to "Y", halts.first { it.code == "Y" }.name to "Y")
    }

    @Test
    fun theDeviceRouteSitsContiguousInsideThePageList() {
        val halts = ErailRouteMirror.parse(page)
        val device = listOf("SMET", "SME", "BDVT", "TKE", "RRB", "ASK", "TTR", "TK", "YPR", "MWM", "SBC")
        val merged = merge(halts, device)
        assertNotNull(merged)
        assertEquals(27, merged!!.stops.size)
        assertEquals("TLGP", merged.srcCode)
        assertEquals("MYS", merged.dstCode)
    }

    @Test
    fun deviceHaltsKeepTheirSeqsAndTimesExtensionRowsHaveNeither() {
        val halts = ErailRouteMirror.parse(page)
        val device = listOf("SMET", "SME", "BDVT", "TKE", "RRB", "ASK", "TTR", "TK", "YPR", "MWM", "SBC")
        val base = route(device)
        val merged = merge(halts, device)!!
        // The device's 11 rows survive VERBATIM (seqs 1..11, their minutes, their km).
        base.stops.forEachIndexed { i, s ->
            val kept = merged.stops.first { it.code == s.code }
            assertEquals(s.seq, kept.seq)
            assertEquals(s.departureMin, kept.departureMin)
            assertEquals(s.schedKm, kept.schedKm)
        }
        // Extension rows: no minutes, no km, seqs outside the device band, correct order.
        val pre = merged.stops.take(5)
        assertEquals(listOf("TLGP", "SRF", "ANF", "ARU", "KMSI"), pre.map { it.code })
        assertTrue(pre.all { it.departureMin == null && it.schedKm == -1 })
        assertEquals("MYS", merged.stops.last().code)
        // 27 rows = 5 before (seqs -5..-1) + 11 device (1..11) + 11 after (12..22); device band untouched.
        assertEquals(device.size + 11, merged.stops.last().seq)
    }

    @Test
    fun aPageMissingTheDeviceRouteIsRefusedNotGuessed() {
        val halts = ErailRouteMirror.parse(page).take(3) // TLGP SRF ANF - no device halt among them
        val merged = merge(halts, listOf("SMET", "SME", "BDVT"))
        assertNull("a route the page cannot span must be refused", merged)
    }

    // ---- fixtures --------------------------------------------------------------

    private fun merge(
        halts: List<ErailRouteMirror.Halt>,
        deviceCodes: List<String>,
    ) = LiveViewModel.mergeRouteExtents(route(deviceCodes), halts)

    private fun route(codes: List<String>): TrainRoute = TrainRoute(
        trainNo = "T", trainName = "T", srcCode = codes.first(), srcName = codes.first(),
        dstCode = codes.last(), dstName = codes.last(), totalKm = 274, totalMin = null,
        runDays = null, returnNo = null,
        stops = codes.mapIndexed { i, c ->
            StopRow(
                seq = i + 1, code = c, name = c, state = null, lat = null, lon = null,
                arrivalMin = if (i == 0) null else 600 + i, departureMin = if (i == codes.size - 1) null else 605 + i,
                journeyDay = 0, schedKm = i * 25, isHalt = true,
            )
        },
        segments = codes.zipWithNext().mapIndexed { i, (a, b) -> SegmentRow(i + 1, a, b, 25, 5) },
    )
}

