package `in`.opentrack.india.domain

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one promise of `TrackModel`: the sentence at the top of the tracking screen and the row it is
 * about cannot disagree.
 *
 * That is not a hypothetical. The screen once read "departed TIPTUR" while the Tiptur row below it was
 * marked as not yet reached, because the header came from the position engine and the rows from a second
 * comparison written in the ViewModel. Every test here is a guard on that seam: build a position, then
 * assert that the headline, the highlighted row and its state are all the same fact.
 *
 * Fixture: SRC 06:00 → MID 10:00/10:10 (100 km) → DST 11:40 (200 km), the same shape as
 * [PositionEngineTest] so the two files can be read together.
 */
class TrackModelTest {

    private fun stop(seq: Int, code: String, arr: Int?, dep: Int?, km: Int) = StopRow(
        seq = seq, code = code, name = "$code Jn", state = "GJ", lat = null, lon = null,
        arrivalMin = arr, departureMin = dep, journeyDay = if ((arr ?: dep ?: 0) >= 1440) 2 else 1,
        schedKm = km, isHalt = true,
    )

    private fun route(kmAllZero: Boolean = false, lastStatus: StopStatus = StopStatus.UNKNOWN) =
        TrainRoute(
            trainNo = "12951", trainName = "TEST EXPRESS", srcCode = "SRC", srcName = "Src Jn",
            dstCode = "DST", dstName = "Dst Jn", totalKm = if (kmAllZero) 0 else 200, totalMin = 340,
            runDays = null, returnNo = null,
            stops = listOf(
                stop(1, "SRC", null, 360, if (kmAllZero) 0 else 0),
                stop(2, "MID", 600, 610, if (kmAllZero) 0 else 100),
                stop(3, "DST", 700, null, if (kmAllZero) 0 else 200).copy(status = lastStatus),
            ),
            segments = listOf(
                SegmentRow(1, "SRC", "MID", if (kmAllZero) 0 else 100, 240),
                SegmentRow(2, "MID", "DST", if (kmAllZero) 0 else 100, 90),
            ),
        )

    private fun pos(
        anchor: Int, next: Int, status: StopStatus, fraction: Float = 0f, kmAlong: Float = 0f,
        delay: Int? = null, kind: SourceKind = SourceKind.PROVIDER,
    ) = TrainPosition(
        anchorSeq = anchor, nextSeq = next, fraction = fraction, kmAlong = kmAlong, status = status,
        etaNextMinute = null, delayMinutes = delay, remainingMin = null, provenance = kind,
        confidence = TrainPosition.Confidence.HIGH, ageSeconds = 5,
    )

    /** The overnight shape of the same train: MID and DST are reached on day 2 of the run. */
    private fun overnightRoute() = route().copy(
        stops = listOf(
            stop(1, "SRC", null, 1380, 0),            // boards 23:00, day 1
            stop(2, "MID", 1440, 1450, 100),         // 00:00 / 00:10 (+1d)
            stop(3, "DST", 1470, null, 200),         // 00:30 (+1d)
        ),
    )

    private fun model(
        route: TrainRoute = route(), pos: TrainPosition,
        perStop: Map<String, LiveStopFields> = emptyMap(), now: Int = 620,
        board: Int = 1, alight: Int = 3, note: String? = null,
    ) = TrackModel.build(route, pos, perStop, now, board, alight, 19949L, note)

    @Test
    fun nextStopUsesEstimatedArrivalNotScheduledDepartureAtMidnight() {
        val r = overnightRoute()
        val reported = mapOf("SRC" to LiveStopFields(actualDepartureMin = 1387,
            delayDepartureMin = 7))
        val m = model(r, pos(1, 2, StopStatus.DEPARTED, delay = 7), reported, now = 1435)
        assertEquals("Next: MID Jn · 00:07 (+1d) expected · in 12 min", m.nextLabel)
        val overdue = model(r, pos(1, 2, StopStatus.DEPARTED, delay = 7), reported, now = 1449)
        assertTrue(overdue.nextLabel.contains("estimate overdue; arrival unconfirmed"))
    }

