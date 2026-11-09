package `in`.opentrack.india.domain

import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether the marker can be believed. Each test below exists because the naive
 * implementation of that rule is what shipping trackers do, and each one produces a visible lie:
 * a train "at" a station it left an hour ago, a marker that walks backwards, or an ETA for a stop
 * 300 km away that the feed never promised.
 *
 * Fixture: a 3-stop, 200 km run — SRC 06:00 → MID 10:00/10:10 → DST 11:40.
 */
class PositionEngineTest {

    private val t0 = 1_723_612_800_000L

    private fun stop(seq: Int, code: String, arr: Int?, dep: Int?, km: Int, halt: Boolean = true) =
        StopRow(
            seq = seq, code = code, name = "$code Jn", state = "GJ", lat = null, lon = null,
            arrivalMin = arr, departureMin = dep, journeyDay = if ((arr ?: dep ?: 0) >= 1440) 2 else 1,
            schedKm = km, isHalt = halt,
        )

    private val route = TrainRoute(
        trainNo = "12951", trainName = "TEST EXPRESS", srcCode = "SRC", srcName = "Src Jn",
        dstCode = "DST", dstName = "Dst Jn", totalKm = 200, totalMin = 340, runDays = null,
        returnNo = null,
        stops = listOf(
            stop(1, "SRC", null, 360, 0),
            stop(2, "MID", 600, 610, 100),
            stop(3, "DST", 700, null, 200, halt = false),
        ),
        segments = listOf(
            SegmentRow(1, "SRC", "MID", 100, 240),
            SegmentRow(2, "MID", "DST", 100, 90),
        ),
    )

    private fun snap(
        code: String?, seq: Int, event: StopStatus, eventMinute: Int?, schedMinute: Int?,
        delay: Int? = null, fetchedAt: Long = t0, perStop: Map<String, LiveStopFields> = emptyMap(),
    ) = LiveSnapshot(
        trainNo = "12951", journeyEpochDay = null, atCode = code, atSeq = seq, atName = "$code Jn",
        event = event, eventMinute = eventMinute, scheduledMinuteAtEvent = schedMinute,
        nextCode = null, expectedNextMinute = null, delayMinutes = delay, platform = null,
        perStop = perStop, fetchedAtMillis = fetchedAt, sourceKind = SourceKind.PROVIDER,
    )

    private fun locate(
        snapshot: LiveSnapshot?, now: Int, previous: TrainPosition? = null,
        gps: PositionEngine.GpsEvidence? = null, nowMillis: Long = t0 + 60_000L,
    ) = PositionEngine.locate(
        PositionEngine.Request(route, snapshot, now, nowMillis, previous, gps)
    )

    @Test
    fun emptyRouteIsSaidSoRatherThanDrawn() {
        val bare = route.copy(stops = emptyList(), segments = emptyList())
        val a = PositionEngine.locate(
            PositionEngine.Request(bare, null, 600, t0, null, null)
        )
        assertTrue(a.unresolved)
        assertEquals(SourceKind.NONE, a.position.provenance)
        assertNotNull(a.note)
    }

    @Test
    fun aConfirmedArrivalIsHeldForTheLengthOfTheHalt() {
        // 10:05, ten minutes after the feed said "arrived MID", and the halt is 10 min.
        val a = locate(snap("MID", 2, StopStatus.ARRIVED, 600, 600), now = 605)
        assertEquals(StopStatus.ARRIVED, a.position.status)
        assertEquals(2, a.position.anchorSeq)
        assertEquals(0f, a.position.fraction, 1e-6f)
        assertEquals(100f, a.position.kmAlong, 1e-3f)
        // While it is standing at MID, the useful future minute is the scheduled departure.
        assertEquals(610, a.position.etaNextMinute)
    }

    @Test
    fun arrivalOnlyDoesNotBecomeDepartureWhenScheduledHaltEnds() {
        val a = locate(snap("MID", 2, StopStatus.ARRIVED, 600, 600,
            perStop = mapOf("MID" to LiveStopFields(actualArrivalMin = 600))), now = 625)
        assertEquals(StopStatus.ARRIVED, a.position.status)
        assertEquals(2, a.position.anchorSeq)
        assertEquals(0f, a.position.fraction, 1e-6f)
        assertNull(a.position.etaNextMinute)
        assertTrue(a.note.orEmpty().contains("departure not reported"))
        val screen = TrackModel.build(route, a.position,
            mapOf("MID" to LiveStopFields(actualArrivalMin = 600)), 625,
            boardSeq = 1, alightSeq = 3, runEpochDay = 19949L,
            note = a.note, positionOnly = true)
        assertTrue(screen.headline.contains("arrived at MID Jn"))
        assertFalse(screen.headline.contains("departed"))
        assertNull(screen.rows.first { it.code == "MID" }.showDep)
    }

