package `in`.opentrack.india.data

import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import `in`.opentrack.india.App
import `in`.opentrack.india.R
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.ui.Render
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * The shipped timetable, end to end, on the JVM.
 *
 * This is the closest thing to "install the APK and tap through it" that a sandbox without KVM or system
 * images can produce, and it deliberately goes through the *packaged* asset rather than the ETL script: an
 * asset that builds fine but does not survive aapt (a renamed `.gz`, a second root, a schema the app
 * rejects) is a device-only failure, and a device-only failure is the user finding out.
 *
 * The SQLite engine and the SDK level come from `src/test/resources/robolectric.properties`, not an
 * annotation, so this file cannot fail to compile over a Robolectric API detail the sandbox cannot check.
 *
 * What is asserted is what the app promises: the timetable opens, the distances are real, and a search for
 * a station pair returns runs with kilometres attached. The last of these is the regression the user
 * reported — every result read "0 km" because the ETL wrote a zero column that the UI then trusted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShippedDataTest {

    private val app: App get() = RuntimeEnvironment.getApplication() as App

    @Test
    fun theAssetIsReadableUnderExactlyTheNameRefDbAsksFor() {
        val ctx = RuntimeEnvironment.getApplication()
        // aapt rewrites `.gz` assets; `ASSET_NAME` ends in `.dbgz` precisely so that nothing does. If this
        // ever fails, the fix belongs in the name, not in a GZIPInputStream guess at read time.
        ctx.assets.open(RefDb.ASSET_NAME).use { input ->
            val head = ByteArray(6)
            assertEquals("asset shorter than a magic number", 6, input.read(head))
            val gzip = head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()
            val sqlite = String(head, Charsets.ISO_8859_1) == "SQLite"
            assertTrue("asset starts with neither gzip nor an SQLite header: ${head.toList()}", gzip || sqlite)
        }
    }

    @Test
    fun theInstalledTimetableAnswersTheRealQuestion() {
        val repo = runBlocking { app.repository() }
        assertNotNull("RefDb.prepare() could not install the shipped timetable", repo)
        assertEquals(RefDb.State.READY, RefDb.state(app))
        assertTrue("data version must name the build, got '${repo!!.dataVersion()}'",
            repo.dataVersion().isNotBlank())

        val route = runBlocking { repo.route("16228") }
        assertNotNull("16228 (SBC - SMET) is missing from the shipped timetable", route)
        assertDistancesAreReal(route!!)

        val report = runBlocking { repo.search("SMET", "SBC", RailTime.todayEpochDay(), 8 * 60 + 30) }
        assertTrue("no runs returned (${report.reason})", report.hits.isNotEmpty())
        assertTrue(
            "a result with 0 km means the distance column regressed: " +
                report.hits.joinToString { "${it.trainNo}=${it.distanceKm}" },
            report.hits.all { it.distanceKm > 0 },
        )
        assertTrue(
            "boarding must be before alighting on every row",
            report.hits.all { it.alightMinute > it.boardMinute },
        )
        // The whole point of the search: tapping a row has to open a tracking screen, so the codes and the
        // sequences a result carries must agree with the route it points at — one of them being off is what
        // makes the live view describe the wrong station.
        report.hits.take(4).forEach { hit ->
            val r = runBlocking { repo.route(hit.trainNo) }
            assertNotNull("${hit.trainNo} has no route", r)
            val board = r!!.stops.firstOrNull { it.seq == hit.fromSeq }
            val alight = r.stops.firstOrNull { it.seq == hit.toSeq }
            assertNotNull("${hit.trainNo} has no stop with seq ${hit.fromSeq}", board)
            assertEquals("boarding seq does not point at the result's fromCode", hit.fromCode, board!!.code)
            assertEquals("alighting seq does not point at the result's toCode", hit.toCode,
                alight?.code ?: "<none at seq ${hit.toSeq}>")
        }
    }

    /** The user's complaint, written as a test: the km column must be a ladder, not a zero. */
    private fun assertDistancesAreReal(route: TrainRoute) {
        assertTrue("a 950-halt train is possible; this one needs a handful: ${route.stops.size}",
            route.stops.size > 8)
        assertEquals("the first station is 0 km from itself", 0, route.stops.first().schedKm)
        assertEquals("the last stop carries the published total", route.totalKm, route.stops.last().schedKm)
        assertTrue("kilometres must never go backwards",
            route.stops.zipWithNext().all { (a, b) -> b.schedKm >= a.schedKm })
        assertTrue("a route of all-zeros is monotonic too; some stop must be away from the origin",
            route.stops.count { it.schedKm > 0 } > route.stops.size / 2)
    }

    @Test
    fun theTrackingScreenRendersTheShippedRoute() {
        // No "skip if the data is missing" here: a quiet return would be a green test that proved nothing,
        // and a missing asset is exactly what this file exists to catch.
        val repo = runBlocking { app.repository() }
        assertNotNull("the shipped timetable could not be installed", repo)
        val route = runBlocking { repo!!.route("16228") }
        assertNotNull("16228 is missing from the shipped timetable", route)
        val pos = TrainPosition(
            anchorSeq = 0, nextSeq = 1, fraction = 0f, kmAlong = 0f, status = StopStatus.UNKNOWN,
            etaNextMinute = null, delayMinutes = null, remainingMin = null,
            provenance = SourceKind.OFFLINE_SCHEDULE, confidence = TrainPosition.Confidence.LOW,
            ageSeconds = 0,
        )
        val m = TrackModel.build(route!!, pos, emptyMap(), nowRailMinute = 600, boardSeq = 1,
            alightSeq = route.stops.size, runEpochDay = RailTime.todayEpochDay(), note = null)
        val shot = Render.render(R.layout.activity_train, heightDp = 1480f) { root ->
            Render.track(root, m, pos, 600, note = "rendered from the shipped timetable; no live feed")
        }
        val rows = shot.root.findViewById<RecyclerView>(R.id.stops)!!
        @Suppress("UNCHECKED_CAST")
        val adapter = rows.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        assertEquals(route.stops.size, adapter.itemCount)
        assertTrue("the list has no visible station rows", rows.childCount > 0)
        // Every row must still bind the correct distance even though RecyclerView only
        // attaches the viewport's children (especially at the larger font scale).
        val km = (0 until adapter.itemCount).map { i ->
            val holder = adapter.createViewHolder(rows, adapter.getItemViewType(i))
            adapter.bindViewHolder(holder, i)
            Render.read(holder.itemView, R.id.stopMeta)
        }
            .mapNotNull { Regex("""· (\d+) km""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        assertTrue("only ${km.size} of ${route.stops.size} rows printed a distance",
            km.size >= route.stops.size - 1)
        assertTrue("distances out of order on the real route: $km", km.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("every row of a real route prints its distance", route.stops.size, km.size)
        // The user's wording for this requirement was "first station = 0 km, last = destination".
        assertEquals("the origin row must read 0 km", 0, km.first())
        assertEquals("the last row must read the train's total", route.totalKm, km.last())
        Render.save("05-track-shipped-asset", shot.bitmap)
    }

}
