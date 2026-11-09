package `in`.opentrack.india.ui

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.StopRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineRouteRenderTest {
    @Test fun websiteColumnShowsPrintedClocksWithoutTurningForecastIntoPosition() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val anf = TrackModel.Stop(1, "ANF", "Anandapuram", 1800, 1802, 1813,
            null, 13, TrackModel.MinuteSource.SITE_REPORTED, "2",
            TrackModel.State.HERE, true, 468, 0, 25, false, false, false, -41)
        val srf = anf.copy(seq = 2, code = "SRF", name = "Sagar Jambagaru",
            schedArr = 1827, schedDep = 1829, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE, isAnchor = false, kmFromOrigin = 482)
        val day = 20751L // 26 Oct page for the 25 Oct originating run
        val pageAt = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(day, 1860)
        val site = `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.Report(
            mapOf("ANF" to `in`.opentrack.india.model.LiveStopFields(actualArrivalMin = 1813)),
            "ANF", false, 1813, pageAt, siteClocks = mapOf(
                "ANF" to `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.SiteClocks(1813, 1850, false),
                "SRF" to `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.SiteClocks(1869, 1871, true)))
        adapter.websiteCardTimes = WebsiteReportDisplay.displayedClocks(site, day,
            listOf("ANF", "SRF"), pageAt + 10_000L)
        adapter.haltMarker = TableMarker.Halt("ANF", "Last mapped website arrival", true)
        adapter.submitList(listOf(anf, srf))
        val at = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(at, 0)
        assertEquals("06:13", at.liveArr.text.toString())
        assertEquals("06:50", at.liveDep.text.toString())
        assertEquals("day2", at.liveDay.text.toString())
        assertTrue(at.liveDep.contentDescription.contains("Website displayed departure"))
        assertTrue(at.liveDep.contentDescription.contains("not a current position"))
        assertEquals(View.GONE, at.status.visibility)
        assertEquals(View.VISIBLE, at.trainMarker.visibility)
        val next = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(next, 1)
        assertEquals("—", next.liveArr.text.toString())
        assertEquals("—", next.liveDep.text.toString())
        assertTrue(next.liveArr.contentDescription.contains("Not reported arrival unknown"))
        assertEquals(View.GONE, next.trainMarker.visibility)
    }

    @Test fun websitePastTipturFillsOnlyTheLabelledGapBesideNtesNotItsActColumn() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val ttr = TrackModel.Stop(14, "TTR", "Tiptur", 1505, 1506, 1505, 1506, null,
            TrackModel.MinuteSource.SCHEDULE, "3", TrackModel.State.PASSED,
            false, 279, 0, 231, false, false, false, null)
        val ask = ttr.copy(seq = 15, code = "ASK", name = "Arsikere Jn",
            schedArr = 1543, schedDep = 1545, showArr = 1538, showDep = null,
            source = TrackModel.MinuteSource.REPORTED, state = TrackModel.State.HERE,
            isAnchor = true, kmFromOrigin = 304)
        val rrb = ttr.copy(seq = 16, code = "RRB", name = "Birur Jn",
            state = TrackModel.State.NEXT, showArr = null, showDep = null)
        val day = 20753L
        val pageAt = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(day, 1540)
        val site = `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.Report(
            mapOf("TTR" to `in`.opentrack.india.model.LiveStopFields(
                actualArrivalMin = 1517, actualDepartureMin = 1520),
                "ASK" to `in`.opentrack.india.model.LiveStopFields(actualArrivalMin = 1538)),
            "ASK", false, 1538, pageAt, siteClocks = mapOf(
                "TTR" to `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.SiteClocks(1517, 1520, false),
                "ASK" to `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.SiteClocks(1538, null, false),
                "RRB" to `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.SiteClocks(1612, 1615, true)))
        adapter.websiteComparisonTimes = WebsiteReportDisplay.displayedClocks(site, day,
            listOf("TTR", "ASK", "RRB"), pageAt + 60_000)
        // A later site timeout/stale page leaves only this separately labelled
        // historical comparison, never an official Act or a renewed page clock.
        assertTrue(WebsiteGapUi.caption(ttr, site.siteClocks["TTR"], saved = true)!!
            .startsWith("Web past (saved) · arr 01:17"))
        adapter.haltMarker = TableMarker.Halt("ASK", "Last NTES arrival", false)
        adapter.submitList(listOf(ttr, ask, rrb))
        val past = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(past, 0)
        assertEquals("—", past.liveArr.text.toString())
        assertEquals("—", past.liveDep.text.toString())
        assertEquals("Web past · arr 01:17 · dep 01:20 · day2",
            past.websitePastComparison.text.toString())
        assertEquals(View.VISIBLE, past.websitePastComparison.visibility)
        assertTrue(past.websitePastComparison.contentDescription.contains("Not an NTES actual"))
        assertEquals(View.GONE, past.trainMarker.visibility)
        val at = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(at, 1)
        assertEquals("01:38", at.liveArr.text.toString())
        assertEquals("—", at.liveDep.text.toString())
        assertEquals(View.GONE, at.websitePastComparison.visibility)
        assertEquals(View.VISIBLE, at.trainMarker.visibility)
        adapter.savedOfficialClocks = setOf("ASK:arr")
        adapter.onBindViewHolder(at, 1)
        assertEquals("01:38", at.liveArr.text.toString())
        assertTrue(at.meta.text.toString().contains("NTES saved arr"))
        assertTrue(at.liveArr.contentDescription.contains("saved for this run, not a new event"))
        adapter.savedOfficialClocks = emptySet()
        val future = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(future, 2)
        assertEquals(View.GONE, future.websitePastComparison.visibility)
        assertEquals(null, WebsiteGapUi.caption(rrb,
            `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.SiteClocks(1612, 1615, false)))
        adapter.websiteComparisonTimes = emptyMap() // switching away clears the comparison
        adapter.onBindViewHolder(past, 0)
        assertEquals(View.GONE, past.websitePastComparison.visibility)
    }

    @Test fun bengaluruScreenshotLeavesFutureWebsiteCellsBlankOnScreen() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val sbc = TrackModel.Stop(1, "SBC", "KSR Bengaluru", 1385, 1395, 1378,
            null, -7, TrackModel.MinuteSource.SITE_REPORTED, "10",
            TrackModel.State.HERE, true, 138, 0, 27, false, false, false, -7)
        val mwm = sbc.copy(seq = 2, code = "MWM", name = "Malleswaram",
            schedArr = 1400, schedDep = 1401, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE, isAnchor = false)
        val ypr = mwm.copy(seq = 3, code = "YPR", name = "Yesvantpur Jn",
            schedArr = 1406, schedDep = 1408)
        val day = 20753L
        val stamp = `in`.opentrack.india.core.RailTime.railMinuteToEpochMillis(day, 1390) + 44_000L
        val site = `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.Report(
            mapOf("SBC" to `in`.opentrack.india.model.LiveStopFields(actualArrivalMin = 1378)),
            "SBC", false, 1378, stamp, siteClocks = mapOf(
                "SBC" to `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.SiteClocks(1378, 1395, false),
                "MWM" to `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.SiteClocks(1396, 1401, true),
                "YPR" to `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.SiteClocks(1401, 1408, true)))
        adapter.websiteCardTimes = WebsiteReportDisplay.displayedClocks(site, day,
            listOf("SBC", "MWM", "YPR"), stamp + 20_000L)
        adapter.haltMarker = TableMarker.Halt("SBC", "Last mapped website arrival", true)
        adapter.submitList(listOf(sbc, mwm, ypr))
        val sbcHolder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(sbcHolder, 0)
        assertEquals("22:58", sbcHolder.liveArr.text.toString())
        assertEquals("—", sbcHolder.liveDep.text.toString())
        assertEquals("Last site arrival · 22:58", sbcHolder.eventCaption.text.toString())
        assertEquals(View.VISIBLE, sbcHolder.trainMarker.visibility)
        for (index in 1..2) {
            val next = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
            adapter.onBindViewHolder(next, index)
            assertEquals("—", next.liveArr.text.toString())
            assertEquals("—", next.liveDep.text.toString())
            assertEquals(View.GONE, next.trainMarker.visibility)
            assertTrue(next.liveArr.contentDescription.contains("Not reported arrival unknown"))
        }
    }

    @Test fun minuteAgeTickRebindsOnlyTheMarkerInsteadOfTheWholeRoute() {
        val adapter = StopAdapter()
        val at = TrackModel.Stop(1, "SBC", "KSR Bengaluru", 1385, 1395, 1378,
            null, -7, TrackModel.MinuteSource.SITE_REPORTED, null,
            TrackModel.State.HERE, true, 138, 0, 27, false, false, false, -7)
        adapter.submitList(listOf(at, at.copy(seq = 2, code = "MWM", name = "Malleswaram",
            source = TrackModel.MinuteSource.SCHEDULE, showArr = null, isAnchor = false),
            at.copy(seq = 3, code = "YPR", name = "Yesvantpur Jn",
                source = TrackModel.MinuteSource.SCHEDULE, showArr = null, isAnchor = false)))
        adapter.haltMarker = TableMarker.Halt("SBC", "Last mapped website arrival", true)
        var full = 0
        val changed = mutableListOf<Int>()
        val observer = object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onChanged() { full++ }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                repeat(itemCount) { changed += positionStart + it }
            }
        }
        adapter.registerAdapterDataObserver(observer)
        adapter.legAgeSeconds = 13 * 60L
        assertEquals(0, full)
        assertEquals(listOf(0), changed)
        adapter.legAgeSeconds = 13 * 60L + 20
        assertEquals(listOf(0), changed) // no rebind inside the same minute
        adapter.unregisterAdapterDataObserver(observer)
    }

    @Test fun freshLastWebsiteDepartureIsLabelledOutsideActualBeforeAnyMovementCue() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val arrived = TrackModel.Stop(1, "KMSI", "Kumsi", 1769, 1770, 1772,
            null, 10, TrackModel.MinuteSource.SITE_REPORTED, null,
            TrackModel.State.HERE, true, 437, 0, 25, false, false, false, 3)
        adapter.websitePendingDeparture = "KMSI" to 1774
        adapter.haltMarker = TableMarker.Halt("KMSI", "Site's last past stop", true)
        adapter.submitList(listOf(arrived))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("05:32", holder.liveArr.text.toString())
        assertEquals("—", holder.liveDep.text.toString())
        assertEquals(View.VISIBLE, holder.status.visibility)
        assertTrue(holder.status.text.toString().contains("05:34? · unconfirmed"))
        assertTrue(holder.status.contentDescription.contains("not an actual departure"))
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        assertFalse(holder.inlineLeg.showTrain)
        adapter.websitePendingDeparture = null
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.status.visibility)
    }

    @Test fun birurDepartureSuggestionUsesOneSeparatedSymbolAndFoldedBetweenCaption() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val birur = TrackModel.Stop(1, "RRB", "Birur Jn", 1205, 1206, 1215,
            null, 10, TrackModel.MinuteSource.SITE_REPORTED, null,
            TrackModel.State.HERE, true, 211, 0, 24, false, false, false, -11)
        val tarikere = birur.copy(seq = 2, code = "TKE", name = "Tarikere Jn",
            schedArr = 1230, schedDep = 1231, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE, isAnchor = false, kmFromOrigin = 235)
        val map = listOf(StopRow(2, "SPV", "Sivapur", null, null, null,
            null, null, 0, 215, false), StopRow(3, "KRNH", "Koranahalli", null, null,
            null, null, null, 0, 220, false))
        val halts = `in`.opentrack.india.model.TrainRoute("12089", "Test", "SBC", "Bengaluru",
            "SMET", "Shivamogga", 274, null, null, null,
            listOf(StopRow(1, "RRB", "Birur Jn", null, null, null,
                1205, 1206, 0, 211, true), StopRow(2, "TKE", "Tarikere Jn", null,
                null, null, 1230, 1231, 0, 235, true)), emptyList())
        val expanded = halts.copy(stops = listOf(halts.stops[0]) + map + halts.stops[1])
        adapter.setRoutePoints(mapOf("RRB" to map))
        adapter.mappedAnchorKm = mapOf("RRB" to 211, "TKE" to 235)
        adapter.setProjectedLeg(RouteBetween.departureCueLeg(halts, expanded, "RRB", true))
        adapter.websiteTentativeDeparture = "RRB" to 1221
        adapter.submitList(listOf(birur, tarikere))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("20:21?", holder.liveDep.text.toString())
        assertTrue(holder.liveDep.contentDescription.contains("not actual"))
        assertEquals(View.GONE, holder.trainMarker.visibility)
        assertEquals(View.VISIBLE, holder.dot.visibility)
        assertTrue(holder.inlineLeg.showTrain)
        assertTrue(holder.inlineLegCaption.text.toString().contains("4 km route gap to Sivapur"))
        assertEquals(View.VISIBLE, holder.inlineLegCaption.visibility)
        assertFalse(holder.inlineLeg.contentDescription.contains("percent of scheduled leg"))
        holder.inlineLegGroup.performClick()
        assertEquals("text must disappear on the tap, not only after a rebind",
            View.GONE, holder.inlineLegCaption.visibility)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.inlineLegCaption.visibility)
        assertTrue(holder.inlineLeg.showLabels)
        holder.inlineLegGroup.performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegCaption.visibility)
        adapter.websiteTentativeDeparture = null
        adapter.setProjectedLeg(RouteBetween.siteLastStopLeg(halts, expanded, "RRB"))
        adapter.haltMarker = TableMarker.Halt("RRB", "Site's last past stop", true)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("—", holder.liveDep.text.toString())
        assertEquals(View.GONE, holder.inlineLegCaption.visibility)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
    }

    @Test fun confirmedDepartureShowsActualTimeAndSymbolicGapWithoutInventingKm() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val stop = TrackModel.Stop(1, "RRB", "Birur Jn", 1205, 1206, 1215,
            1221, 15, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 211, 0, 24, false, false, false, -5)
        val next = stop.copy(seq = 2, code = "TKE", name = "Tarikere Jn",
            schedArr = 1230, schedDep = 1231, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE, isAnchor = false, kmFromOrigin = 235)
        val route = `in`.opentrack.india.model.TrainRoute("12089", "Test", "RRB", "Birur",
            "TKE", "Tarikere", 24, null, null, null,
            listOf(StopRow(1, "RRB", "Birur Jn", null, null, null,
                1205, 1206, 0, 211, true), StopRow(2, "TKE", "Tarikere Jn", null,
                null, null, 1230, 1231, 0, 235, true)), emptyList())
        adapter.setProjectedLeg(RouteBetween.departureCueLeg(route, null, "RRB"))
        adapter.submitList(listOf(stop, next))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("20:21", holder.liveDep.text.toString())
        assertEquals("Reported departure 20:21", holder.liveDep.contentDescription)
        assertEquals(View.GONE, holder.trainMarker.visibility)
        assertEquals(View.VISIBLE, holder.dot.visibility)
        assertTrue(holder.inlineLeg.showTrain)
        assertTrue(holder.inlineLegCaption.text.toString().contains("24 km route gap to Tarikere Jn"))
        assertFalse(holder.inlineLeg.contentDescription.contains("percent of scheduled leg"))
        holder.inlineLeg.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        holder.inlineLeg.layout(0, 0, holder.inlineLeg.measuredWidth, holder.inlineLeg.measuredHeight)
        assertTrue("departure icon must have a visible gap below the leg start",
            holder.inlineLeg.trainCenter()!!.second > 24 * ctx.resources.displayMetrics.density)
        adapter.legAgeSeconds = SourceFreshness.STALE_AFTER_MS / 1_000L
        adapter.onBindViewHolder(holder, 0)
        assertTrue(holder.inlineLegCaption.text.toString().contains("Last stop reported"))
        assertFalse(holder.inlineLegCaption.text.toString().contains("Between"))
    }

    @Test fun websiteArrivalOnlyAtYesvantpurAppearsOnRailAndClearsOnSourceChange() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val site = TrackModel.Stop(2, "YPR", "Yesvantpur Jn", 670, 672,
            686, null, null, TrackModel.MinuteSource.SITE_REPORTED, null,
            TrackModel.State.HERE, true, 367, 0, 5, false, false, false, -2)
        val next = site.copy(seq = 3, code = "SBC", name = "Bengaluru",
            schedArr = 720, schedDep = null, showArr = 720,
            source = TrackModel.MinuteSource.SCHEDULE, isAnchor = false)
        adapter.submitList(listOf(site, next))
        adapter.haltMarker = TableMarker.Halt("YPR", "Site's last past stop", true)
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("11:26", holder.liveArr.text.toString())
        assertEquals("—", holder.liveDep.text.toString())
        assertEquals("Website claim arrival 11:26", holder.liveArr.contentDescription)
        assertEquals("", holder.timeType.text.toString())
        assertEquals("WEB attribution stays in badge/accessibility, not repeated beside clocks",
            View.GONE, holder.timeType.visibility)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        assertEquals("Last site arrival · 11:26",
            holder.eventCaption.text.toString())
        adapter.haltMarker = null
        // AsyncListDiffer's null-list removal is synchronous; emptyList() is not.
        // Reset so a standalone holder observes the subsequent source's data on bind.
        adapter.submitList(null)
        adapter.submitList(listOf(site.copy(showArr = 670, showDep = 672,
            source = TrackModel.MinuteSource.SCHEDULE), next))
        adapter.onBindViewHolder(holder, 0)
        assertEquals("—", holder.liveArr.text.toString())
        assertEquals(View.GONE, holder.timeType.visibility)
        assertEquals(View.GONE, holder.eventCaption.visibility)
    }

    @Test fun onlyCollapsedReportedHaltShowsAtAndAsOnBesideItsLocomotive() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val main = TrackModel.Stop(1, "DRU", "Kadur", 512, 513,
            523, 524, 11, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 167, 0, 40, false, false, false, -12)
        val next = main.copy(seq = 2, code = "ASK", name = "Arsikere Jn",
            showArr = null, showDep = null, source = TrackModel.MinuteSource.SCHEDULE,
            isAnchor = false, kmFromOrigin = 206)
        adapter.submitList(listOf(main, next))
        adapter.haltMarker = TableMarker.Halt("DRU", "Last reported stop", false)
        adapter.setRoutePoints(mapOf("DRU" to listOf(StopRow(3, "MID", "Intermediate",
            null, null, null, 526, null, 0, 185, false))))
        adapter.mappedAnchorKm = mapOf("DRU" to 167, "ASK" to 206)
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        assertEquals("At Kadur · as on 08:44\n18 km route gap to Intermediate",
            holder.eventCaption.text.toString())
        holder.inlineLegGroup.performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.eventCaption.visibility)
        holder.inlineLegGroup.performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.eventCaption.visibility)
        adapter.haltMarker = null
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.eventCaption.visibility)
        adapter.haltMarker = TableMarker.Halt("DRU", "Site's last past stop", true)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("an NTES row must not inherit a site marker", View.GONE,
            holder.eventCaption.visibility)
        adapter.submitList(null)
        adapter.submitList(listOf(main.copy(source = TrackModel.MinuteSource.SITE_REPORTED), next))
        adapter.onBindViewHolder(holder, 0)
        assertEquals("Last site arrival · 08:44\n18 km route gap to Intermediate",
            holder.eventCaption.text.toString())
        assertEquals("", holder.timeType.text.toString())
        assertEquals("WEB attribution stays in badge/accessibility, not repeated beside clocks",
            View.GONE, holder.timeType.visibility)
        assertEquals(View.GONE, holder.timeType.visibility)
    }

    @Test fun projectedTrainIsInlineBetweenItsHaltRowsAndCandidateIsVisibleWithoutReplacingAct() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.setProjectedLeg(RouteBetween.LegProjection("Birur", "Tarikere", .25f,
            null, 212, "RRB", "TKE", listOf(RouteBetween.LegPoint("SPV", "Sivapur", 216, .4f))))
        adapter.reports = mapOf("RRB" to "RRB arrival 13:13 · WIMT web-cache candidate, NOT verified Act")
        val row = TrackModel.Stop(1, "RRB", "Birur Jn", 722, 724,
            792, 794, null, TrackModel.MinuteSource.REPORTED, "2",
            TrackModel.State.PASSED, true, 206, 0, 24, false, false, false, -5)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertTrue(holder.inlineLeg.contentDescription.contains("not GPS"))
        assertEquals(View.GONE, holder.inlineLegCaption.visibility)
        assertTrue(holder.report.text.contains("13:13"))
        assertEquals("13:12", holder.liveArr.text.toString())
        adapter.setProjectedLeg(null)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.inlineLegGroup.visibility)
    }

    @Test fun officialStopHasVisibleBluePinnedLocomotiveNotAnInterpolatedPosition() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.sourceNameForMarker = "NTES"
        adapter.setProjectedLeg(RouteBetween.LegProjection("Arasalu", "Kumsi", 0f,
            "Arasalu", 57, "ARU", "KMSI", emptyList(), RouteBetween.MarkerKind.REPORTED_HALT))
        adapter.legAgeSeconds = 12 * 60L
        val row = TrackModel.Stop(1, "ARU", "Arasalu", 960, 961,
            961, null, null, TrackModel.MinuteSource.REPORTED, "1",
            TrackModel.State.PASSED, true, 57, 0, 15, false, false, false, -12)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertEquals(View.GONE, holder.inlineLegCaption.visibility)
        assertTrue(holder.inlineLeg.contentDescription.contains("no measured distance since"))
        assertEquals("12 min ago", holder.inlineLegAge.text.toString())
        assertEquals(12 * 60L, holder.inlineLeg.ageSeconds)
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
    }

    @Test fun stationClaimMarkerNeverPretendsToHaveAReportedMinute() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.setProjectedLeg(RouteBetween.LegProjection("Arasalu", "Kumsi", 0f,
            "Arasalu", 57, "ARU", "KMSI", emptyList(), RouteBetween.MarkerKind.STATION_CLAIM))
        val row = TrackModel.Stop(1, "ARU", "Arasalu", 960, 961,
            960, 961, null, TrackModel.MinuteSource.SCHEDULE, null,
            TrackModel.State.PASSED, true, 57, 0, 15, false, false, false, -12)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.inlineLegCaption.visibility)
        assertTrue(holder.inlineLeg.contentDescription.contains("passage time"))
        assertEquals(null, holder.inlineLeg.ageSeconds)
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
        assertEquals("—", holder.liveArr.text.toString())
    }

    @Test fun websiteLastStopIsPinnedAtHaltAndAgesWithoutMovingAlongLeg() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val pinned = RouteBetween.LegProjection("Arasalu", "Kumsi", 0f,
            "Arasalu", 57, "ARU", "KMSI", listOf(
                RouteBetween.LegPoint("ABC", "Static point", 60, .2f)),
            RouteBetween.MarkerKind.SITE_LAST_STOP)
        adapter.setProjectedLeg(pinned)
        adapter.legAgeSeconds = 9 * 60L
        val row = TrackModel.Stop(1, "ARU", "Arasalu", 960, 961,
            961, null, null, TrackModel.MinuteSource.SITE_REPORTED, "1",
            TrackModel.State.PASSED, true, 57, 0, 15, false, false, false, -9)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertEquals(View.GONE, holder.inlineLegCaption.visibility)
        assertEquals("9 min ago", holder.inlineLegAge.text.toString())
        assertTrue(holder.inlineLeg.contentDescription.contains("no measured distance since"))
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
        assertEquals(9 * 60L, holder.inlineLeg.ageSeconds)
        adapter.legAgeSeconds = 70 * 60L
        adapter.onBindViewHolder(holder, 0)
        assertEquals(70 * 60L, holder.inlineLeg.ageSeconds)
        assertEquals("1 h 10 min ago", holder.inlineLegAge.text.toString())
        assertEquals(0f, holder.inlineLeg.projection!!.fraction, .001f)
    }

    @Test fun denseLegRetainsAllOrderedStaticPointsInAccessibilityAndExpandedMap() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val points = (1..11).map { i ->
            RouteBetween.LegPoint("P$i", "Static point $i", 100 + i * i,
                (i * i).toFloat() / (12 * 12))
        }
        adapter.setProjectedLeg(RouteBetween.LegProjection("A", "B", 0f,
            "A", 100, "AAA", "BBB", points, RouteBetween.MarkerKind.REPORTED_HALT))
        val row = TrackModel.Stop(1, "AAA", "A", 500, 505, 507,
            null, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 100, 0, 44, false, false, false, -10)
        adapter.submitList(listOf(row))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        val description = holder.inlineLeg.contentDescription.toString()
        assertTrue(description.contains("Static point 1 (P1)") &&
            description.contains("Static point 3 (P3)"))
        assertTrue(description.contains("Static point 9 (P9)") &&
            description.contains("Static point 11 (P11)"))
        assertEquals(points, holder.inlineLeg.projection!!.points)
        val ruler = holder.inlineLeg
        val width = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        ruler.measure(width, height)
        val compact = ruler.measuredHeight
        assertEquals(0f, ruler.projection!!.fraction, .001f)
        ruler.showLabels = true
        ruler.measure(width, height)
        assertTrue("station dots need room on the same line",
            ruler.measuredHeight > compact)
        assertEquals("disclosing map points moved the last-reported train", 0f,
            ruler.projection!!.fraction, .001f)
        ruler.showLabels = false
        ruler.measure(width, height)
        assertEquals("closing stations did not restore the compact leg",
            compact, ruler.measuredHeight)
    }

    @Test fun routeSpineContinuesThroughTheFullStopItemIncludingItsSpacing() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = android.view.LayoutInflater.from(ctx)
            .inflate(R.layout.item_stop, FrameLayout(ctx), false) as RouteStopGroup
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        root.paintForTest(android.graphics.Canvas(bitmap))
        val x = (RouteRailGeometry.X_DP * ctx.resources.displayMetrics.density).toInt()
        assertEquals("route spine must reach the item boundary, not stop at the text",
            ctx.getColor(R.color.stroke), bitmap.getPixel(x, root.height - 2))
    }

    @Test fun verticalDistanceRulerScalesByScheduledKmWithoutOutgrowingTheViewport() {
        assertEquals(55, LegGeometry.heightDp(2))
        assertTrue(LegGeometry.heightDp(20) > LegGeometry.heightDp(6))
        assertEquals(78, LegGeometry.heightDp(200))
        assertEquals("small screens cap the leg and leave space for three halt rows",
            68, LegGeometry.heightDp(200, 480f))
        assertTrue(LegGeometry.heightDp(65) + 3 * 82 < 360)
        assertEquals(53, LegGeometry.compactMappedHeightDp(65))
        assertTrue("short mapped legs must not all look identical",
            LegGeometry.compactMappedHeightDp(3) < LegGeometry.compactMappedHeightDp(18))
        assertTrue("3 km, 7 km and 18 km need distinct stop separations",
            LegGeometry.stopGapDp(3) < LegGeometry.stopGapDp(7) &&
                LegGeometry.stopGapDp(7) < LegGeometry.stopGapDp(18))
        assertTrue("distance must still change the long-leg layout",
            LegGeometry.stopGapDp(60) > LegGeometry.stopGapDp(18))
        assertTrue("small displays retain a bounded leg", LegGeometry.stopGapDp(200, 480f) <= 26)
        assertEquals("1 min ago", LegGeometry.ageLabel(60))
        assertEquals("1 hour ago", LegGeometry.ageLabel(3600))
        assertEquals("1 hour 2 min ago", LegGeometry.ageLabel(3720))
    }

    @Test fun sourceSwitchKeepsTheSameVerticalLegGeometry() {
        val ctx = RuntimeEnvironment.getApplication()
        val from = TrackModel.Stop(1, "AAA", "From", 500, 505, 505,
            null, 505, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 100, 0, 20, false, false, false, -5)
        val to = from.copy(seq = 2, code = "BBB", name = "To", kmFromOrigin = 120,
            isAnchor = false, source = TrackModel.MinuteSource.SCHEDULE,
            showArr = null, showDep = null)
        val adapter = StopAdapter()
        adapter.submitList(listOf(from, to))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        val spec = View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY)
        val open = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun height(kind: RouteBetween.MarkerKind): Int {
            adapter.setProjectedLeg(RouteBetween.LegProjection("From", "To", 0f,
                "From", 100, "AAA", "BBB", emptyList(), kind))
            adapter.onBindViewHolder(holder, 0)
            assertEquals(20, holder.inlineLeg.distanceKm)
            holder.inlineLeg.measure(spec, open)
            return holder.inlineLeg.measuredHeight
        }
        assertEquals("source choice changed the train ruler's footprint",
            height(RouteBetween.MarkerKind.REPORTED_HALT),
            height(RouteBetween.MarkerKind.SITE_LAST_STOP))
    }

    @Test fun disclosedStationNamesAndDotsShareTheLocomotiveRail() {
        val ctx = RuntimeEnvironment.getApplication()
        val points = listOf(
            RouteBetween.LegPoint("SAR", "Sree Saradanagar", 142, .04f, 1011, true),
            RouteBetween.LegPoint("HNV", "Honnavalli Road", 152, .44f, 1020, true),
            RouteBetween.LegPoint("ADH", "Adihalli", 157, .64f, 1025, true))
        val ruler = InlineLegView(ctx).apply {
            projection = RouteBetween.LegProjection("Tiptur", "Arsikere", 0f, null,
                141, "TTR", "ASK", points, RouteBetween.MarkerKind.REPORTED_HALT)
            distanceKm = 25
            showLabels = true
            ageSeconds = 180
        }
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        val bmp = android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(ctx.getColor(R.color.surface))
        ruler.paintForTest(canvas)
        Render.save("10-inline-stations-and-train", bmp)
        val d = ctx.resources.displayMetrics.density
        val positions = LegGeometry.pointPositionsDp(points, 25)
        assertTrue(positions[1] - positions[0] > positions[0] - 24f)
        for (y in positions) {
            val cx = (RouteRailGeometry.X_DP * d).toInt()
            val cy = (y * d).toInt()
            // The shortened first gap may put its circle centre between device
            // pixels; anti-aliasing changes one pixel, never the actual dot.
            assertTrue("intermediate dot is not on the train's rail at $cy",
                (-2..2).any { dy -> bmp.getPixel(cx, cy + dy) == ctx.getColor(R.color.text2) })
        }
        val surface = ctx.getColor(R.color.surface)
        val timeY = (positions[0] * d).toInt()
        // Only the planned clock may change when the static timetable changes:
        // never paint an interpolated ~ minute in the ACTUAL column.
        ruler.projection = ruler.projection!!.copy(points = points.map {
            it.copy(passageMinute = null)
        })
        val noSchedule = android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        val other = android.graphics.Canvas(noSchedule)
        other.drawColor(surface)
        ruler.paintForTest(other)
        assertTrue("a planned intermediate clock must paint LEFT of the rail",
            (0 until (RouteRailGeometry.X_DP - 27f).times(d).toInt()).any { x ->
                ((timeY - (8 * d).toInt())..(timeY + (8 * d).toInt())).any { y ->
                    bmp.getPixel(x, y) != noSchedule.getPixel(x, y)
                }
            })
        assertFalse("planned clock leaked into the ACTUAL column",
            ((bmp.width - (85 * d).toInt()) until bmp.width).any { x ->
                ((timeY - (8 * d).toInt())..(timeY + (8 * d).toInt())).any { y ->
                    bmp.getPixel(x, y) != noSchedule.getPixel(x, y)
                }
            })
        assertEquals("the locomotive has moved to a point instead of staying at its stop",
            ctx.getColor(R.color.live), bmp.getPixel(((RouteRailGeometry.X_DP - 16f) * d).toInt(), (24 * d).toInt()))
    }

    @Test fun intermediateActualNeedsASelectedWebsiteStopNotAPlannedPassage() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val stop = StopRow(2, "HALT", "Halt", null, null, null,
            901, 902, 0, 20, true)
        val passing = StopRow(3, "PASS", "Passing", null, null, null,
            904, null, 0, 25, false)
        adapter.setRoutePoints(mapOf("AAA" to listOf(stop, passing)))
        adapter.websiteIntermediateReports = mapOf(
            "HALT" to `in`.opentrack.india.model.LiveStopFields(
                actualArrivalMin = 906, actualDepartureMin = 908),
            "PASS" to `in`.opentrack.india.model.LiveStopFields(actualArrivalMin = 909))
        val from = TrackModel.Stop(1, "AAA", "From", 890, 895,
            895, null, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 10, 0, 20, false, false, false, -2)
        val to = from.copy(seq = 2, code = "BBB", name = "To", kmFromOrigin = 30,
            schedArr = 915, schedDep = 916, showArr = null, showDep = null,
            isAnchor = false, source = TrackModel.MinuteSource.SCHEDULE)
        adapter.submitList(listOf(from, to))
        val holder = adapter.onCreateViewHolder(android.widget.FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        holder.inlineLegGroup.performClick()
        adapter.onBindViewHolder(holder, 0)
        val points = holder.inlineLeg.projection!!.points
        assertEquals(901, points[0].passageMinute)
        assertEquals(906, points[0].siteArrivalMin)
        assertEquals(908, points[0].siteDepartureMin)
        assertEquals("mapped passing card should not disappear", 909, points[1].siteArrivalMin)
        assertEquals("pass-through is not a stopped departure", null, points[1].siteDepartureMin)
        assertFalse(points[1].isHalt)
        assertTrue(holder.inlineLeg.contentDescription.contains("website passage claim 15:09"))
        assertTrue(holder.inlineLeg.contentDescription.contains("website arrival claim 15:06"))
        adapter.websiteIntermediateReports = emptyMap()
        adapter.onBindViewHolder(holder, 0)
        assertTrue(holder.inlineLeg.projection!!.points.all { it.siteArrivalMin == null })
    }

    @Test fun onlySelectedWebsiteIntermediatePaintsItsOwnPublishedEventBesideMarker() {
        val ctx = RuntimeEnvironment.getApplication()
        val ruler = InlineLegView(ctx).apply {
            projection = RouteBetween.LegProjection("Arsikere", "Birur", .5f, "Devanur",
                327, "ASK", "RRB", listOf(RouteBetween.LegPoint("DRU", "Devanur", 327, .5f)),
                RouteBetween.MarkerKind.SITE_INTERMEDIATE)
            distanceKm = 45
            ageSeconds = null
        }
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        fun painted(minute: Int?): android.graphics.Bitmap {
            ruler.siteEventMinute = minute
            return android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
                android.graphics.Bitmap.Config.ARGB_8888).also { bitmap ->
                ruler.paintForTest(android.graphics.Canvas(bitmap))
            }
        }
        val noSiteEvent = painted(null)
        val siteEvent = painted(1576)
        val d = ctx.resources.displayMetrics.density
        val fromX = ((RouteRailGeometry.X_DP + 23f) * d).toInt()
        assertTrue("a qualified website minute must change pixels beside the non-halt marker",
            (fromX until siteEvent.width).any { x ->
                (0 until siteEvent.height).any { y ->
                    siteEvent.getPixel(x, y) != noSiteEvent.getPixel(x, y)
                }
            })
        ruler.showLabels = true
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        val expandedWithoutClock = painted(null)
        val expandedWithClock = painted(1576)
        assertFalse("expanded points already show the detail; no As on text next to the logo",
            (0 until expandedWithClock.width).any { x ->
                (0 until expandedWithClock.height).any { y ->
                    expandedWithoutClock.getPixel(x, y) != expandedWithClock.getPixel(x, y)
                }
            })
        ruler.showLabels = false
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        val foldedAgain = painted(1576)
        assertTrue("folding must restore the nearby source time",
            (fromX until foldedAgain.width).any { x ->
                (0 until foldedAgain.height).any { y ->
                    foldedAgain.getPixel(x, y) != noSiteEvent.getPixel(x, y)
                }
            })
    }

    @Test fun compactSiteIntermediateNamesTheReportedPointAndPlacesAgeBelowTheTrain() {
        val ctx = RuntimeEnvironment.getApplication()
        val ruler = InlineLegView(ctx).apply {
            distanceKm = 24
            projection = RouteBetween.LegProjection("VNR", "DRU", .45f, "Ballekere Halt", 198,
                "VNR", "DRU", listOf(RouteBetween.LegPoint("BLKR", "Ballekere Halt", 198, .45f)),
                RouteBetween.MarkerKind.SITE_INTERMEDIATE)
            siteEventMinute = 851
            showLabels = false // the named report cannot require a Stations tap
        }
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        fun paint(age: Long?, site: Boolean): android.graphics.Bitmap {
            ruler.ageSeconds = age
            ruler.siteEventMinute = null
            ruler.projection = ruler.projection!!.copy(markerKind = if (site)
                RouteBetween.MarkerKind.SITE_INTERMEDIATE else RouteBetween.MarkerKind.WEB_CACHE_CANDIDATE)
            return android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
                android.graphics.Bitmap.Config.ARGB_8888).also {
                ruler.paintForTest(android.graphics.Canvas(it))
            }
        }
        val plain = paint(null, false)
        val named = paint(null, true)
        val aged = paint(4 * 60, true)
        val rail = (RouteRailGeometry.X_DP * ctx.resources.displayMetrics.density).toInt()
        assertTrue("a folded map must still identify the source-named intermediate next to the train",
            (rail + 25 until named.width).any { x ->
                (0 until named.height).any { y -> named.getPixel(x, y) != plain.getPixel(x, y) }
            })
        val density = ctx.resources.displayMetrics.density
        val trainY = ruler.trainCenter()!!.second
        val ageTop = (trainY + 20 * density).toInt().coerceAtLeast(0)
        val ageBottom = (trainY + 38 * density).toInt().coerceAtMost(aged.height)
        assertTrue("the event age must be legible BELOW the logo without a Stations tap",
            ((rail - 24 * density).toInt().coerceAtLeast(0) until
                (rail + 24 * density).toInt().coerceAtMost(aged.width)).any { x ->
                (ageTop until ageBottom).any { y -> aged.getPixel(x, y) != named.getPixel(x, y) }
            })
        assertFalse("event age must no longer occupy the left timetable gutter",
            (0 until (rail - 24 * density).toInt()).any { x ->
                (0 until aged.height).any { y -> aged.getPixel(x, y) != named.getPixel(x, y) }
            })
        assertFalse("the selected site report must not automatically open all intermediate points",
            ruler.showLabels)
        ruler.showLabels = true
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        val expandedPlain = paint(null, false)
        val expandedNamed = paint(null, true)
        assertFalse("expanded stations must not repeat At station beside the locomotive",
            (0 until expandedNamed.width).any { x ->
                (0 until expandedNamed.height).any { y ->
                    expandedPlain.getPixel(x, y) != expandedNamed.getPixel(x, y)
                }
            })
    }

    @Test fun onlyARecentSourceEventPaintsTheGreenCheckOnTheLocomotive() {
        val ctx = RuntimeEnvironment.getApplication()
        val ruler = InlineLegView(ctx).apply {
            distanceKm = 20
            projection = RouteBetween.LegProjection("From", "To", 0f, "From", 100,
                "AAA", "BBB", emptyList(), RouteBetween.MarkerKind.REPORTED_HALT)
        }
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        fun pixelAtCheck(age: Long?): Int {
            ruler.ageSeconds = age
            val bmp = android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            // A bare renderer may skip painting before attachment in Robolectric.
            // Exercise the painter directly on this software canvas instead.
            ruler.paintForTest(android.graphics.Canvas(bmp))
            val d = ctx.resources.displayMetrics.density
            return bmp.getPixel(((RouteRailGeometry.X_DP + 15 + 5) * d).toInt(), ((24 - 14) * d).toInt())
        }
        assertEquals("<1 min must draw green at the icon's upper-right",
            ctx.getColor(R.color.accent), pixelAtCheck(30))
        assertTrue("a minute-old event must not display a freshness check",
            pixelAtCheck(60) != ctx.getColor(R.color.accent))
        assertTrue("unknown event time must not display a freshness check",
            pixelAtCheck(null) != ctx.getColor(R.color.accent))
    }

    @Test fun officialAndWebsitePaintTheSameRulerAndLocomotive() {
        val ctx = RuntimeEnvironment.getApplication()
        fun paint(kind: RouteBetween.MarkerKind): android.graphics.Bitmap {
            val ruler = InlineLegView(ctx)
            ruler.distanceKm = 20
            ruler.projection = RouteBetween.LegProjection("From", "To", 0f,
                "From", 100, "AAA", "BBB", listOf(
                    RouteBetween.LegPoint("MAP", "Untimed map point", 110, .5f)), kind)
            ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
            return android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
                android.graphics.Bitmap.Config.ARGB_8888).also {
                ruler.paintForTest(android.graphics.Canvas(it))
            }
        }
        val official = paint(RouteBetween.MarkerKind.REPORTED_HALT)
        val d = ctx.resources.displayMetrics.density
        assertEquals("source-neutral train body must actually be painted",
            ctx.getColor(R.color.live), official.getPixel(((RouteRailGeometry.X_DP - 16f) * d).toInt(), (24 * d).toInt()))
        assertTrue("switching from NTES to the website changed the route skin",
            official.sameAs(paint(RouteBetween.MarkerKind.SITE_LAST_STOP)))
    }

    @Test fun tappingAnOrdinaryStopShowsProportionalStationDotsWithoutATimeSheet() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.setRoutePoints(mapOf("YPR" to listOf(
            StopRow(2, "MWM", "Malleswaram", null, null, null,
                null, null, 0, 367, false),
            StopRow(3, "ABC", "Another station", null, null, null,
                null, null, 0, 370, false))))
        adapter.mappedAnchorKm = mapOf("YPR" to 366, "AAA" to 372)
        val from = TrackModel.Stop(1, "YPR", "Yesvantpur Jn", 670, 672,
            670, 672, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 366, 0, 6, true, true, false, -20)
        val next = TrackModel.Stop(2, "AAA", "Next stop", 684, 686,
            684, 686, null, TrackModel.MinuteSource.SCHEDULE, null,
            TrackModel.State.NEXT, true, 372, 6, 8, false, false, false, 4)
        adapter.submitList(listOf(from, next))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertFalse(holder.inlineLeg.showLabels)
        assertEquals("Stations ▾", holder.routeToggle.text.toString())
        assertTrue(holder.inlineLeg.compactStatic)
        holder.inlineLeg.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue(holder.inlineLeg.measuredHeight / ctx.resources.displayMetrics.density <= 58f)
        assertEquals(View.GONE, holder.details.visibility)
        assertEquals(View.VISIBLE, holder.schedArr.visibility)
        assertEquals(View.VISIBLE, holder.liveArr.visibility)
        assertEquals("11:10", holder.schedArr.text.toString())
        holder.inlineLeg.performClick() // full folded rail must open, not just the name
        assertEquals("Hide ▴", holder.routeToggle.text.toString())
        assertTrue(holder.inlineLeg.showLabels) // visible before RecyclerView's next bind
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertTrue(holder.inlineLeg.showLabels)
        assertFalse(holder.inlineLeg.showTrain)
        assertEquals(2, holder.inlineLeg.projection!!.points.size)
        assertEquals(367, holder.inlineLeg.projection!!.points[0].mapKm)
        assertEquals("intermediate clocks must use bounding schedule and km, never live progress",
            listOf(674, 680), holder.inlineLeg.projection!!.points.map { it.passageMinute })
        assertTrue(holder.inlineLeg.projection!!.points[0].approximate)
        assertTrue(holder.inlineLeg.contentDescription.contains("Malleswaram (MWM), approximately"))
        assertTrue(holder.inlineLeg.contentDescription.contains("no train-position claim"))
        assertEquals(View.GONE, holder.details.visibility)
        val positions = LegGeometry.pointPositionsDp(holder.inlineLeg.projection!!.points, 6)
        assertTrue("1 km and 3 km must create different vertical gaps",
            positions[0] - 24f < positions[1] - positions[0])
        holder.inlineLeg.performClick()
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertFalse(holder.inlineLeg.showLabels)
    }

    @Test fun emptyStaticMapExplainsWhyStationsCannotOpenAndOffersRetry() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        var explanations = 0
        adapter.onMapUnavailable = { _ -> explanations++ }
        adapter.setProjectedLeg(RouteBetween.LegProjection("Yesvantpur", "Tumkur", 0f,
            "Yesvantpur", 144, "YPR", "TK", emptyList(), RouteBetween.MarkerKind.REPORTED_HALT))
        adapter.haltMarker = TableMarker.Halt("YPR", "NTES YPR departure 23:42", true)
        adapter.legAgeSeconds = 53 * 60L
        val from = TrackModel.Stop(1, "YPR", "Yesvantpur", 1420, 1422,
            1420, 1422, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 144, 0, 55, false, false, false, -53)
        val to = from.copy(seq = 2, code = "TK", name = "Tumkur", kmFromOrigin = 199,
            isAnchor = false, source = TrackModel.MinuteSource.SCHEDULE)
        val third = to.copy(seq = 3, code = "TTR", name = "Tiptur", kmFromOrigin = 279)
        adapter.submitList(listOf(from, to, third))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertEquals("duplicate leg caption should be hidden", View.GONE,
            holder.inlineLegCaption.visibility)
        assertEquals("an empty map cannot offer an empty Stations disclosure", View.GONE,
            holder.routeToggle.visibility)
        assertEquals("duplicate stop banner should be hidden", View.GONE,
            holder.marker.visibility)
        assertEquals("event age belongs below the locomotive", "53m ago",
            holder.markerAge.text.toString())
        assertEquals("full event age remains accessible", "Last reported event 53 min ago",
            holder.markerAge.contentDescription.toString())
        assertEquals(View.VISIBLE, holder.markerAge.visibility)
        assertEquals(53 * 60L, holder.trainMarker.ageSeconds)
        holder.inlineLeg.performClick()
        assertEquals("the source-supported train leg still explains its unavailable map on tap",
            1, explanations)
        adapter.onBindViewHolder(holder, 1)
        holder.row.performClick() // even a plain, unmapped station gap explains itself
        assertEquals(2, explanations)
        adapter.onBindViewHolder(holder, 2)
        holder.row.performClick() // last stop has no following leg; no false promise
        assertEquals(2, explanations)
        adapter.onBindViewHolder(holder, 0)
        assertFalse(holder.inlineLeg.showLabels)
        assertEquals(0, holder.inlineLeg.projection!!.points.size)
    }

    @Test fun refreshAndAgeTickCannotDrawTwoLocomotivesForAReportedDeparture() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val departed = TrackModel.Stop(1, "BDVT", "Bhadravati", 770, 772,
            785, 810, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 250, 0, 19, true, false, false, 38)
        val next = departed.copy(seq = 2, code = "SMET", name = "Shimoga Town",
            schedArr = 845, schedDep = null, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE, isAnchor = false, kmFromOrigin = 269)
        adapter.submitList(listOf(departed, next))
        val candidate = TableMarker.Halt("BDVT", "NTES last reported departure", false)
        val cue = RouteBetween.LegProjection("Bhadravati", "Shimoga Town", .1f,
            "Shimoga Bidare", 250, "BDVT", "SMET", emptyList(),
            RouteBetween.MarkerKind.REPORTED_DEPARTURE_CUE)
        adapter.haltMarker = candidate
        adapter.setProjectedLeg(cue)
        assertEquals("binding a departure cue must remove the old station train",
            null, adapter.haltMarker)
        // Simulate the 30-second age ticker and another refresh; neither can
        // re-introduce the row locomotive while the outbound cue remains.
        repeat(2) { adapter.haltMarker = candidate }
        assertEquals(null, adapter.haltMarker)
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.trainMarker.visibility)
        assertTrue("the outbound cue should keep the sole locomotive", holder.inlineLeg.showTrain)
        adapter.setProjectedLeg(null)
        adapter.haltMarker = candidate
        assertEquals("without a departure cue the reported stop regains its train",
            candidate, adapter.haltMarker)
    }

    @Test fun birurMarkerIsOnItsStationAndWholeLegTogglesExactlyTwoMappedPoints() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        val points = listOf(
            StopRow(2, "SPV", "Sivapur", null, null, null,
                null, null, 0, 215, false),
            StopRow(3, "KRNH", "Koranahalli", null, null, null,
                null, null, 0, 220, false))
        adapter.setRoutePoints(mapOf("RRB" to points))
        adapter.setProjectedLeg(RouteBetween.LegProjection("Birur Jn", "Tarikere Jn", 0f,
            "Birur Jn", 211, "RRB", "TKE", emptyList(), RouteBetween.MarkerKind.SITE_LAST_STOP))
        adapter.haltMarker = TableMarker.Halt("RRB", "Site's last past stop · 2m ago", true)
        adapter.legAgeSeconds = 120
        val birur = TrackModel.Stop(1, "RRB", "Birur Jn", 1205, 1206, 1230,
            null, null, TrackModel.MinuteSource.SITE_REPORTED, "2",
            TrackModel.State.PASSED, true, 211, 0, 24, false, false, false, -2)
        val tarikere = birur.copy(seq = 2, code = "TKE", name = "Tarikere Jn",
            schedArr = 1230, schedDep = 1231, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE, isAnchor = false, kmFromOrigin = 235)
        adapter.submitList(listOf(birur, tarikere))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        assertEquals(View.GONE, holder.dot.visibility)
        assertFalse("second locomotive cannot occupy the leg below Birur", holder.inlineLeg.showTrain)
        assertEquals("the train belongs to Birur, not the next dot", "RRB",
            adapter.haltMarker?.code)
        assertFalse(holder.inlineLeg.showLabels)
        holder.inlineLegGroup.performClick() // any blank part of the leg, not a button
        adapter.onBindViewHolder(holder, 0)
        assertTrue(holder.inlineLeg.showLabels)
        assertEquals(listOf("SPV", "KRNH"), holder.inlineLeg.projection!!.points.map { it.code })
        assertEquals(listOf(215, 220), holder.inlineLeg.projection!!.points.map { it.mapKm })
        assertTrue(holder.inlineLeg.contentDescription.contains("215 km"))
        assertTrue(holder.inlineLeg.contentDescription.contains("220 km"))
        val d = ctx.resources.displayMetrics.density
        val content = holder.itemView
        content.measure(View.MeasureSpec.makeMeasureSpec((360 * d).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val frame = holder.trainMarker.parent as View
        val locomotiveX = frame.left + holder.trainMarker.left + holder.trainMarker.width / 2
        assertEquals("the locomotive must be centred on the station rail",
            (RouteRailGeometry.X_DP * d).toInt(), locomotiveX)
        // Keep both time pairs on their OWN sides of that same rail, including at
        // narrow widths; a coordinate assertion catches a cosmetic-only reorder.
        val scheduleBounds = android.graphics.Rect(0, 0, holder.schedArr.width, holder.schedArr.height)
        val reportBounds = android.graphics.Rect(0, 0, holder.liveArr.width, holder.liveArr.height)
        val group = content as android.view.ViewGroup
        group.offsetDescendantRectToMyCoords(holder.schedArr, scheduleBounds)
        group.offsetDescendantRectToMyCoords(holder.liveArr, reportBounds)
        assertTrue("scheduled arrival overlaps the rail: $scheduleBounds",
            scheduleBounds.right < locomotiveX)
        assertTrue("reported arrival crosses to the schedule side: $reportBounds",
            reportBounds.left > locomotiveX)
        // Both descendants have different parents: compare in station-row coordinates,
        // including the identity column's top inset (not their local top values).
        val iconBounds = android.graphics.Rect(0, 0, holder.trainMarker.width, holder.trainMarker.height)
        val nameBounds = android.graphics.Rect(0, 0, holder.name.width, holder.name.height)
        group.offsetDescendantRectToMyCoords(holder.trainMarker, iconBounds)
        group.offsetDescendantRectToMyCoords(holder.name, nameBounds)
        assertTrue("the icon slipped below the Birur station name: icon=$iconBounds name=$nameBounds",
            kotlin.math.abs(iconBounds.centerY() - nameBounds.centerY()) < (25 * d).toInt())
        val stationBitmap = android.graphics.Bitmap.createBitmap(content.width, content.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        val stationCanvas = android.graphics.Canvas(stationBitmap)
        stationCanvas.drawColor(ctx.getColor(R.color.surface))
        content.draw(stationCanvas)
        Render.save("12-birur-train-at-stop-with-two-map-points", stationBitmap)
        holder.inlineLeg.performClick() // on the rail itself closes it again
        adapter.onBindViewHolder(holder, 0)
        assertFalse(holder.inlineLeg.showLabels)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        val ruler = holder.inlineLeg
        // A second tap must really close the stations; no anonymous small dots
        // may remain on the otherwise plain route rail.
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        val closed = android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        val closedCanvas = android.graphics.Canvas(closed)
        closedCanvas.drawColor(ctx.getColor(R.color.surface))
        ruler.paintForTest(closedCanvas)
        val pointFraction = ruler.projection!!.points[0].fraction
        val collapsedY = 24f + (ruler.height / d - 48f) * pointFraction
        assertEquals("hidden intermediate point still leaves an unexplained dot",
            ctx.getColor(R.color.stroke),
            closed.getPixel((RouteRailGeometry.X_DP * d).toInt(), (collapsedY * d).toInt()))
        ruler.showLabels = true
        ruler.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        ruler.layout(0, 0, ruler.measuredWidth, ruler.measuredHeight)
        val bmp = android.graphics.Bitmap.createBitmap(ruler.width, ruler.height,
            android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(ctx.getColor(R.color.surface))
        ruler.paintForTest(canvas)
        Render.save("11-birur-to-tarikere-two-points", bmp)
    }

    @Test fun historicalCorridorOpensStationsButMappedEmptyLegNeverShowsFailure() {
        val ctx = RuntimeEnvironment.getApplication()
        val adapter = StopAdapter()
        adapter.mapSource = `in`.opentrack.india.remote.OfflineCorridorMap.SOURCE
        adapter.mappedLegStarts = setOf("TK", "MWM")
        var unavailable = 0
        adapter.onMapUnavailable = { _ -> unavailable++ }
        adapter.setRoutePoints(mapOf("TK" to listOf(
            StopRow(0, "HEI", "Heggere", null, null, null,
                null, null, 0, 218, false),
            StopRow(0, "MLSA", "Mallasandra", null, null, null,
                null, null, 0, 222, false))))
        val from = TrackModel.Stop(1, "TK", "Tumkur", 1450, 1452,
            1470, 1472, null, TrackModel.MinuteSource.REPORTED, null,
            TrackModel.State.PASSED, true, 213, 0, 70, false, false, false, -44)
        val to = from.copy(seq = 2, code = "TTR", name = "Tiptur",
            kmFromOrigin = 284, source = TrackModel.MinuteSource.SCHEDULE,
            showArr = null, showDep = null, isAnchor = false)
        adapter.submitList(listOf(from, to))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("Stations ▾", holder.routeToggle.text.toString())
        holder.inlineLeg.performClick()
        assertTrue(holder.inlineLeg.showLabels)
        assertEquals(listOf("HEI", "MLSA"), holder.inlineLeg.projection!!.points.map { it.code })
        assertTrue(holder.inlineLeg.projection!!.points.all { it.passageMinute == null })
        assertTrue(holder.inlineLeg.historicalMap)
        assertEquals(0, unavailable)
        val malleshwaram = from.copy(code = "MWM", name = "Malleswaram", kmFromOrigin = 143)
        val emptyLeg = StopAdapter()
        emptyLeg.mapSource = adapter.mapSource
        emptyLeg.mappedLegStarts = adapter.mappedLegStarts
        emptyLeg.onMapUnavailable = { _ -> unavailable++ }
        emptyLeg.submitList(listOf(malleshwaram, to.copy(code = "YPR", kmFromOrigin = 146)))
        val emptyHolder = emptyLeg.onCreateViewHolder(FrameLayout(ctx), 0)
        emptyLeg.onBindViewHolder(emptyHolder, 0)
        assertEquals("no-point leg must not repeat map status prose", View.GONE,
            emptyHolder.inlineLegGroup.visibility)
        assertEquals(View.GONE, emptyHolder.routeToggle.visibility)
        assertFalse("empty historical leg should not pop a toast or map dialog",
            emptyHolder.row.isClickable)
        assertEquals(0, unavailable)
    }

    @Test fun arrowsHaveOppositeDiagonalTipsAndSourceLightIsStronger() {
        val ctx = RuntimeEnvironment.getApplication()
        val d = ctx.resources.displayMetrics.density
        fun draw(arrive: Boolean, report: Boolean): android.graphics.Bitmap {
            val arrow = DirectionArrowDrawable(ctx, arrive, report)
            arrow.phaseForTest = .6f
            val bitmap = android.graphics.Bitmap.createBitmap((22*d).toInt(), (20*d).toInt(),
                android.graphics.Bitmap.Config.ARGB_8888)
            arrow.setBounds(0, 0, bitmap.width, bitmap.height)
            arrow.draw(android.graphics.Canvas(bitmap))
            return bitmap
        }
        val arrival = draw(true, true)
        val departure = draw(false, true)
        fun alpha(bitmap: android.graphics.Bitmap, x: Float, y: Float) =
            android.graphics.Color.alpha(bitmap.getPixel((x*d).toInt(), (y*d).toInt()))
        assertTrue("arrival must glow at lower-right, not upper-left",
            alpha(arrival, 18f, 17f) > alpha(arrival, 3f, 17f))
        assertTrue("departure must point lower-left, not upper-left",
            alpha(departure, 3f, 17f) > alpha(departure, 3f, 3f))
        // The reported highlight moves along the shaft, not a stationary halo at its tip.
        val early = DirectionArrowDrawable(ctx, true, true).apply { phaseForTest = .1f }
        val late = DirectionArrowDrawable(ctx, true, true).apply { phaseForTest = .85f }
        fun frame(arrow: DirectionArrowDrawable): android.graphics.Bitmap {
            val bitmap = android.graphics.Bitmap.createBitmap((22*d).toInt(), (20*d).toInt(),
                android.graphics.Bitmap.Config.ARGB_8888)
            arrow.setBounds(0, 0, bitmap.width, bitmap.height)
            arrow.draw(android.graphics.Canvas(bitmap))
            return bitmap
        }
        val before = frame(early)
        val after = frame(late)
        assertTrue("light must leave the tail", alpha(before, 5f, 5f) > alpha(after, 5f, 5f))
        assertTrue("light must reach the head", alpha(after, 16f, 15f) > alpha(before, 16f, 15f))
    }
}