    @Test
    fun positionOnlyShowsReportedHaltWhileFutureRemainsStatic() {
        val r = overnightRoute()
        val p = pos(1, 2, StopStatus.DEPARTED, fraction = .75f,
            kmAlong = 75f, delay = 7, kind = SourceKind.PROVIDER_NTES)
        val rows = mapOf(
            "SRC" to LiveStopFields(actualDepartureMin = 1387, delayDepartureMin = 7),
            "MID" to LiveStopFields(actualArrivalMin = 1447, delayArrivalMin = 7),
        )
        val m = TrackModel.build(r, p, rows, 1435, 1, 3, 19949L, null,
            positionOnly = true)
        assertTrue(m.headline.contains("Last reported: departed SRC Jn"))
        assertTrue(m.headline.contains("current position unknown"))
        assertEquals("Next scheduled: MID Jn · 00:00 (+1d) · arrival not confirmed", m.nextLabel)
        assertEquals(TrackModel.MinuteSource.SCHEDULE, m.rows[1].source)
        assertEquals(TrackModel.MinuteSource.REPORTED, m.rows[0].source)
        assertEquals(0, m.progressPct)
        assertEquals(-1, m.schedulePct)
        assertFalse(m.progressLabel.contains("km left"))
    }

    @Test
    fun stationClaimCannotTurnTheScheduledMinuteIntoAReportedPassageTime() {
        val m = TrackModel.build(route(),
            pos(2, 3, StopStatus.DEPARTED, kind = SourceKind.PROVIDER_OVERRIDE).copy(
                feedAgeSeconds = 180, ageSeconds = 3),
            emptyMap(), 620, 1, 3, 19949L, null, positionOnly = true)
        assertTrue(m.headline.contains("Last reported: departed MID Jn"))
        assertFalse("schedule must not be reported as departure: ${m.headline}",
            m.headline.contains("10:10"))
        assertEquals(TrackModel.MinuteSource.SCHEDULE, m.rows[1].source)
        // The confirmed station is halfway along the scheduled route, not at its origin.
        assertEquals(50, m.progressPct)
        assertTrue(m.progressLabel.contains("Last reported halt"))
        assertEquals("feed 3 min old", m.ageLabel)
    }

    @Test
    fun cachedOfficialPastStopNeverClaimsLivePositionOrForecast() {
        val r = overnightRoute()
        val reported = mapOf("SRC" to LiveStopFields(actualDepartureMin = 1387,
            delayDepartureMin = 7))
        val m = model(r, pos(1, 2, StopStatus.DEPARTED, delay = 7,
            kind = SourceKind.CACHED), reported, now = 1435)
        assertTrue(m.headline.contains("current position unknown"))
        assertTrue(m.nextLabel.startsWith("Next scheduled: MID Jn · 00:00 (+1d)"))
        assertEquals(TrackModel.MinuteSource.SCHEDULE, m.rows[1].source)
        assertEquals(TrackModel.MinuteSource.REPORTED, m.rows[0].source)
    }

    @Test
    fun theEndpointOnlyRouteIsNeverShownAsAZeroKmLeg() {
        // 20689 on the owner's device (2026-10-12): the offline copy lacks the train, the screen
        // renders the live source's two endpoints - and the old headline read "4h 49m into the
        // 0 km leg", "1 of 1 stops covered". Neither sentence may exist for this shape.
        val synthetic = TrainRoute(
            trainNo = "20689", trainName = "SMET INTERCITY", srcCode = "YPR", srcName = "YPR",
            dstCode = "SMET", dstName = "SMET", totalKm = 0, totalMin = null, runDays = null,
            returnNo = null,
            stops = listOf(
                stop(1, "YPR", null, 555, 0),
                stop(2, "SMET", 845, null, 0),
            ),
            segments = emptyList(),
        )
        val m = model(
            route = synthetic,
            pos = pos(1, 2, StopStatus.DEPARTED, fraction = 0.5f, kind = SourceKind.OFFLINE_SCHEDULE),
            now = 844, board = 1, alight = 2,
        )
        assertTrue("got: ${m.headline}", m.headline.startsWith("Endpoints only"))
        assertFalse("got: ${m.headline}", m.headline.contains("0 km"))
        assertTrue("got: ${m.progressLabel}", m.progressLabel.contains("endpoint times only"))
        assertFalse("got: ${m.progressLabel}", m.progressLabel.contains("1 of 1"))
    }

