package `in`.opentrack.india.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainHit
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screens, painted — plus assertions that only hold if what was painted is legible.
 *
 * Why this exists: the one thing CI could never check is how the app *looks*, and that is exactly what
 * went wrong between "the data is right" and "the app is usable". No emulator is possible in this sandbox
 * (no KVM, no system images), so Robolectric runs the real LayoutInflater, the real styles and the real
 * `onDraw`, and these tests assert what a reviewer would check by eye: something was painted, the chart
 * contains the live polyline rather than an empty frame, every row carries a station name and a pair of
 * times, no string is still a `%1$s` template, and no headline was ellipsised away.
 *
 * Scope, honestly: this mirrors the bindings [TrainActivity] and [MainActivity] make. It is a render
 * harness for the layouts, styles and custom views; the *facts* on those screens are pinned by
 * [in.opentrack.india.domain.TrackModelTest], and the Activity's wiring is thin by construction.
 *
 * The route is invented — names, codes, times and kilometres are made up — so the render does not depend
 * on the shipped timetable. Only its shape matters: nine stops, an anchor in the middle, a delay that
 * carries forward from there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderTest {

    private companion object {
        /**
         * [in.opentrack.india.data.RailRepository.dataVersion] over the shipped reference DB. Every screen
         * that quotes the size of the timetable uses this one string, so the previews cannot disagree.
         */
        const val DATA_VERSION = "6,775 trains · timetable 10 Oct 2026"
    }

    private fun stop(seq: Int, code: String, name: String, arr: Int?, dep: Int?, km: Int) = StopRow(
        seq = seq, code = code, name = name, state = "KR", lat = null, lon = null,
        arrivalMin = arr, departureMin = dep, journeyDay = 1, schedKm = km, isHalt = true,
    )

    private val stops = listOf(
        stop(1, "SBC", "KSR BENGALURU", null, 330, 0),
        stop(2, "YPA", "YESVANTPUR JN", 352, 354, 12),
        stop(3, "HSQ", "HOSAPETE ROAD", 391, 393, 44),
        stop(4, "DKPT", "DOBETAKAPUR", 415, 417, 61),
        stop(5, "TMLL", "TUMAKURU", 452, 462, 107),
        stop(6, "TKN", "TALAKORU", 498, 500, 138),
        stop(7, "KDDA", "KADUR", 546, 551, 205),
        stop(8, "HKK", "HOSAHOSUR", 583, 585, 241),
        stop(9, "SMET", "SHIVAMOGGA TOWN", 604, null, 274),
    )

    private val route = TrainRoute(
        trainNo = "16999", trainName = "KSR BENGALURU - SHIVAMOGGA TOWN EXP",
        srcCode = "SBC", srcName = "KSR BENGALURU", dstCode = "SMET", dstName = "SHIVAMOGGA TOWN",
        totalKm = 274, totalMin = 274, runDays = 0x7F, returnNo = "16998",
        stops = stops,
        segments = stops.zipWithNext().mapIndexed { i, (a, b) ->
            SegmentRow(
                i + 1, a.code, b.code, b.schedKm - a.schedKm,
                (b.arrivalMin ?: b.departureMin ?: 0) - (a.departureMin ?: a.arrivalMin ?: 0),
            )
        },
    )

    private fun pos(anchor: Int, status: StopStatus, delay: Int?, live: Boolean) = TrainPosition(
        anchorSeq = anchor, nextSeq = anchor + 1, fraction = 0.4f, kmAlong = 60f, status = status,
        etaNextMinute = null, delayMinutes = delay, remainingMin = null,
        provenance = if (live) SourceKind.PROVIDER else SourceKind.OFFLINE_SCHEDULE,
        confidence = TrainPosition.Confidence.MEDIUM, ageSeconds = 95,
    )

    /** Reported minutes for the three stops behind us, so the table shows scheduled against actual. */
    private val perStop = mapOf(
        "YPA" to LiveStopFields(actualArrivalMin = 355, actualDepartureMin = 358, platform = "2"),
        "HSQ" to LiveStopFields(actualArrivalMin = 401, actualDepartureMin = 404, delayArrivalMin = 10),
        "DKPT" to LiveStopFields(
            actualArrivalMin = 429, actualDepartureMin = 431, delayDepartureMin = 14, departed = true
        ),
    )

    private fun model(pos: TrainPosition, now: Int, per: Map<String, LiveStopFields>) =
        TrackModel.build(route, pos, per, now, boardSeq = 1, alightSeq = 9, runEpochDay = 19949L, note = null)

    // ------------------------------------------------------------------ the tracking screen

    @Test
    fun keyboardEmptySearchUsesTheWholeVisibleCardNotAnEmptyListStrip() {
        // Simulate the adjustResize viewport after the IME consumes 280dp.
        val shot = Render.render(R.layout.activity_main, widthDp = 393f,
            heightDp = 535f, textScale = 1.15f) { root ->
            root.findViewById<View>(R.id.statusLine).visibility = View.GONE
            root.findViewById<View>(R.id.results).visibility = View.GONE
            root.findViewById<BoundedSearchScroll>(R.id.searchCardScroll).isFillViewport = true
            root.findViewById<SuggestBox>(R.id.toBox).text = "SMET"
        }
        val card = shot.bounds(R.id.searchCard)
        val viewport = shot.bounds(R.id.searchCardScroll)
        assertTrue("empty list left a bare strip above IME: $card / $viewport",
            card.bottom >= viewport.bottom - Render.dp(shot.context, 2f))
        assertTrue("the focused station input vanished on the reduced viewport",
            shot.bounds(R.id.toBox).top < viewport.bottom)
    }

    @Test
    fun editedSearchKeepsSummaryCloseAndSortInRightHalf() {
        val shot = Render.render(R.layout.activity_main) { root ->
            val scroll = root.findViewById<BoundedSearchScroll>(R.id.searchCardScroll)
            (scroll.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                weight = 0f
                scroll.layoutParams = this
            }
            scroll.capWhenEditingResults = true
            root.findViewById<View>(R.id.statusLine).visibility = View.GONE
            root.findViewById<View>(R.id.resultsSummary).visibility = View.VISIBLE
            root.findViewById<View>(R.id.resultsHead).visibility = View.VISIBLE
            root.findViewById<View>(R.id.resultsSortRow).visibility = View.VISIBLE
            Render.text(root, R.id.resultsSummary,
                "3 trains · erail + timetable · source data as of 17 Oct 2026, 19:01 IST")
            Render.text(root, R.id.resultsTitle, "SEARCHED TRAINS")
            val sort = root.findViewById<android.widget.Spinner>(R.id.resultsSort)
            sort.adapter = android.widget.ArrayAdapter(root.context, R.layout.item_sort_selected,
                listOf("Suggested", "Departure", "Arrival", "Duration"))
            val hits = listOf(
                hit("16999", "KSR BENGALURU - SHIVAMOGGA TOWN EXP", 330, 604, 274, 0x7F),
                hit("16536", "PALEM G EXP", 396, 690, 311, 0x55),
                hit("06268", "SBC SMET Passenger SPECIAL", 486, 810, 274, 0x2A))
            root.findViewById<RecyclerView>(R.id.results).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = TrainHitAdapter { }.apply { submitList(hits) }
                Render.settle(this, hits.size)
            }
        }
        val card = shot.bounds(R.id.searchCard)
        val summary = shot.bounds(R.id.resultsSummary)
        val title = shot.bounds(R.id.resultsTitle)
        val sort = shot.bounds(R.id.resultsSort)
        assertTrue("edited form still leaves a large empty list gap: $card / $summary",
            summary.top - card.bottom in 0..(shot.bitmap.width / 5))
        assertTrue("result summary missing", shot.text(R.id.resultsSummary).contains("source data as of"))
        val list = shot.bounds(R.id.results)
        assertTrue("populated result list should start close to the edited search, not below a weighted blank: $card / $list",
            list.top - card.bottom in 0..(shot.bitmap.width / 3))
        assertTrue("list must retain room for real rows after reopening search: $list",
            list.height() >= Render.dp(shot.context, 100f))
        assertEquals("fixture lost populated rows", 3,
            shot.root.findViewById<RecyclerView>(R.id.results).adapter!!.itemCount)
        assertTrue("sort overlaps heading or spills beyond right half: $title / $sort",
            sort.left >= title.right && sort.right <= shot.bounds(R.id.resultsHead).right)
        assertTrue("sort arrow not painted", shot.root.findViewById<View>(R.id.sortArrow).width > 0)
        assertEquals("sort selector should take less height than before", Render.dp(shot.context, 40f).toInt(),
            sort.height())
        val editButton = shot.root.findViewById<android.widget.Button>(R.id.editSearch)
        assertEquals("Edit search should be blue, distinct from the green Find action",
            shot.context.getColor(R.color.live), editButton.currentTextColor)
        assertTrue("Edit search lost its blue surface", editButton.background != null)
        Render.save("r130-populated-home-search", shot.bitmap)
    }

    @Test
    fun trackingSummaryAndRunDateAreDistinctReadableLabels() {
        val shot = Render.render(R.layout.activity_train) { root ->
            Render.text(root, R.id.runDate, "run of 17 Oct 2026")
        }
        val date = shot.root.findViewById<android.widget.TextView>(R.id.runDate)
        assertNotNull("run of date lost its box", date.background)
        assertTrue("run date not bold", date.typeface.isBold)
        assertTrue("run date too small", date.textSize >
            shot.root.findViewById<android.widget.TextView>(R.id.routeLabel).textSize)
        assertEquals("SUMMARY BOX", shot.text(R.id.summaryBoxLabel))
    }

    @Test
    fun livePositionPaintsHeadlineChartAndEveryRow() {
        val p = pos(anchor = 4, status = StopStatus.DEPARTED, delay = 14, live = true)
        val m = model(p, now = 432, per = perStop)
        val shot = Render.render(R.layout.activity_train) { root -> Render.track(root, m, p, 432) }

        // Nothing the Activity is supposed to write may still be a format template or blank.
        val headline = shot.text(R.id.headline)
        val strings = listOf(
            headline, shot.text(R.id.provenance), shot.text(R.id.age), shot.text(R.id.delay),
            shot.text(R.id.next), shot.text(R.id.trainNo),
            shot.text(R.id.routeLabel), shot.text(R.id.runDate),
        )
        strings.forEach { assertTrue("unfilled or empty label: '$it'", it.isNotBlank() && !it.contains("%")) }
        assertTrue("headline must name the last departure without claiming current location: '$headline'",
            headline.startsWith("Last NTES report: Departed ") &&
                headline.endsWith(" · now unknown"))
        // The fixture's stop names are ALL CAPS, as the upstream dump writes them. A sentence that carries
        // them verbatim ("Between DOBETAKAPUR and TUMAKURU") sits above a table that prints "Dobetakapur",
        // and two casings on one screen is how an app starts to look untrustworthy.
        assertTrue("the headline should follow the table's casing: '$headline'",
            headline.contains("Dobetakapur") && !headline.contains("DOBETAKAPUR"))
        assertEquals("a completed check must never draw a score on top of the journey",
            0, shot.context.resources.getIdentifier("checkClock", "id", shot.context.packageName))
        assertTrue("ripple must overlay ONLY the status table viewport",
            shot.root.findViewById<View>(R.id.statusRipple).parent ===
                shot.root.findViewById<View>(R.id.routeViewport))
        assertEquals(m.trainNo, shot.text(R.id.trainNo))
        assertNotClipped(shot.root.findViewById(R.id.headline), "headline")
        // The header line is where the app used to lose a word to an ellipsis; the fix is in the layout, so
        // it can only be checked on a laid-out screen.
        assertNotClipped(shot.root.findViewById(R.id.routeLabel), "routeLabel ('${shot.text(R.id.routeLabel)}')")

        val summary = shot.root.findViewById<android.widget.ScrollView>(R.id.scroller)
        val topGap = shot.bounds(R.id.heroEvidenceSlot).top -
            shot.bounds(R.id.summaryBoxLabel).bottom
        assertTrue("an invisible connection warning left $topGap px of empty space above the report",
            topGap in 0..Render.dp(shot.context, 39f).toInt())
        assertTrue("a warning must live with the evidence without replacing its badge",
            shot.root.findViewById<View>(R.id.connectionAlert).parent ===
                shot.root.findViewById<View>(R.id.heroEvidenceSlot) &&
                shot.root.findViewById<View>(R.id.heroEvidence).parent ===
                    shot.root.findViewById<View>(R.id.heroEvidenceSlot))
        assertTrue("expanding source text must scroll without clipping source/Auto controls",
            shot.root.findViewById<View>(R.id.heroEvidenceSlot).parent is android.widget.LinearLayout &&
                shot.root.findViewById<View>(R.id.heroDetailsScroll) is android.widget.ScrollView &&
                shot.root.findViewById<View>(R.id.sourceSpinner).parent !==
                shot.root.findViewById<View>(R.id.heroDetailsScroll))
        val wasted = summary.height - summary.getChildAt(0).height
        assertTrue("summary reserved $wasted px of blank space before the route",
            wasted <= (16 * shot.context.resources.displayMetrics.density).toInt())
        assertTrue("opt-in one-minute Auto must stay in the source row",
            shot.root.findViewById<View>(R.id.autoSwitch).parent ===
                shot.root.findViewById<View>(R.id.sourceSpinner).parent)
        val refresh = shot.root.findViewById<View>(R.id.refresh)
        assertTrue("Refresh must park above the separate, clock-free bottom dock",
            refresh.parent === shot.root.findViewById<View>(R.id.contentColumn).parent &&
                refresh.height > 0 &&
                shot.bounds(R.id.refresh).top >= shot.bounds(R.id.routeViewport).bottom &&
                shot.bounds(R.id.refresh).top >= shot.bounds(R.id.refreshDock).top)
        assertTrue("Less must retain a readable report headline",
            shot.root.findViewById<android.widget.TextView>(R.id.compactReport).maxLines >= 2)
        assertEquals("the separate route button must not exist", 0,
            shot.context.resources.getIdentifier("betweenStations", "id", shot.context.packageName))
        assertTrue("source dropdown must be visible",
            shot.root.findViewById<View>(R.id.sourceSpinner).height > 0)
        val sourcePicker = shot.root.findViewById<View>(R.id.sourceSpinner)
        assertEquals("About time button must be removed", 0,
            shot.context.resources.getIdentifier("sourceToggle", "id", shot.context.packageName))
        assertTrue("source picker must precede right-aligned Auto without overlap",
            shot.bounds(R.id.sourceSpinner).right <= shot.bounds(R.id.autoSwitch).left &&
                shot.bounds(R.id.autoSwitch).right >= shot.bounds(R.id.scroller).right -
                    35 * shot.context.resources.displayMetrics.density)
        assertEquals("source selector saved vertical space", Render.dp(shot.context, 40f).toInt(),
            sourcePicker.height)
        assertTrue("refresh must contain an independently rotating icon, not text",
            shot.root.findViewById<View>(R.id.refreshGlyph) is android.widget.ImageView &&
                shot.root.findViewById<View>(R.id.refreshGlyph).parent ===
                    shot.root.findViewById<View>(R.id.refresh))
        assertTrue("picker must remain long-pressable for source guidance",
            sourcePicker.height > 0)
        assertEquals("Refresh needs a 48dp touch target", 48,
            (shot.root.findViewById<View>(R.id.refresh).height /
                shot.context.resources.displayMetrics.density).toInt())
        val floating = shot.bounds(R.id.refresh)
        val full = shot.bounds(R.id.routeViewport)
        assertTrue("Refresh must never cover ANY station's source-time column: $floating / $full",
            floating.top >= full.bottom && floating.centerX() > shot.root.width * 3 / 4 &&
                floating.right < shot.root.width && floating.bottom <= shot.root.height)
        assertEquals("every stop must be in the list", 9, Render.rowCount(shot.root))
        val list = shot.root.findViewById<RecyclerView>(R.id.stops)!!
        assertEquals("TIMETABLE", shot.text(R.id.routeHeadTimetable))
        assertEquals("STATIONS", shot.text(R.id.routeHeadStations))
        assertEquals("ACTUAL", shot.text(R.id.routeHeadActual))
        val actualHeading = shot.bounds(R.id.routeHeadActual)
        assertTrue("ACTUAL header must occupy the visible right column: $actualHeading",
            actualHeading.width() >= Render.dp(shot.context, 55f) &&
                actualHeading.height() > 0 && actualHeading.right <= shot.bitmap.width)
        assertTrue("ACTUAL heading is present in XML but was not PAINTED on screen: $actualHeading",
            shot.countWith(shot.context.getColor(R.color.live), actualHeading) > 3)
        assertTrue("header must be outside the scrolling stop list",
            shot.root.findViewById<View>(R.id.routeColumnHeader).parent !== list)
        fun right(id: Int) = shot.bounds(id).right
        assertTrue("timetable heading must align with the left edge of scheduled clocks",
            kotlin.math.abs(shot.bounds(R.id.routeHeadTimetable).left -
                shot.bounds(R.id.schedArr).left) <= 2)
        val dp = shot.context.resources.displayMetrics.density
        assertTrue("timetable must have visible border inset",
            shot.bounds(R.id.routeHeadTimetable).left -
                shot.bounds(R.id.routeColumnHeader).left >= 7 * dp)
        assertTrue("actuals must have visible border inset",
            shot.bounds(R.id.routeColumnHeader).right -
                shot.bounds(R.id.routeHeadActual).right >= 19 * dp)
        assertTrue("timetable clock must sit LEFT of the rail, not float next to it",
            shot.bounds(R.id.schedArr).right < shot.bounds(R.id.stopTrainMarker).left)
        assertTrue("station heading must align with station names",
            kotlin.math.abs(shot.bounds(R.id.routeHeadStations).left -
                shot.bounds(R.id.stationIdentity).left) <= 2)
        assertTrue("actual heading must align with right-hand clocks",
            kotlin.math.abs(right(R.id.routeHeadActual) - right(R.id.reportTimes)) <= 2)
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            assertTrue("row $i has no station name", text(row, R.id.stopName).isNotBlank())
            listOf(R.id.schedArr, R.id.schedDep, R.id.liveArr, R.id.liveDep).forEach { id ->
                assertTrue("row $i is missing a time column", text(row, id).isNotBlank())
            }
            // Diagonal arrows are painted as bold gradient drawables with a lit blue tip;
            // the clock remains plain text. No stop tap is required to read either pair.
            listOf(R.id.schedArr, R.id.schedDep).forEach { id ->
                val arrow = row.findViewById<TextView>(id).compoundDrawables[0]
                assertTrue("row $i lacks a painted direction cue", arrow is DirectionArrowDrawable)
                assertEquals("row $i cue points the wrong way", id == R.id.schedArr,
                    (arrow as DirectionArrowDrawable).arriving)
                assertTrue("row $i schedule is not a plain clock", text(row, id).matches(
                    Regex("""(?:—|START|END|\d{2}:\d{2})""")))
            }
            // The complaint this pins: an offline screen printed the schedule twice, and the right-hand copy
            // read like a report about the train. A row nothing moved must show a dash, not a second "05:52".
            if (m.rows[i].source == TrackModel.MinuteSource.SCHEDULE) {
                listOf(R.id.liveArr, R.id.liveDep).forEach { id ->
                    assertEquals("a timetable-only row must not look reported in ${name(shot, id)}",
                        when {
                            i == 0 && id == R.id.liveArr && m.rows[i].schedArr == null -> "START"
                            i == list.childCount - 1 && id == R.id.liveDep && m.rows[i].schedDep == null -> "END"
                            else -> "—"
                        }, text(row, id))
                }
            }
            // A station owns its clocks. The previous two-tier layout passed all
            // content assertions while its arrival/departure sat ~50-80dp below
            // the name and dot on the owner's 360dp phone.
            val stop = row as android.view.ViewGroup
            fun cell(id: Int): android.graphics.Rect {
                val view = row.findViewById<View>(id)
                return android.graphics.Rect(0, 0, view.width, view.height).also {
                    stop.offsetDescendantRectToMyCoords(view, it)
                }
            }
            val nameBox = cell(R.id.stopName)
            val plannedArr = cell(R.id.schedArr)
            val plannedDep = cell(R.id.schedDep)
            val sourceArr = cell(R.id.liveArr)
            val sourceDep = cell(R.id.liveDep)
            val density = shot.context.resources.displayMetrics.density
            assertTrue("row $i scheduled arrival detached from station: $nameBox / $plannedArr",
                kotlin.math.abs(plannedArr.top - nameBox.top) <= 24 * density)
            assertTrue("row $i source arrival detached from station: $nameBox / $sourceArr",
                kotlin.math.abs(sourceArr.top - nameBox.top) <= 24 * density)
            assertTrue("row $i arrival clocks not level: $plannedArr / $sourceArr",
                kotlin.math.abs(plannedArr.top - sourceArr.top) <= 6 * density)
            assertTrue("row $i departure clocks not level: $plannedDep / $sourceDep",
                kotlin.math.abs(plannedDep.top - sourceDep.top) <= 8 * density)
            assertTrue("row $i station cannot fit between dot and report: $nameBox / $sourceArr",
                nameBox.width() >= 90 * density && nameBox.right <= sourceArr.left)
            assertTrue("row $i timetable crossed its station rail: $plannedArr / $nameBox",
                plannedArr.right < nameBox.left)
            assertTrue("row $i clock escaped its own stop: $plannedDep / $sourceDep / $stop",
                plannedDep.bottom <= stop.findViewById<View>(R.id.stopRow).bottom &&
                    sourceDep.bottom <= stop.findViewById<View>(R.id.stopRow).bottom)
            assertTrue("row $i is missing its distance/platform line", text(row, R.id.stopMeta).isNotBlank())
            // No row may have its name or its "code · km · note" line cut with an ellipsis: this is where a
            // traveller reads the boarding and alighting marks, and "…you g…" was on a shipping screen.
            row.findViewById<TextView>(R.id.stopName)?.let { assertNotClipped(it, "row $i stopName") }
            row.findViewById<TextView>(R.id.stopMeta)?.let { assertNotClipped(it, "row $i stopMeta") }
        }
        // The distance ladder is what the user asked for: the first row reads 0 km, the last one is the
        // train's total, and the middle rows must therefore be strictly increasing.
        val km = (0 until list.childCount).map { text(list.getChildAt(it), R.id.stopMeta) }
            .mapNotNull { Regex("""· (\d+) km""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        assertEquals("every row prints its distance, the 0 km origin included", list.childCount, km.size)
        assertTrue("distances must increase along the route: $km", km.zipWithNext().all { (a, b) -> b > a })

Render.save("02-track-live", shot.bitmap)
    }

    @Test
    fun lastReportedHaltMarkerRendersInsideTheTableNotOnScheduledRows() {
        val p = pos(anchor = 4, status = StopStatus.DEPARTED, delay = 14, live = true)
        val m = model(p, now = 432, per = perStop)
        val now = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(19949L, 440)
        val marker = TableMarker.atHalt(m.rows, "YPA", 19949L, now)
        assertNotNull("reported halt did not earn a table marker", marker)
        val shot = Render.render(R.layout.activity_train) { root ->
            Render.track(root, m, p, 432)
            val list = root.findViewById<RecyclerView>(R.id.stops)
            (list.adapter as StopAdapter).apply {
                haltMarker = marker
                legAgeSeconds = 120
            }
        }
        val list = shot.root.findViewById<RecyclerView>(R.id.stops)
        val reported = list.getChildAt(1)
        assertEquals("duplicate reported-stop banner should stay hidden", View.GONE,
            reported.findViewById<View>(R.id.haltMarker).visibility)
        assertTrue("passage age disappeared from its accessible marker",
            reported.findViewById<TextView>(R.id.haltMarker).contentDescription.contains("ago"))
        assertEquals("the stop's age must sit below its locomotive",
            "2m ago", text(reported, R.id.stopMarkerAge))
        val stopGroup = reported as android.view.ViewGroup
        fun localRect(id: Int) = android.graphics.Rect().also { rect ->
            val cell = reported.findViewById<View>(id)
            rect.set(0, 0, cell.width, cell.height)
            stopGroup.offsetDescendantRectToMyCoords(cell, rect)
        }
        val markerBounds = localRect(R.id.stopTrainMarker)
        val ageBounds = localRect(R.id.stopMarkerAge)
        assertTrue("age overlaps the train logo", ageBounds.top >= markerBounds.bottom -
            3 * shot.context.resources.displayMetrics.density)
        assertEquals("locomotive must replace the reported stop's dot",
            View.VISIBLE, reported.findViewById<View>(R.id.stopTrainMarker).visibility)
        assertEquals(View.GONE, reported.findViewById<View>(R.id.stopDot).visibility)
        assertEquals("a future stop cannot acquire a locomotive", View.GONE,
            list.getChildAt(2).findViewById<View>(R.id.stopTrainMarker).visibility)
        assertEquals(View.GONE, list.getChildAt(2).findViewById<View>(R.id.haltMarker).visibility)
    }

    @Test
    fun sourceReportedLocomotiveAndStaticPointPaintWithoutImpliedMotion() {
        val p = pos(anchor = 4, status = StopStatus.DEPARTED, delay = 14, live = true)
        val m = model(p, now = 432, per = perStop)
        val static = route.copy(stops = route.stops.take(4) +
            stop(5, "NMP", "STATIC POINT", null, null, 80).copy(isHalt = false) +
            route.stops.drop(4))
        val leg = RouteBetween.reportedHaltLeg(route, static, "DKPT")!!
        assertEquals(0f, leg.fraction, .001f)
        assertEquals(19f / 46f, leg.points.single().fraction, .001f)
        val shot = Render.render(R.layout.activity_train) { root ->
            Render.track(root, m, p, 432)
            val list = root.findViewById<RecyclerView>(R.id.stops)
            (list.adapter as StopAdapter).apply {
                sourceNameForMarker = "NTES"
                legAgeSeconds = 12 * 60L
                setProjectedLeg(leg)
            }
        }
        val list = shot.root.findViewById<RecyclerView>(R.id.stops)
        val reported = list.getChildAt(3)
        val icon = reported.findViewById<InlineLegView>(R.id.inlineLeg)
        assertEquals(View.VISIBLE, reported.findViewById<View>(R.id.inlineLegGroup).visibility)
        assertTrue(icon.contentDescription.contains("Static Point (NMP)"))
        assertEquals("duplicate leg caption should stay hidden", View.GONE,
            reported.findViewById<View>(R.id.inlineLegCaption).visibility)
        assertTrue("distance ruler must shrink without crushing the readable marker: ${icon.height}px",
            icon.height >= (54 * shot.context.resources.displayMetrics.density).toInt() &&
                icon.height <= (84 * shot.context.resources.displayMetrics.density).toInt())
        assertTrue(icon.contentDescription.contains("no measured distance since"))
        assertEquals("12 min ago", text(reported, R.id.inlineLegAge))
        assertEquals(12 * 60L, icon.ageSeconds)
        Render.save("07-track-source-reported-halt", shot.bitmap)
    }

    @Test
    fun trackingHasUnboxedBrandAndOnlyTheRefreshGlyphSpins() {
        val shot = Render.render(R.layout.activity_train, widthDp = 411f,
            heightDp = 760f, textScale = 1.15f)
        val button = shot.root.findViewById<View>(R.id.refresh)
        val glyph = shot.root.findViewById<View>(R.id.refreshGlyph)
        assertTrue("refresh target shrank", button.width >= Render.dp(shot.context, 48f))
        assertTrue("refresh icon cannot rotate independently", glyph.parent === button)
        assertTrue("locomotive/title is no longer a usable home target",
            shot.root.findViewById<View>(R.id.back).width >= Render.dp(shot.context, 180f))
        assertEquals("the response score must not cover the readable summary text",
            0, shot.context.resources.getIdentifier("checkClock", "id", shot.context.packageName))
        val headline = shot.root.findViewById<TextView>(R.id.headline)
        assertTrue("summary headline is too small for a phone", headline.textSize >=
            Render.dp(shot.context, 23f))
        Render.assertNothingCut(headline, "larger journey headline")
    }

    @Test
    fun twoSecondReceiptFloatsOverCompactCardWithoutMeasuringTheRoute() {
        fun paint(showReceipt: Boolean) = Render.render(R.layout.activity_train,
            widthDp = 393f, heightDp = 785f, textScale = 1.15f) { root ->
            root.findViewById<View>(R.id.heroDetailsScroll).visibility = View.GONE
            root.findViewById<TextView>(R.id.heroToggle).text = "More  ▾"
            if (showReceipt) {
                root.findViewById<TextView>(R.id.receiptTook).text = "Took 1.2 s"
                root.findViewById<TextView>(R.id.receiptChecked).text =
                    "Checked 17:29:05 · website · no newer qualified stop"
                root.findViewById<TextView>(R.id.receiptFreshness).text =
                    "Freshness · last website stop 2m ago · current position unknown"
                root.findViewById<View>(R.id.refreshReceiptOverlay).visibility = View.VISIBLE
            }
        }
        val baseline = paint(false)
        val overlaid = paint(true)
        assertEquals("receipt grew compact summary shell", baseline.bounds(R.id.summaryShell),
            overlaid.bounds(R.id.summaryShell))
        assertEquals("receipt moved the station headings", baseline.bounds(R.id.routeColumnHeader),
            overlaid.bounds(R.id.routeColumnHeader))
        assertEquals("receipt moved the pinned Source/Auto controls",
            baseline.bounds(R.id.sourceControls), overlaid.bounds(R.id.sourceControls))
        assertEquals("overlay is not a sibling that pushes the table", overlaid.bounds(R.id.summaryShell),
            overlaid.bounds(R.id.refreshReceiptOverlay))
        assertTrue("receipt must scroll inside the card, not spill over source controls",
            overlaid.bounds(R.id.receiptScroll).bottom <=
                overlaid.bounds(R.id.refreshReceiptOverlay).bottom)
        assertTrue("missing three visible receipt lines",
            listOf(R.id.receiptTook, R.id.receiptChecked, R.id.receiptFreshness)
                .all { overlaid.bounds(it).height() > 0 })
    }

    @Test
    fun screenshotSizedWebsiteReportCannotPushSourcePickerUnderTheCard() {
        // 20652, 28 Oct at 10:09: the R157 report's long following-halt
        // sentence put Source/Auto below the top bounded viewport on the phone.
        // Include a genuinely overflowing card, not just a short default text.
        for (expanded in listOf(true, false)) {
            val shot = Render.render(R.layout.activity_train, widthDp = 393f,
                heightDp = 760f, textScale = 1.15f) { root ->
                root.findViewById<TextView>(R.id.provenance).text =
                    "WEBSITE · past-card report · run inferred"
                root.findViewById<TextView>(R.id.age).text = "Site arrival · 2 min ago"
                root.findViewById<TextView>(R.id.headline).text =
                    "Last mapped website card · Bhadravati 10:07"
                root.findViewById<TextView>(R.id.delay).text = "+159m late · 10:07"
                root.findViewById<TextView>(R.id.next).text =
                    "Tarikere Jn\nTimetable 07:47 · past clock"
                root.findViewById<View>(R.id.trackingSummaryCard).layoutParams =
                    root.findViewById<View>(R.id.trackingSummaryCard).layoutParams.apply {
                        height = Render.dp(root.context, if (expanded) 530f else 145f).toInt()
                    }
                if (!expanded) {
                    root.findViewById<View>(R.id.heroDetailsScroll).visibility = View.GONE
                    root.findViewById<TextView>(R.id.compactReport).apply {
                        text = "Website last card · Bhadravati 10:07"
                        visibility = View.VISIBLE
                    }
                }
            }
            val scroller = shot.bounds(R.id.scroller)
            val controls = shot.bounds(R.id.sourceControls)
            val picker = shot.bounds(R.id.sourceSpinner)
            val auto = shot.bounds(R.id.autoSwitch)
            assertTrue("source controls scrolled/clipped with summary ($expanded): $controls / $scroller",
                shot.root.findViewById<View>(R.id.sourceControls).parent ===
                    shot.root.findViewById<View>(R.id.contentColumn) &&
                    controls.top >= scroller.bottom && controls.bottom <=
                        shot.bounds(R.id.routeColumnHeader).top)
            assertTrue("source picker not fully painted ($expanded): $picker / $controls",
                picker.left >= controls.left && picker.right <= controls.right &&
                    picker.top >= controls.top && picker.bottom <= controls.bottom &&
                    picker.width() >= Render.dp(shot.context, 100f))
            assertTrue("Auto is clipped ($expanded): $auto / $controls",
                auto.top >= controls.top && auto.bottom <= controls.bottom &&
                    auto.right <= controls.right)
            assertTrue("source row must be above fold ($expanded): $controls",
                controls.bottom < Render.dp(shot.context, 760f))
        }
    }

    @Test
    fun conciseWebsiteGapAndReleasedDockReturnSpaceToTheTable() {
        val shot = Render.render(R.layout.activity_train, widthDp = 393f,
            heightDp = 780f, textScale = 1.15f) { root ->
            Render.text(root, R.id.provenance, "Comparison only")
            Render.text(root, R.id.age, "No reported event time")
            Render.text(root, R.id.headline, "No qualifying past-stop report")
            root.findViewById<TextView>(R.id.sourceSummary).apply {
                text = "No YPR site arrival · last listed TK (comparison). Position unknown."
                visibility = View.VISIBLE
            }
            root.findViewById<View>(R.id.note).visibility = View.GONE
            Render.text(root, R.id.next, "Following halt unknown · timetable below")
        }
        // Render saves an unbounded image for inspecting every route row. A phone
        // is NOT unbounded: remeasure its very same view tree at one fixed 780dp
        // viewport before and after releasing the dock.
        val width = Render.dp(shot.context, 393f).toInt()
        val height = Render.dp(shot.context, 780f).toInt()
        fun phoneLayout() {
            shot.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            shot.root.layout(0, 0, width, height)
        }
        phoneLayout()
        val parkedHeight = shot.bounds(R.id.routeViewport).height()
        val parkedDock = shot.bounds(R.id.refreshDock).height()
        assertEquals("default clock-free dock lost its 60dp", Render.dp(shot.context, 60f).toInt(),
            parkedDock)
        RefreshDockSpace.release(shot.root.findViewById(R.id.refreshDock))
        phoneLayout()
        val route = shot.bounds(R.id.routeViewport)
        assertTrue("moving Refresh left an empty dock instead of more table: " +
            "$parkedHeight -> ${route.height()} ($parkedDock dock)",
            route.height() - parkedHeight >= Render.dp(shot.context, 59f))
        assertEquals("released dock must have zero height", 0,
            shot.bounds(R.id.refreshDock).height())
        assertTrue("short report still covered Source controls",
            shot.bounds(R.id.sourceControls).bottom <=
                shot.bounds(R.id.routeColumnHeader).top)
        assertTrue("the source explanation must stay below its headline",
            shot.bounds(R.id.sourceSummary).top >= shot.bounds(R.id.headline).bottom)
        assertTrue("the table must have at least three compact rows of room: $route",
            route.height() >= Render.dp(shot.context, 240f))
    }

    @Test
    fun denseWebsiteFailureKeepsSourceNamedHeadlineAboveTheFold() {
        val shot = Render.render(R.layout.activity_train, widthDp = 393f,
            heightDp = 785f, textScale = 1.15f) { root ->
            Render.text(root, R.id.provenance,
                "whereismytrain.org.in (website) · comparison only")
            Render.text(root, R.id.age, "No event time")
            Render.text(root, R.id.headline,
                "whereismytrain.org.in (website): no qualifying past-stop report for this run")
        }
        val viewport = shot.bounds(R.id.scroller)
        val badge = shot.bounds(R.id.provenance)
        val headline = shot.bounds(R.id.headline)
        assertTrue("source badge was clipped: $badge / $viewport",
            badge.height() > 0 && badge.top >= viewport.top && badge.bottom <= viewport.bottom)
        assertTrue("source-specific failure headline was clipped: $headline / $viewport",
            headline.height() > 0 && headline.top >= viewport.top && headline.bottom <= viewport.bottom)
    }

    @Test
    fun connectionFailureNeverPaintsOverTheReportBadgeOrHeadline() {
        val shot = Render.render(R.layout.activity_train, widthDp = 411f,
            heightDp = 760f, textScale = 1.15f) { root ->
            root.findViewById<TextView>(R.id.provenance).text = "NTES · reported stop"
            root.findViewById<TextView>(R.id.age).text = "Freshness: 22 min ago"
            root.findViewById<TextView>(R.id.connectionAlert).apply {
                text = "Request failed · NTES · official. Previous report unchanged."
                visibility = View.VISIBLE
            }
            root.findViewById<TextView>(R.id.headline).text =
                "Last NTES report: Departed Tumkur · now unknown"
            root.findViewById<TextView>(R.id.delay).text = "+16m late · 00:31 (+1d)"
            root.findViewById<TextView>(R.id.next).text =
                "Tiptur · 01:05 (+1d)\nTimetable"
        }
        val badge = shot.bounds(R.id.provenance)
        val alert = shot.bounds(R.id.connectionAlert)
        val headline = shot.bounds(R.id.headline)
        val footer = shot.bounds(R.id.delay)
        assertTrue("warning painted over the last report's source: $badge / $alert",
            badge.height() > 0 && alert.top >= badge.bottom)
        assertTrue("warning painted over the report text: $alert / $headline",
            alert.height() > 0 && alert.bottom <= headline.top)
        assertTrue("footer painted over the report: $headline / $footer",
            headline.bottom <= footer.top)
    }

    @Test
    fun failedWebsiteResultIsInTheVisibleTopCardEvenAtLargeFont() {
        val shot = Render.render(R.layout.activity_train, widthDp = 411f,
            heightDp = 760f, textScale = 1.15f) { root ->
            root.findViewById<TextView>(R.id.headline).text = "No qualifying past-stop report"
            root.findViewById<TextView>(R.id.sourceSummary).apply {
                text = "whereismytrain.org.in (website): connection failed. Timetable shown."
                visibility = View.VISIBLE
            }
        }
        val summary = shot.bounds(R.id.sourceSummary)
        val viewport = shot.bounds(R.id.scroller)
        assertTrue("source result was clipped below the capped card viewport: $summary / $viewport",
            summary.height() > 0 && summary.top >= viewport.top && summary.bottom <= viewport.bottom)
        assertTrue("source result must follow its headline", summary.top >= shot.bounds(R.id.headline).bottom)
        assertTrue("source result was placed below the picker", summary.bottom < shot.bounds(R.id.sourceSpinner).top)
    }

    @Test
    fun websitePastCardPaintsWebMinutesAndWebPlatformNeverAct() {
        val p = pos(anchor = 4, status = StopStatus.DEPARTED, delay = null, live = true)
        val base = model(p, now = 432, per = emptyMap())
        val siteRow = base.rows[1].copy(showArr = 355, showDep = 358,
            source = TrackModel.MinuteSource.SITE_REPORTED, platform = "2")
        val site = base.copy(rows = base.rows.toMutableList().apply { set(1, siteRow) },
            headline = "Website reports departed Yesvantpur Jn · last site-marked past stop",
            provenanceLabel = "whereismytrain.org.in · website report, unverified")
        val shot = Render.render(R.layout.activity_train) { root -> Render.track(root, site, p, 432) }
        val row = shot.root.findViewById<RecyclerView>(R.id.stops).getChildAt(1)
        assertNotNull("website row did not render", row)
        val arr = text(row, R.id.liveArr)
        val dep = text(row, R.id.liveDep)
        assertEquals("05:55", arr)
        assertEquals("Website claim arrival 05:55", row.findViewById<TextView>(R.id.liveArr).contentDescription)
        assertEquals("05:58", dep)
        val plannedY = IntArray(2)
        val reportedY = IntArray(2)
        row.findViewById<TextView>(R.id.schedArr).getLocationOnScreen(plannedY)
        row.findViewById<TextView>(R.id.liveArr).getLocationOnScreen(reportedY)
        assertTrue("arrival columns do not line up on a real row: ${plannedY[1]} vs ${reportedY[1]}",
            kotlin.math.abs(plannedY[1] - reportedY[1]) <=
                (6 * shot.context.resources.displayMetrics.density).toInt())
        assertTrue("website platform was mislabelled: ${text(row, R.id.stopMeta)}",
            text(row, R.id.stopMeta).contains("site pf 2"))
        assertTrue("website row lost its last-stop state: ${text(row, R.id.stopStatus)}",
            text(row, R.id.stopStatus).contains("Departed"))
        assertFalse("source-specific prose belongs in the top card, not every row",
            text(row, R.id.stopStatus).contains("Website"))
        assertEquals("next station must stay scheduled", "—",
            text(shot.root.findViewById<RecyclerView>(R.id.stops).getChildAt(2), R.id.liveArr))
    }

    @Test
    fun wholeTrackingScreenIncludingTheTable() {
        val p = pos(anchor = 4, status = StopStatus.DEPARTED, delay = 14, live = true)
        val m = model(p, now = 432, per = perStop)
        // Tall enough that the review image shows the stop table as well as the header; a ScrollView
        // measures its child unbounded, so this is the same content scrolled to the bottom.
        val shot = Render.render(R.layout.activity_train, heightDp = 1480f) { root ->
            Render.track(root, m, p, 432)
            // The Info dialog is intentionally NOT in this layout: the stop table keeps its height.
        }
        assertEquals(9, Render.rowCount(shot.root))
        Render.save("03-track-full", shot.bitmap)
    }

    @Test
    fun withNoLiveDataTheScreenSaysScheduleAndPaintsNoPolyline() {
        val p = pos(anchor = 0, status = StopStatus.UNKNOWN, delay = null, live = false)
        val m = model(p, now = 360, per = emptyMap())
        val shot = Render.render(R.layout.activity_train) { root -> Render.track(root, m, p, 360) }

        val headline = shot.text(R.id.headline)
        assertTrue("offline screen should carry the schedule sentence, got '$headline'", headline.isNotBlank())
        assertFalse("offline screen must not claim a departure it did not see", headline.contains("departed", true))
        assertFalse("offline screen must not certify punctuality", headline.contains("on time", true))
        assertEquals(m.provenanceLabel, shot.text(R.id.provenance))
        assertEquals(9, Render.rowCount(shot.root))
        // What the user saw, and called what it is: with nothing reported, the "live / expected" column
        // reprinted the timetable, so every row looked like a confirmed arrival. A dash is the honest cell —
        // the schedule column above it already says that number, and saying it twice invents a report.
        val offline = shot.root.findViewById<RecyclerView>(R.id.stops)!!
        for (i in 0 until offline.childCount) {
            val row = offline.getChildAt(i)
            val arr = Render.read(row, R.id.liveArr)
            val dep = Render.read(row, R.id.liveDep)
            assertEquals("offline arrival slot must be a dash except at the route origin",
                if (i == 0) "START" else "—", arr)
            assertEquals("offline departure slot must be a dash except at the terminus",
                if (i == offline.childCount - 1) "END" else "—", dep)
        }
Render.save("01-track-schedule", shot.bitmap)
    }

    // ------------------------------------------------------------------ the search screen

    @Test
    @Config(sdk = [24], qualifiers = "w320dp-h640dp-mdpi")
    fun narrowLargeTypeDateDoesNotLoseItsWidthToDayButtons() {
        val shot = Render.render(R.layout.activity_main, widthDp = 320f,
            heightDp = 640f, textScale = 1.3f) { root ->
            Render.text(root, R.id.whenValue, "Today · 12:30")
        }
        val date = shot.root.findViewById<TextView>(R.id.whenValue)
        assertTrue("travel date has zero/insufficient width next to day buttons: ${date.width}",
            date.width >= Render.dp(shot.context, 160f).toInt())
        Render.assertNothingCut(date, "compact date at 320dp / 1.3x font")
        assertTrue("travel date must render actual characters", (date.layout?.lineCount ?: 0) > 0)
        for (id in listOf(R.id.dayBack, R.id.dayNow, R.id.dayFwd)) {
            assertTrue("day control $id collapsed on a narrow phone",
                shot.root.findViewById<View>(id).width >= Render.dp(shot.context, 48f).toInt())
        }
    }

    @Test
    fun searchScreenShowsFieldsDayPickerAndResultRows() {
        val hits = listOf(
            hit("16999", "KSR BENGALURU - SHIVAMOGGA TOWN EXP", 330, 604, 274, 0x7F),
            hit("16536", "PALEM G EXP", 396, 690, 311, 0x55),
            hit("06268", "SBC SMET Passenger SPECIAL", 486, 810, 274, 0x2A),
        )
        val shot = Render.render(R.layout.activity_main) { root ->
            // What MainActivity does: hints on the type-ahead boxes. Without them the render is two blank
            // rectangles, which is exactly what a reviewer would call a broken screen.
            root.findViewById<SuggestBox>(R.id.fromBox)?.hint = "Board at — station name or code"
            root.findViewById<SuggestBox>(R.id.toBox)?.hint = "Get off at — station name or code"
            root.findViewById<SuggestBox>(R.id.trainBox)?.hint = "5-digit number or a name fragment"
            // In the Ready state MainActivity hides `statusLine` (`visibility = GONE`) and puts
            // `repository.dataVersion()` in `dataChip`. Both strings here are that method's shape over the
            // shipped asset — counts and build date included, 6,775 trains rather than a number invented for
            // the picture. `ShippedDataTest` reads those counts out of the packaged DB; a fixture that made
            // its own up was how one preview ended up contradicting its neighbours.
            root.findViewById<View>(R.id.statusLine)?.visibility = View.GONE
            root.findViewById<View>(R.id.searchCardScroll)?.visibility = View.GONE
            root.findViewById<View>(R.id.searchCompact)?.visibility = View.VISIBLE
            Render.text(root, R.id.dataChip, DATA_VERSION)
            Render.text(root, R.id.resultsTitle, "5 trains between these stations")
            Render.text(root, R.id.resultsSub, "sorted by departure · 4 ms on device")
            root.findViewById<RecyclerView>(R.id.results)?.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = TrainHitAdapter { }.apply {
                    // The tag beside a result counts down from the *searched* instant — which is what
                    // MainViewModel.targetEpochMinute is. A bare 300 here rendered "in 19949d 0h", the
                    // fixture being 19 949 days away from the epoch rather than 30 minutes from it.
                    nowMinute = 19949L * 1440L + 300L
                    submitList(hits)
                }
                Render.settle(this, hits.size)
            }
        }
        assertNotNull("home brand must return to search", shot.root.findViewById<View>(R.id.homeBrand))
        assertNotNull("station inputs must share a compact row", shot.root.findViewById<View>(R.id.stationInputs))
        assertTrue("swap must have a labelled symbol", shot.text(R.id.swap).contains("⇅"))
        assertNotNull("results must expose sorting controls", shot.root.findViewById<View>(R.id.resultsSort))
        assertTrue("operating days missing below result", shot.text(R.id.hitDays).contains("S M T W T F S"))
        val painted = shot.distinct()
        assertTrue("screen painted only $painted colours", painted > 12)
        listOf(R.id.fromBox, R.id.toBox, R.id.trainBox, R.id.find, R.id.swap, R.id.dayNow, R.id.dayBack,
            R.id.dayFwd, R.id.modeStations, R.id.modeTrain).forEach { id ->
            assertNotNull("control ${name(shot, id)} missing from the layout", shot.root.findViewById<View>(id))
        }
        assertNotNull("the type-ahead input is not inside the box", shot.root.findViewById<View>(R.id.suggestInput))
        assertTrue("search button has no label", shot.text(R.id.find).isNotBlank())
        val shown = shot.root.findViewById<RecyclerView>(R.id.results)?.childCount ?: -1
        assertEquals("all three runs should be on screen", 3, shown)
        val list = shot.root.findViewById<RecyclerView>(R.id.results)!!
        for (i in 0 until list.childCount - 1) {
            val gap = list.getChildAt(i + 1).top - list.getChildAt(i).bottom
            assertTrue("train results should have a visible, thin 6dp gap: $gap px",
                gap in Render.dp(shot.context, 5f).toInt()..Render.dp(shot.context, 8f).toInt())
        }
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            // `containsMatchIn`, not `matches`: the row prints the number *and* its category ("16999 · SF
            // Exp"), which is what the adapter is meant to show, so a whole-string match would fail on
            // correct output.
            assertTrue("result $i has no train number: '${text(row, R.id.hitNo)}'",
                Regex("""\d{5,6}""").containsMatchIn(text(row, R.id.hitNo)))
            listOf(R.id.hitName, R.id.hitMeta, R.id.hitDays, R.id.hitDep, R.id.hitArr, R.id.hitTag).forEach { id ->
                assertTrue("result $i missing ${name(shot, id)}", text(row, id).isNotBlank())
            }
            // Present-and-correct is not enough for the name: it also has to have *fit*. A result row is where
            // a traveller chooses the train, and "KSR Bengaluru – Shivamogga Town…" is a name with the part
            // that distinguishes it cut off. Checked at the narrowest supported width; `LargeScreenRenderTest`
            // makes the same claim at 411 dp, where the row has different neighbours.
            listOf(R.id.hitNo, R.id.hitName, R.id.hitMeta, R.id.hitDays).forEach { id ->
                Render.assertNothingCut(row.findViewById(id), "result $i · ${name(shot, id)} = '${text(row, id)}'")
            }
        }
        Render.save("00-search", shot.bitmap)
    }

    private fun hit(no: String, nm: String, dep: Int, arr: Int, km: Int, days: Int) = TrainHit(
        trainNo = no, trainName = nm, trainType = "SUPERFAST_EXPRESS", originDateEpochDay = 19949L,
        boardMinute = dep, alightMinute = arr, fromCode = "SMET", toCode = "SBC", fromSeq = 1, toSeq = 9,
        distanceKm = km, runDays = days,
    )

    // ------------------------------------------------------------------ settings

    @Test
    fun trackingWhereIsMyTrainLiveSourceRendersCleanly() {
        val p = pos(anchor = 4, status = StopStatus.ARRIVED, delay = 5, live = true)
        val wimtStops = mapOf(
            "SBC" to LiveStopFields(actualArrivalMin = 330, actualDepartureMin = 335, delayDepartureMin = 5, platform = "3", departed = true),
            "YPA" to LiveStopFields(actualArrivalMin = 356, actualDepartureMin = 359, delayArrivalMin = 4, delayDepartureMin = 5, platform = "1", departed = true),
            "HSQ" to LiveStopFields(actualArrivalMin = 396, actualDepartureMin = 398, delayArrivalMin = 5, delayDepartureMin = 5, platform = "2", departed = true),
            "DKPT" to LiveStopFields(actualArrivalMin = 420, actualDepartureMin = 422, delayArrivalMin = 5, delayDepartureMin = 5, platform = "1", departed = false),
        )
        val m = model(p, now = 421, per = wimtStops)
        val shot = Render.render(R.layout.activity_train) { root ->
            Render.track(root, m, p, 421)
            Render.text(root, R.id.headline, "at DKPT · +5m late")
            Render.text(root, R.id.provenance, "Where Is My Train · live · updated 30s ago")
            Render.text(root, R.id.routeHeadActual, "WIMT")
        }
        Render.save("08-track-wimt-live", shot.bitmap)
    }

    @Test
    fun settingsScreenHasEveryControlTheActivityBinds() {
        val shot = Render.render(R.layout.activity_settings) { root ->
            // SettingsActivity's own two strings, word for word (its `state` line has never contained a
            // count, and the "schema 7" this fixture used to print is not a number the app shows anywhere).
            Render.text(root, R.id.state, "Built-in timetable installed · offline search ready")
            Render.text(root, R.id.about, "TrackTrain 1.0 · timetable: $DATA_VERSION\n\n" +
                "No accounts, no API keys, no ads, no analytics. Live status is fetched directly from a " +
                "public Indian Railways enquiry endpoint; everything else — station search, the ±24 h " +
                "train list and every timetable — is answered here.")
        }
        listOf(R.id.state, R.id.overrideUrl, R.id.columnHint, R.id.save, R.id.clear,
            R.id.back, R.id.about, R.id.defaultTrackingSource, R.id.appearance).forEach { id ->
            assertNotNull("SettingsActivity binds ${name(shot, id)}, the layout does not have it",
                shot.root.findViewById<View>(id))
        }
        assertTrue("settings painted ${shot.distinct()} colours", shot.distinct() > 8)
        Render.save("04-settings", shot.bitmap)
    }

    // ------------------------------------------------------------------ helpers

    private fun text(root: View, id: Int): String = Render.read(root, id)

    private fun name(shot: Render.Shot, id: Int): String =
        shot.root.resources.getResourceEntryName(id)

    /**
     * A label truncated by its own box is a layout bug, not a small-screen problem. Lives in [Render] now,
     * because the large-screen test has to make the same claim about other pixels.
     */
    private fun assertNotClipped(tv: TextView?, what: String = "view") = Render.assertNothingCut(tv, what)
}
