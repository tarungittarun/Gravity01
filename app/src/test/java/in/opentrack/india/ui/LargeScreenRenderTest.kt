package `in`.opentrack.india.ui

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import `in`.opentrack.india.App
import `in`.opentrack.india.R
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainHit
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The same screens, on a wider phone and with the largest system font.
 *
 * Why this is a separate file rather than more cases in [ScreenRenderTest]: the size a screen is painted at
 * comes from Robolectric's `qualifiers`, which are per-class, and every clipping bug found by eye this week
 * ("SBC → SMET · 274 km · …", "SMET · 274 km · you g…") was a *width* problem. Fixing those at 360 dp proves
 * they are fixed at 360 dp, which is the smallest phone this app claims to support and the one place a
 * one-line label survives by luck.
 *
 * 411 × 891 dp is a Pixel 6 / Galaxy S21 class viewport in portrait — the width most of the installed base
 * actually has — and [FONT] is 1.3, the top of AOSP's "Font size" slider (Settings → Display), with the
 *
 * How the big font is produced, and why it is not the way I first wrote it: Robolectric resolves `sp` as `dp`
 * whatever the Configuration says (measured — a 21sp headline came out 42 px at fontScale 1.0 *and* at 1.3),
 * so a Configuration-based font scale is inert here and every "big text" picture in this file would have been
 * a normal render wearing a label. [Render.applyTextScale] multiplies the resolved sizes instead, which is the
 * same arithmetic the framework performs; it does not stretch sp line-spacing or sp padding, so a screen that
 * fails these checks has a real problem, and one that passes has not been tested against the framework's
 * plumbing. [theBigFontRenderIsActuallyRenderingABigFont] fails this file if the factor stops landing.
 *
 * What is asserted is only what a *layout* can be wrong about: no label was cut to an ellipsis at this size,
 * the columns still hold their times, the boarding and alighting notes still read in full, and the screens
 * paint. The facts themselves are pinned in `TrackModelTest` and `ShippedDataTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LargeScreenRenderTest {

    private val app: App get() = RuntimeEnvironment.getApplication() as App

    @Test
    fun theBigFontRenderIsActuallyRenderingABigFont() {
        val text = "Should be past Yesvantpur Jn, 6m into the 32 km leg"
        fun headlineSize(scale: Float): Float {
            val shot = Render.render(R.layout.activity_train, textScale = scale) { root ->
                Render.text(root, R.id.headline, text)
            }
            return (shot.root.findViewById<TextView>(R.id.headline))?.textSize ?: -1f
        }
        val base = headlineSize(1f)
        val big = headlineSize(FONT)
        assertTrue(
            "textScale $FONT produced base $base px vs scaled $big px — the harness stopped scaling, so every " +
                "picture in this file is a default-font render wearing a label",
            base > 0f && Math.abs(big - base * FONT) < 1f,
        )
        // And the scale has to reach a list row, which is a different code path: rows are created while the
        // RecyclerView is measured, long after a fixture's `prepare` block has run. If a view is missed there,
        // the row in the picture is normal-sized while the header is not, and the whole file is a lie about
        // one screen. Compared against the same row at scale 1.0 rather than against a guessed pixel size.
        fun rowTitleSize(scale: Float): Float {
            val shot = Render.render(R.layout.activity_main, textScale = scale) { root ->
                root.findViewById<RecyclerView>(R.id.results)?.apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = TrainHitAdapter { }.apply {
                        submitList(listOf(hit("16999", "SBC SMET EXP", 330, 604, 274, 0x7F)))
                    }
                    Render.settle(this, 1)
                }
            }
            return shot.root.findViewById<RecyclerView>(R.id.results)?.getChildAt(0)
                ?.findViewById<TextView>(R.id.hitName)?.textSize ?: -1f
        }
        val rowBase = rowTitleSize(1f)
        val rowBig = rowTitleSize(FONT)
        assertTrue("a row's title scaled from $rowBase px to $rowBig px, expected $rowBase × $FONT",
            rowBase > 0f && Math.abs(rowBig - rowBase * FONT) < 1f)
    }

    @Test
    fun liveTrackingScreenKeepsEveryWordAt411dpAndTheLargestFont() {
        val route = shippedRoute()
        val anchor = (route.stops.size / 2).coerceAtLeast(1)
        val pos = TrainPosition(
            anchorSeq = anchor, nextSeq = anchor + 1, fraction = 0.4f,
            kmAlong = route.stops[anchor - 1].schedKm.toFloat(), status = StopStatus.DEPARTED,
            etaNextMinute = null, delayMinutes = 14, remainingMin = 95,
            provenance = SourceKind.PROVIDER, confidence = TrainPosition.Confidence.MEDIUM, ageSeconds = 60,
        )
        // Two reported cells, so the live columns hold real minutes rather than "—": a column that clips is
        // only interesting when something is in it.
        // Reported as *clocks*, because that is what the feed sends: scheduled + 12, reduced to a
        // minute-of-day. For an overnight halt that leaves a number far below its own schedule, which is the
        // case `RailTime.alignClock` has to put back on the run's day scale before it is drawn or plotted —
        // a fixture that handed both columns coherent cumulative minutes would have tested none of it.
        fun clock(sched: Int?, shift: Int): Int? = sched?.let { (it + shift) % RailTime.MINUTES_PER_DAY }
        val passed = route.stops[anchor - 1]
        val ahead = route.stops[anchor]
        val per = mapOf(
            passed.code to LiveStopFields(
                actualArrivalMin = clock(passed.arrivalMin ?: passed.departureMin, 12),
                actualDepartureMin = clock(passed.departureMin ?: passed.arrivalMin, 16),
                delayArrivalMin = 12, delayDepartureMin = 14, platform = "3",
            ),
            ahead.code to LiveStopFields(
                actualArrivalMin = clock(ahead.arrivalMin, 14),
                delayArrivalMin = 14, departed = false,
            ),
        )
        // `now` has to sit inside this run: 16228 boards at 22:10, so a clock of 08:00 put the "now" marker
        // twelve hours before the first halt and made the next-stop tag read "in 16h 35m" over a train that
        // leaves in two. The app was right and the picture was nonsense, which is the one thing these previews
        // must not be.
        val m = TrackModel.build(
            route, pos, per, nowRailMinute = NOW, boardSeq = 1, alightSeq = route.stops.size,
            runEpochDay = RailTime.todayEpochDay(), note = null,
        )
        val shot = Render.render(R.layout.activity_train, widthDp = WIDTH, heightDp = 900f, textScale = FONT) {
            Render.track(root = it, m = m, pos = pos, now = NOW)
        }
        assertHeaderIntact(shot)
        val rows = assertTableIntact(shot)
        assertTrue("only $rows stop rows were laid out at ${WIDTH} dp", rows > 4)
        assertTrue("the delay cell is where a big font should have been caught",
            shot.text(R.id.delay).isNotBlank())
        Render.save("06-411dp-bigfont-track-live", shot.bitmap)
    }

    @Test
    fun offlineTrackingScreenKeepsItsHonestLabelsAtTheLargestFont() {
        // The offline strings are the long ones — "OFFLINE · schedule only", "no live feed", "no live fetch" —
        // and they are the ones a traveller has to be able to read: they are the app saying what it does not
        // know. A cut-off "no live f…" would turn a careful sentence into an unreadable one.
        val route = shippedRoute()
        val pos = TrainPosition(
            anchorSeq = 3, nextSeq = 4, fraction = 0.5f, kmAlong = 60f, status = StopStatus.APPROACHING,
            etaNextMinute = null, delayMinutes = null, remainingMin = null,
            provenance = SourceKind.OFFLINE_SCHEDULE, confidence = TrainPosition.Confidence.LOW, ageSeconds = 0,
        )
        val m = TrackModel.build(
            route, pos, emptyMap(), nowRailMinute = NOW, boardSeq = 1, alightSeq = route.stops.size,
            runEpochDay = RailTime.todayEpochDay(), note = null,
        )
        val shot = Render.render(R.layout.activity_train, widthDp = WIDTH, heightDp = 900f, textScale = FONT) {
            Render.track(root = it, m = m, pos = pos, now = NOW)
        }
        assertHeaderIntact(shot)
        assertTableIntact(shot)
        assertEquals("an offline screen must still say it has no feed",
            "no live feed", shot.text(R.id.delay))
        assertEquals("and still refuse to call its own data live",
            "no live fetch", shot.text(R.id.age))
        Render.save("07-411dp-bigfont-track-offline", shot.bitmap)
    }

    @Test
    fun searchResultsKeepATrainNameAtTheLargestFont() {
        // Three names chosen to be the awkward ones: a long pair of places, an ALL-CAPS dump with a station
        // code in it, and a "SPECIAL" whose type must not be cut into "Spec…".
        val hits = listOf(
            hit("16999", "KSR BENGALURU - SHIVAMOGGA TOWN EXP", 330, 604, 274, 0x7F),
            hit("12627", "KARNATAKA EXPRESS", 1140, 1980, 842, 0xFF),
            hit("06268", "SBC SMET Passenger SPECIAL", 486, 810, 274, 0x2A),
        )
        val shot = Render.render(R.layout.activity_main, widthDp = WIDTH, textScale = FONT) { root ->
            root.findViewById<SuggestBox>(R.id.fromBox)?.hint = "Board at — station name or code"
            root.findViewById<SuggestBox>(R.id.toBox)?.hint = "Get off at — station name or code"
            root.findViewById<SuggestBox>(R.id.trainBox)?.hint = "5-digit number or a name fragment"
            root.findViewById<View>(R.id.statusLine)?.visibility = View.GONE
            root.findViewById<View>(R.id.searchCardScroll)?.visibility = View.GONE
            root.findViewById<View>(R.id.searchCompact)?.visibility = View.VISIBLE
            Render.text(root, R.id.dataChip, DATA_VERSION)
            Render.text(root, R.id.resultsTitle, "3 trains between these stations")
            Render.text(root, R.id.resultsSub, "sorted by departure · 5 ms on device")
            root.findViewById<RecyclerView>(R.id.results)?.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = TrainHitAdapter { }.apply {
                    nowMinute = 19949L * 1440L + 300L
                    submitList(hits)
                }
                Render.settle(this, hits.size)
            }
        }
        val list = shot.root.findViewById<RecyclerView>(R.id.results)
        assertNotNull("no results list at ${WIDTH} dp", list)
        assertEquals("a result row was lost to the layout", hits.size, list!!.childCount)
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            listOf(R.id.hitNo, R.id.hitName, R.id.hitMeta, R.id.hitDep, R.id.hitArr, R.id.hitTag, R.id.hitGo)
                .forEach { Render.assertNothingCut(row.findViewById(it), "result $i · ${name(shot, it)}") }
            listOf(R.id.hitNo, R.id.hitName, R.id.hitMeta, R.id.hitDays).forEach {
                assertTrue("result $i has a blank ${name(shot, it)}", Render.read(row, it).isNotBlank())
            }
        }
        val names = (0 until list.childCount).map { Render.read(list.getChildAt(it), R.id.hitName) }
        assertEquals("a train name was cut in half, not wrapped: $names", 3, names.count { it.isNotBlank() })
        assertTrue("the ALL-CAPS name should read as a sentence, not a shout: $names",
            names.any { it.contains("Karnataka Express") })
        assertTrue("a terminal code inside a name must stay a code: $names",
            names.any { it.startsWith("SBC SMET") })
        Render.save("08-411dp-bigfont-search", shot.bitmap)
    }

    @Test
    fun settingsScreenPaintsWithoutCuttingALabelAtTheLargestFont() {
        val shot = Render.render(R.layout.activity_settings, widthDp = WIDTH, textScale = FONT) { root ->
            Render.text(root, R.id.state, "Built-in timetable installed · offline search ready")
            Render.text(root, R.id.about, "TrackTrain 1.0 · timetable: $DATA_VERSION\n\n" +
                "No accounts, no API keys, no ads, no analytics. Live status is fetched directly from a " +
                "public Indian Railways enquiry endpoint; everything else — station search, the ±24 h " +
                "train list and every timetable — is answered here.")
        }
        listOf(R.id.state, R.id.about).forEach { Render.assertNothingCut(shot.root.findViewById(it), name(shot, it)) }
        assertTrue("settings painted only ${shot.distinct()} colours at ${WIDTH} dp", shot.distinct() > 12)
        Render.save("09-411dp-bigfont-settings", shot.bitmap)
    }

    // ------------------------------------------------------------------ shared checks

    /** Every header cell on the tracking screen, measured rather than eyeballed. */
    private fun assertHeaderIntact(shot: Render.Shot) {
        assertEquals("no refresh score should occlude the journey on large-font phones",
            0, shot.context.resources.getIdentifier("checkClock", "id", shot.context.packageName))
        listOf(R.id.trainNo, R.id.routeLabel, R.id.runDate, R.id.headline, R.id.provenance, R.id.age,
            R.id.delay, R.id.next).forEach {
            val value = shot.text(it)
            assertTrue("unfilled or empty label: '${name(shot, it)}' = '$value'",
                value.isNotBlank() && !value.contains("%"))
            Render.assertNothingCut(shot.root.findViewById(it), "${name(shot, it)} = '$value'")
        }
    }

    /** @return the number of rows the list laid out. */
    private fun assertTableIntact(shot: Render.Shot): Int {
        val list = shot.root.findViewById<RecyclerView>(R.id.stops)
        assertNotNull("the stop table did not lay out at ${WIDTH} dp", list)
        for (i in 0 until list!!.childCount) {
            val row = list.getChildAt(i)
            listOf(R.id.stopName, R.id.stopMeta, R.id.schedArr, R.id.schedDep,
                R.id.liveArr, R.id.liveDep)
                .forEach {
                    Render.assertNothingCut(row.findViewById(it), "row $i · ${name(shot, it)}")
                }
            assertTrue("row $i has no station name", Render.read(row, R.id.stopName).isNotBlank())
        }
        return list.childCount
    }

    private fun shippedRoute(): TrainRoute {
        val repo = runBlocking { app.repository() }
        assertNotNull("the shipped timetable could not be installed", repo)
        val route = runBlocking { repo!!.route(SHIPPED_TRAIN) }
        assertNotNull("$SHIPPED_TRAIN is missing from the shipped timetable", route)
        return route!!
    }

    private fun hit(no: String, nm: String, dep: Int, arr: Int, km: Int, days: Int) = TrainHit(
        trainNo = no, trainName = nm, trainType = "SUPERFAST_EXPRESS", originDateEpochDay = 19949L,
        boardMinute = dep, alightMinute = arr, fromCode = "SBC", toCode = "SMET", fromSeq = 1, toSeq = 9,
        distanceKm = km, runDays = days,
    )

    private fun name(shot: Render.Shot, id: Int): String =
        try {
            shot.root.resources.getResourceEntryName(id)
        } catch (e: Exception) {
            "0x" + id.toString(16)
        }

    private companion object {
        /** A Pixel 6 / Galaxy S21 class portrait width. */
        const val WIDTH = 411f

        /** The top of AOSP's "Font size" slider. */
        const val FONT = 1.3f

        /** One real train, so the names, kilometres and stop count are the ones a device would show. */
        const val SHIPPED_TRAIN = "16228"

        /**
         * 00:05 on day 2 of the run — after Birur (23:40) and before Arsikere (00:30 +1d), so the "now" rule
         * falls inside the plotted window and the next-stop tag counts down to a halt that is actually next.
         */
        const val NOW = 1445

        /** `RailRepository.dataVersion()` over the shipped DB — shared with the 360 dp renders. */
        const val DATA_VERSION = "6,775 trains · timetable 10 Oct 2026"
    }
}