    @Test
    fun aRunFinishedHoursAgoIsNotToldAsLeavingItsTerminus() {
        // 20652 at the owner's 15:14 paste: scheduled into SBC at 12:00; at 15:14 the screen said
        // "Should have left Bangalore City Jn by now" - the >90-minute rule compared RAIL MINUTES
        // against 90*60 (ninety hours) and could never fire; a terminus cannot be "left" anyway.
        val m = model(
            pos = pos(3, 3, StopStatus.DEPARTED, kind = SourceKind.OFFLINE_SCHEDULE),
            now = 894, board = 1, alight = 3,
        )
        assertTrue("got: ${m.headline}", m.headline.startsWith("Should have finished the run"))
        assertFalse("got: ${m.headline}", m.headline.contains("left"))
    }

    // ------------------------------------------------------------- reported vs expected vs timetable

    @Test
    fun aSingleReportedCellNeverConvertsTheOtherScheduledCellIntoAnActual() {
        val m = model(pos = pos(2, 3, StopStatus.DEPARTED),
            perStop = mapOf("MID" to LiveStopFields(actualArrivalMin = 606,
                delayArrivalMin = 6)), now = 650)
        assertEquals(TrackModel.MinuteSource.REPORTED, m.rows[1].source)
        assertEquals(606, m.rows[1].showArr)
        assertEquals(null, m.rows[1].showDep)
        val depOnly = model(pos = pos(2, 3, StopStatus.DEPARTED),
            perStop = mapOf("MID" to LiveStopFields(actualDepartureMin = 615)), now = 650)
        assertEquals(null, depOnly.rows[1].showArr)
        assertEquals(615, depOnly.rows[1].showDep)
    }

    @Test
    fun aReportedClockTimeIsPlacedOnTheDayItsScheduleBelongsTo() {
        // The day-2 bug, end to end: a feed answers "00:02" for a halt the table puts at 00:00 (+1d). Printed
        // as given, the two cells in one row are 24 hours apart and the delay reads "-1438".
        val r = overnightRoute()
        val m = model(
            route = r, pos = pos(2, 3, StopStatus.DEPARTED),
            perStop = mapOf("MID" to LiveStopFields(actualArrivalMin = 2)), now = 1445,
        )
        assertEquals("a day-blind clock must be put on the run's own day", 1442, m.rows[1].showArr)
        assertEquals("00:02 (+1d)", RailTime.formatRailMinute(m.rows[1].showArr!!))
        assertEquals("the row the sentence is about is still this one", true, m.rows[1].isAnchor)
        assertTrue("a halt two minutes late is not 23 hours early: '${m.delayLabel}'",
            m.delayLabel != "23h 38m early")
    }

    @Test
    fun withNoFeedAtAllEveryRowIsTheTimetableAndNotAForecast() {
        // The presentation bug this guards: one `estimated` boolean made "no live data" and "we expect a
        // slip" the same state, so an offline screen printed every upcoming stop in projection amber and
        // called it "expected" — a forecast manufactured out of an empty field.
        val m = model(
            pos = pos(0, 1, StopStatus.UNKNOWN, kind = SourceKind.OFFLINE_SCHEDULE),
            perStop = emptyMap(), now = 700,
        )
        assertTrue("a no-feed screen must label every row as the timetable: " +
            m.rows.joinToString { "${it.code}=${it.source}" },
            m.rows.all { it.source == TrackModel.MinuteSource.SCHEDULE })
        assertTrue(m.rows.none { it.estimated })
        assertTrue(m.rows.all { it.showArr == it.schedArr && it.showDep == it.schedDep })
        assertTrue("the Delay cell is about 90dp wide: '${m.delayLabel}'", m.delayLabel.length <= 16)
    }