    @Test
    fun pastTheHaltTheMarkerStartsMoving() {
        val a = locate(snap("MID", 2, StopStatus.DEPARTED, 610, 610), now = 655)
        assertEquals(StopStatus.DEPARTED, a.position.status)
        // 45 min into a 90 min leg => exactly halfway, 150 km of 200.
        assertEquals(0.5f, a.position.fraction, 1e-4f)
        assertEquals(150f, a.position.kmAlong, 0.5f)
        assertEquals(700, a.position.etaNextMinute)
        assertFalse(a.held)
    }

    @Test
    fun interpolationNeverClaimsArrival() {
        // 6 hours into a 90-minute leg: the feed has simply not reported DST yet. 0.97 is the cap, and
        // an ETA that is already behind us is dropped rather than shown.
        val a = locate(snap("MID", 2, StopStatus.DEPARTED, 610, 610), now = 610 + 360)
        assertEquals(0.97f, a.position.fraction, 1e-4f)
        assertNull(a.position.etaNextMinute)
        assertEquals(TrainPosition.Confidence.LOW, a.position.confidence)
    }

    @Test
    fun delayIsCarriedFromTheAnchorIntoTheLeg() {
        // Delay is ALREADY present in the reported 10:24 departure. Adding 14 to the
        // 90-minute leg would count it twice and slow the icon after departure.
        val a = locate(snap("MID", 2, StopStatus.DEPARTED, 624, 610, delay = 14), now = 669)
        assertEquals(14, a.position.delayMinutes)
        assertEquals(0.5f, a.position.fraction, 1e-4f)
    }

    @Test
    fun seventyMinutesLateAtBirurIsNotAddedToTheBirurTarikereLegAgain() {
        val short = route.copy(
            stops = listOf(stop(1, "SRC", null, 555, 0),
                stop(2, "MID", 722, 724, 206),
                stop(3, "DST", 750, null, 230, halt = false)),
            segments = listOf(SegmentRow(1, "SRC", "MID", 206, 167),
                SegmentRow(2, "MID", "DST", 24, 26)), totalKm = 230)
        val snap = snap("MID", 2, StopStatus.DEPARTED, 794, 724, delay = 70)
        val result = PositionEngine.locate(PositionEngine.Request(short, snap,
            801, t0 + 60_000L, null, null))
        assertEquals(7f / 26f, result.position.fraction, 1e-4f)
        assertEquals(206f + 24f * 7f / 26f, result.position.kmAlong, .01f)
        assertEquals(820, result.position.etaNextMinute)
    }

    @Test
    fun markersNeverWalkBackwards() {
        val ahead = locate(snap("DST", 3, StopStatus.DEPARTED, 700, 700), now = 705)
        assertEquals(3, ahead.position.anchorSeq)
        val stale = locate(
            snap("MID", 2, StopStatus.DEPARTED, 610, 610), now = 706, previous = ahead.position
        )
        assertEquals(3, stale.position.anchorSeq)
        assertTrue(stale.held)
        assertTrue(stale.note!!.contains("holding"))
    }

    @Test
    fun aReportedStationOffTheScheduleIsReportedNotSnapped() {
        val a = locate(snap("XYZ", 99, StopStatus.DEPARTED, 640, 640), now = 650)
        assertTrue(a.unresolved)
        assertEquals("XYZ", a.anchorStopCode)
        assertTrue(a.note!!.contains("not on the scheduled route"))
        // The position falls back to the last stop the *timetable* puts behind us, and says LOW.
        assertEquals(2, a.position.anchorSeq)
        assertEquals(TrainPosition.Confidence.LOW, a.position.confidence)
    }

    @Test
    fun sequenceOnlyAnchorNeedsTheCodeToAgree() {
        // The feed gave a sequence but a code that contradicts it: trusting the number would put the
        // marker at a station the train has not claimed, so both are refused.
        val a = locate(snap("QQQ", 2, StopStatus.DEPARTED, 610, 610), now = 650)
        assertTrue(a.unresolved)
        // With no code at all, the sequence is the only evidence and is used.
        val b = locate(snap(null, 2, StopStatus.DEPARTED, 610, 610), now = 650)
        assertFalse(b.unresolved)
        assertEquals(2, b.position.anchorSeq)
    }

    @Test
    fun staleLiveDataIsDemotedToCached() {
        val old = snap("MID", 2, StopStatus.DEPARTED, 610, 610, fetchedAt = t0)
        val a = locate(old, now = 655, nowMillis = t0 + PositionEngine.STALE_MS + 1_000L)
        assertEquals(SourceKind.CACHED, a.position.provenance)
        assertTrue(a.position.ageSeconds > 600)
        val fresh = locate(old, now = 655, nowMillis = t0 + 30_000L)
        assertEquals(SourceKind.PROVIDER, fresh.position.provenance)
    }

