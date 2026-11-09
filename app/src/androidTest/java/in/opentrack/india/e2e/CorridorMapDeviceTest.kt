package `in`.opentrack.india.e2e

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.remote.OfflineCorridorMap
import `in`.opentrack.india.ui.StopAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Packaged-asset + real Android rail disclosure integration. Unlike the black-box flow
 * tests, this deliberately constructs a dated-schedule fixture because CI's network
 * cannot promise that NTES will answer. It cannot certify today's actual timetable. */
@RunWith(AndroidJUnit4::class)
class CorridorMapDeviceTest {
    @Test fun bundled16227CorridorRevealsActualStaticStationsOnTheRail() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val json = context.assets.open(OfflineCorridorMap.ASSET).bufferedReader().use { it.readText() }
        val codes = listOf("MYS", "YPR", "TK", "TTR", "ASK", "RRB", "TKE", "TLGP")
        val km = listOf(0, 149, 213, 284, 310, 356, 380, 510)
        val halts = codes.mapIndexed { i, c ->
            StopRow(i + 1, c, c, null, null, null, 900 + i * 40,
                901 + i * 40, 1, km[i], true)
        }
        val dated = TrainRoute("16227", "Fixture only", "MYS", "Mysuru", "TLGP",
            "Talguppa", 510, null, null, null, halts, emptyList())
        val result = OfflineCorridorMap.parse(json, dated)
            ?: throw AssertionError("installed APK lacks a usable historical corridor")
        val mapped = RouteBetween.groupedPoints(result.route, dated)
        assertEquals(10, mapped["TK"].orEmpty().size)
        assertEquals(listOf("SPV", "KRNH"), mapped["RRB"].orEmpty().map { it.code })
        assertTrue(result.mappedLegStarts.contains("TK"))
        assertFalse(mapped["TK"].orEmpty().any {
            it.actualArrivalMin != null || it.actualDepartureMin != null || it.arrivalMin != null
        })
        instrumentation.runOnMainSync {
            val adapter = StopAdapter()
            adapter.mapSource = OfflineCorridorMap.SOURCE
            adapter.mappedLegStarts = result.mappedLegStarts
            adapter.setRoutePoints(mapped)
            var failures = 0
            adapter.onMapUnavailable = { _ -> failures++ }
            val from = TrackModel.Stop(1, "TK", "Tumkur", 1000, 1001,
                1012, 1013, null, TrackModel.MinuteSource.REPORTED, null,
                TrackModel.State.PASSED, true, 213, 0, 71, false, false, false, -50)
            val to = from.copy(seq = 2, code = "TTR", name = "Tiptur",
                kmFromOrigin = 284, source = TrackModel.MinuteSource.SCHEDULE,
                isAnchor = false, showArr = null, showDep = null)
            adapter.submitList(listOf(from, to))
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            adapter.onBindViewHolder(holder, 0)
            assertEquals("Stations ▾", holder.routeToggle.text.toString())
            assertTrue(holder.inlineLeg.contentDescription.contains("HEI"))
            holder.inlineLeg.performClick()
            assertTrue("tap must reveal dots, not an unavailable dialog", holder.inlineLeg.showLabels)
            assertEquals(10, holder.inlineLeg.projection!!.points.size)
            assertTrue(holder.inlineLeg.historicalMap)
            assertEquals(0, failures)
        }
    }
}