    @Test
    fun anOfflineScreenNeverCallsItsOwnDataLive() {
        // The age chip is where "did anything arrive?" is answered, so a schedule-only run must not write
        // "live" in it — `ageSeconds` is 0 there, and `age(0)` said exactly that before this test existed.
        val m = model(pos = pos(0, 1, StopStatus.UNKNOWN, kind = SourceKind.OFFLINE_SCHEDULE), now = 700)
        assertEquals("no live fetch", m.ageLabel)
        assertFalse("the provenance badge must not read LIVE: '${m.provenanceLabel}'",
            m.provenanceLabel.contains("LIVE"))
        assertTrue("a no-feed headline has to hedge: '${m.headline}'",
            m.headline.contains("Should") || m.headline.contains("not available", true))
        // "codes · km" and nothing else: at 360 dp a third clause was ellipsised ("SBC → SMET · 274 km · …"),
        // and a truncated header is the first thing a traveller reads. `ScreenRenderTest` proves the pixels.
        assertTrue("header line should be codes then distance, one separator each: '${m.routeLabel}'",
            m.routeLabel.count { it == '·' } == 1 && m.routeLabel.endsWith("km"))
        // Casing of the names *inside* a sentence is asserted in `ScreenRenderTest`, where the fixture's
        // station names are all-caps the way the source data has them; this file's fixtures are already
        // mixed case, so a check here would pass without looking at anything.
    }

    @Test
    fun aLiveDelayLeavesStopsBeyondNextOnThePublishedSchedule() {
        val m = model(
            pos = pos(1, 2, StopStatus.DEPARTED, delay = 12),
            perStop = mapOf("MID" to LiveStopFields(actualArrivalMin = 606, delayArrivalMin = 6)),
            now = 620,
        )
        assertEquals(TrackModel.MinuteSource.REPORTED, m.rows[1].source)
        assertEquals(TrackModel.MinuteSource.SCHEDULE, m.rows[2].source)
        assertEquals(m.rows[2].schedArr, m.rows[2].showArr)
    }

    @Test
    fun onlyImmediateNextStopCarriesLiveDelay() {
        val base = route()
        val distant = stop(4, "END", 820, null, 270)
        val r = base.copy(dstCode = "END", dstName = "End Jn",
            stops = base.stops + distant,
            segments = base.segments + SegmentRow(3, "DST", "END", 70, 120))
        val m = model(route = r, pos = pos(2, 3, StopStatus.DEPARTED, delay = 14), now = 620)
        assertEquals(714, m.rows[2].showArr)
        assertEquals(TrackModel.MinuteSource.PROJECTED, m.rows[2].source)
        assertEquals(820, m.rows[3].showArr)
        assertEquals(TrackModel.MinuteSource.SCHEDULE, m.rows[3].source)
    }

    // ------------------------------------------------------------------ headline ↔ rows

    @Test
    fun departedPositionMakesTheAnchorRowPassedAndTheNextRowNext() {
        val m = model(pos = pos(2, 3, StopStatus.DEPARTED, fraction = 0.5f, kmAlong = 150f))
        assertEquals(TrackModel.State.PASSED, m.rows[0].state)
        assertEquals(TrackModel.State.PASSED, m.rows[1].state)
        assertTrue("the row the headline is about must be the highlighted one", m.rows[1].isAnchor)
        assertEquals(TrackModel.State.NEXT, m.rows[2].state)
        assertEquals(1, m.rows.count { it.isAnchor })
        assertEquals("Between MID Jn and DST Jn", m.headline)
    }

    @Test
    fun arrivedPositionSaysHereOnTheSameRow() {
        val m = model(pos = pos(2, 3, StopStatus.ARRIVED, kmAlong = 100f))
        assertEquals(TrackModel.State.HERE, m.rows[1].state)
        assertTrue(m.rows[1].isAnchor)
        assertEquals("At MID Jn", m.headline)
    }