    private val gpsTwoAheads = PositionEngine.GpsEvidence(
        nearestStationCode = "DST", nearestStationSeq = 3, distanceKm = 4f,
        groundSpeedKmh = 78f, accuracyM = 12f,
    )

    @Test
    fun gpsMoreThanOneSegmentAheadOfTheFeedWinsAndIsLabelled() {
        // One stop ahead is *not* enough: the feed lags by a station routinely, and following a 4 m
        // accuracy fix that closely would make the marker jitter. Two ahead is a real disagreement.
        val a = locate(snap("SRC", 1, StopStatus.DEPARTED, 360, 360), now = 365, gps = gpsTwoAheads)
        assertEquals(3, a.position.anchorSeq)
        assertEquals(SourceKind.DERIVED_GPS, a.position.provenance)
        assertTrue(a.note!!.contains("GPS"))
    }

    @Test
    fun oneStopAheadIsNotEnoughToOverruleTheFeed() {
        val b = locate(snap("MID", 2, StopStatus.DEPARTED, 610, 610), now = 620, gps = gpsTwoAheads)
        assertEquals(2, b.position.anchorSeq)
        assertEquals(SourceKind.PROVIDER, b.position.provenance)
    }

    @Test
    fun aVagueGpsFixIsIgnored() {
        val vague = gpsTwoAheads.copy(accuracyM = 900f, groundSpeedKmh = 0f)
        val a = locate(snap("SRC", 1, StopStatus.DEPARTED, 360, 360), now = 365, gps = vague)
        assertEquals(1, a.position.anchorSeq)
        assertEquals(SourceKind.PROVIDER, a.position.provenance)
    }

    @Test
    fun cancelledAtTheAnchorIsTheHeadline() {
        val a = locate(snap("MID", 2, StopStatus.CANCELLED_HERE, 610, 610), now = 620)
        assertEquals(StopStatus.CANCELLED_HERE, a.position.status)
        assertNull(a.position.etaNextMinute)
    }

    @Test
    fun scheduleOnlyEstimatesInsideAWideWindowAndNoFurther() {
        // 10:05, five minutes after the scheduled MID departure: an on-time assumption is still worth
        // drawing, as long as the provenance says it is not live.
        val near = locate(null, now = 605)
        assertEquals(SourceKind.OFFLINE_SCHEDULE, near.position.provenance)
        assertEquals(1, near.position.anchorSeq)
        // 20 minutes into the MID→DST leg (dep 10:10): estimated, and labelled as an assumption.
        val mid = locate(null, now = 630)
        assertEquals(2, mid.position.anchorSeq)
        assertEquals(StopStatus.DEPARTED, mid.position.status)
        assertTrue(mid.position.fraction > 0f)
        // Half a day after the last scheduled departure, "on time" would put it at DST already: the
        // engine stops estimating and shows the last stop the timetable has behind it.
        val way = locate(null, now = 1200)
        assertEquals(3, way.position.anchorSeq)
        assertEquals(0f, way.position.fraction, 1e-6f)
        // Already past the last scheduled stop: there is nothing honest left to estimate.
        assertTrue(way.note!!.contains("end of the scheduled run"))
        assertNull(way.position.etaNextMinute)
        // Before the origin departure, say so instead of showing a marker at km 0.
        val before = locate(null, now = 300)
        assertEquals(StopStatus.UPCOMING, before.position.status)
        assertEquals(360, before.position.etaNextMinute)
        assertTrue(before.note!!.contains("has not departed yet"))
    }

    @Test
    fun remainingTimeIsReportedToEndOfRunWhenKnown() {
        val a = locate(snap("MID", 2, StopStatus.DEPARTED, 610, 610), now = 655)
        // DST scheduled 11:40 = 700; 45 min gone => 45 min left.
        assertEquals(45, a.position.remainingMin)
    }

    @Test
    fun delayProfileNeverInventsZeroForUnknownStops() {
        val withAnchor = route.stops.map {
            it.copy(delayArrivalMin = if (it.seq == 2) 14 else null)
        }
        val r2 = route.copy(stops = withAnchor)
        val profile = PositionEngine.delayProfile(r2, mapOf("MID" to LiveStopFields(delayArrivalMin = 14)), 2, 14)
        // Past stops: unknown. At and after the anchor: the anchor's delay carried forward, which is
        // exactly how the station boards phrase it, and null where even that would be a guess.
        assertNull(profile[0])
        assertEquals(14, profile[1])
        assertEquals(14, profile[2])
    }
}