    @Test
    fun offlinePositionNeverClaimsADepartureAndNeverClaimsOnTime() {
        val m = model(
            pos = pos(2, 3, StopStatus.UNKNOWN, kmAlong = 100f, kind = SourceKind.OFFLINE_SCHEDULE),
            now = 900,
        )
        assertTrue("got: ${m.headline}", m.headline.startsWith("Should"))
        assertFalse("a timetable must not report a movement", m.headline.contains("departed", true))
        assertFalse("0 delay would read as a confirmed fact", m.delayLabel.contains("on time"))
        assertTrue(m.delayLabel.contains("no live feed"))
        assertFalse(m.isLive)
        // Every stop whose scheduled departure is behind us is passed, and nothing else is.
        assertEquals(listOf(true, true, true), m.rows.map { (it.schedDep ?: it.schedArr!!) <= 900 })
    }

    @Test
    fun oneRuleOrdersTheRowsWhenThereIsNoReport() {
        // 10:20, the day's third stop not yet due: SRC and MID have gone, DST is next. The anchor the
        // engine picked (MID) is passed by the same rule the rows use, so no second decision is needed.
        val m = model(
            pos = pos(2, 3, StopStatus.DEPARTED, kmAlong = 100f, kind = SourceKind.OFFLINE_SCHEDULE),
            now = 620,
        )
        assertEquals(
            listOf(TrackModel.State.PASSED, TrackModel.State.PASSED, TrackModel.State.NEXT),
            m.rows.map { it.state },
        )
        assertTrue(m.rows[1].isAnchor)
    }

    @Test
    fun cancelledStopBecomesTheHeadlineRatherThanAPassedStation() {
        val r = route(lastStatus = StopStatus.CANCELLED_HERE)
        val m = model(route = r, pos = pos(3, 3, StopStatus.CANCELLED_HERE, kmAlong = 200f))
        assertEquals(TrackModel.State.CANCELLED, m.rows[2].state)
        assertEquals("Cancelled at DST Jn", m.headline)
        assertTrue(m.tone == TrackModel.Tone.PROBLEM)
    }

    // ------------------------------------------------------------------ scheduled vs projected

    @Test
    fun anchorDelayIsCarriedForwardToLaterStopsAndNeverBackwards() {
        val m = model(pos = pos(2, 3, StopStatus.DEPARTED, delay = 14))
        // MID itself: the anchor minute is the report's own, so nothing is invented for it — and since
        // nothing moved it, it is the timetable, not a projection. (It used to be labelled "estimated",
        // which is the claim this file exists to prevent: a screen may not call a number a forecast when
        // the only thing that produced it is the schedule.)
        assertEquals("no delay is asserted at the anchor minute", null, m.rows[1].delay)
        assertEquals(600, m.rows[1].showArr)
        assertEquals(TrackModel.MinuteSource.SCHEDULE, m.rows[1].source)
        assertFalse("the anchor's own minute must not be dressed up as a forecast", m.rows[1].estimated)
        // DST: schedule plus the delay already established at MID, marked as a projection.
        assertEquals(14, m.rows[2].delay)
        assertEquals(714, m.rows[2].showArr)
        assertTrue("a forecast must look like a forecast", m.rows[2].estimated)
        assertEquals(TrackModel.MinuteSource.PROJECTED, m.rows[2].source)
        // SRC is behind the anchor and unreported: no number at all, not 0 and not +14.
        assertEquals(null, m.rows[0].delay)
        assertEquals(360, m.rows[0].showDep)
        assertEquals("behind the anchor and unreported is still the timetable",
            TrackModel.MinuteSource.SCHEDULE, m.rows[0].source)
    }

    @Test
    fun aConfirmedMinuteBeatsTheProjection() {
        val m = model(
            pos = pos(2, 3, StopStatus.DEPARTED, delay = 14),
            perStop = mapOf(
                "MID" to LiveStopFields(
                    actualArrivalMin = 605, delayArrivalMin = -5, platform = "3",
                )
            ),
        )
        assertEquals(605, m.rows[1].showArr)
        assertEquals(-5, m.rows[1].delay)
        assertFalse("this minute was reported, not inferred", m.rows[1].estimated)
        assertEquals("3", m.rows[1].platform)
    }

    @Test
    fun nextStopLineNamesTheKindOfMinuteItShows() {
        val projected = model(pos = pos(2, 3, StopStatus.DEPARTED, delay = 14))
        assertTrue(
            "a projected minute has to say so, in: ${projected.nextLabel}",
            projected.nextLabel.contains("expected"),
        )
        assertTrue(
            "the next stop's name has to survive the casing, in: ${projected.nextLabel}",
            projected.nextLabel.contains("DST Jn"),
        )
        val confirmed = model(
            pos = pos(2, 3, StopStatus.DEPARTED),
            perStop = mapOf("DST" to LiveStopFields(actualArrivalMin = 702, delayArrivalMin = 2)),
        )
        assertTrue(
            "a confirmed minute must not claim to be a forecast, in: ${confirmed.nextLabel}",
            confirmed.nextLabel.contains("due"),
        )
    }

    // ------------------------------------------------------------------ distance arithmetic

    @Test
    fun distancesAreMeasuredFromTheBoardingStopNotTheTrainsOrigin() {
        // Boarding at MID is the case a passenger cares about: "how far have *I* got" must not be
        // answered with the train's own odometer.
        val m = model(pos = pos(2, 3, StopStatus.ARRIVED, kmAlong = 100f), board = 2, alight = 3)
        assertEquals(0, m.rows[1].kmFromBoard)
        assertEquals(100, m.rows[2].kmFromBoard)
        assertEquals(0, m.rows[2].kmRemaining)
        assertEquals(0, m.rows[0].kmFromBoard)   // before the boarding stop: never negative
        assertTrue(m.rows[1].isBoarding)
        assertTrue(m.rows[2].isAlighting)
    }

    @Test
    fun progressIsCoveredOverTheSegmentAndSaysSo() {
        val m = model(pos = pos(2, 3, StopStatus.DEPARTED, fraction = 0.5f, kmAlong = 150f))
        assertEquals("150 of 200 km · 50 km left", m.progressLabel)
        assertEquals(75, m.progressPct)
        assertTrue(m.hasDistances)
    }

    @Test
    fun progressFallsBackToStopsAndAdmitsTheMissingData() {
        val m = model(
            route = route(kmAllZero = true),
            pos = pos(2, 3, StopStatus.DEPARTED, kind = SourceKind.OFFLINE_SCHEDULE),
            now = 620,
        )
        assertFalse(m.hasDistances)
        assertTrue("got: ${m.progressLabel}", m.progressLabel.contains("distances unavailable"))
        assertEquals("no tick to draw when there is no distance axis", -1, m.schedulePct)
    }

    @Test
    fun scheduleTickSitsWhereTheTimetablePutsTheTrain() {
        // Reported at DST (200 km, 11:40 arrived) while the timetable had it at MID: the bar must show
        // the gap, which is the delay made visible.
        val m = model(
            pos = pos(3, 3, StopStatus.ARRIVED, kmAlong = 200f, delay = 20),
            now = 620,   // 10:20: the timetable still has it at MID (100 km), the report has it at DST
        )
        assertEquals(100, m.progressPct)
        assertEquals(50, m.schedulePct)
    }

    @Test
    fun noteIsPassedThroughWhenThereIsNothingToSay() {
        val m = model(
            pos = pos(2, 3, StopStatus.ARRIVED, kind = SourceKind.OFFLINE_SCHEDULE),
            note = "no live report reached this device",
        )
        assertEquals("no live report reached this device", m.note)
    }

    // ------------------------------------------------------------------ shared wording

    @Test
    fun gapWordsReadTheSameOnBothSidesOfNow() {
        assertEquals("in 40 min", TrackModel.formatGap(40))
        assertEquals("42 min ago", TrackModel.formatGap(-42))
        assertEquals("right now", TrackModel.formatGap(0))
        assertEquals("in 1h 30m", TrackModel.formatGap(90))
    }

    @Test
    fun passedCountCountsTheAnchoredRowOnce() {
        val m = model(pos = pos(2, 3, StopStatus.DEPARTED))
        assertEquals(2, TrackModel.passedCount(m.rows))
    }

    // ---------------------------------------------------------------- the age chip
    //
    // The chip used to report the age of *our request*, so a feed nobody had refreshed in six hours said
    // "live". These pins are the difference between "we read the network a moment ago" and "the fact
    // itself is this old", which is the sentence a traveller decides whether to trust the screen by.

    @Test
    fun theAgeChipPrefersTheFeedsOwnStamp() {
        val m = model(pos = pos(2, 3, StopStatus.DEPARTED).copy(feedAgeSeconds = 40 * 60L))
        assertEquals("feed 40 min old", m.ageLabel)
        val fresh = model(pos = pos(2, 3, StopStatus.DEPARTED).copy(feedAgeSeconds = 30L))
        assertEquals("feed is current", fresh.ageLabel)
        val hours = model(pos = pos(2, 3, StopStatus.DEPARTED).copy(feedAgeSeconds = 7 * 3600L))
        assertEquals("feed 7 h old", hours.ageLabel)
    }

    @Test
    fun theDisclosureCarriesTheFeedsOwnClockNotOnlyItsAge() {
        // "4 min old" tells you whether to trust the screen; "stamped 07:56 IST" is what lets you check it
        // against your own watch. Both come from the provider's stamp, and neither may be inferred from the
        // moment our request happened to finish.
        val stamp = RailTime.daysFromCivil(2026, 10, 11) * 86_400_000L + 7 * 3_600_000L +
            56 * 60_000L - RailTime.IST_OFFSET_MILLIS
        val m = model(pos = pos(2, 3, StopStatus.DEPARTED).copy(
            feedAgeSeconds = 240L, feedStampMillis = stamp,
        ))
        assertTrue("said: ${m.feedStampLabel}", m.feedStampLabel.startsWith("feed stamped "))
        assertTrue("said: ${m.feedStampLabel}", m.feedStampLabel.contains("IST"))
        // Four minutes is deliberately *not* called "current": the chip says that only under two minutes,
        // because the whole point of the age is to let you decide whether to trust the minute printed above
        // it. Softening 4 min into "current" is the mistake this pin exists to prevent.
        assertEquals("feed 4 min old", m.ageLabel)
        val unmarked = model(pos = pos(1, 2, StopStatus.DEPARTED, kind = SourceKind.OFFLINE_SCHEDULE))
        assertEquals("", unmarked.feedStampLabel)
    }

    @Test
    fun aFeedOlderThanADayIsAdvertisedAsATimetable() {
        val m = model(pos = pos(2, 3, StopStatus.DEPARTED).copy(feedAgeSeconds = 3L * 86_400L))
        assertTrue("said: ${m.ageLabel}", m.ageLabel.contains("days old"))
        assertTrue("said: ${m.ageLabel}", m.ageLabel.contains("timetable"))
    }

    @Test
    fun noStampFromTheSourceMeansNoClaimAboutTheSource() {
        // With nothing stamped the chip may report only what the device knows — when it fetched. And a
        // schedule-only screen must not say "live" at all, because `age(0)` would: 0 seconds since a fetch
        // that never happened is exactly the unprovable claim this app was written to stop making.
        val offline = model(pos = pos(1, 2, StopStatus.DEPARTED, kind = SourceKind.OFFLINE_SCHEDULE))
        assertEquals("no live fetch", offline.ageLabel)
        val fetched = model(pos = pos(2, 3, StopStatus.DEPARTED).copy(ageSeconds = 40 * 60L))
        assertEquals("updated 40 min ago", fetched.ageLabel)
    }
}
