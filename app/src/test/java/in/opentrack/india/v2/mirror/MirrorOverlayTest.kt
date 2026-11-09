package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.ui.LiveViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The columns are fed by the WITNESS, not by the official chain - the gate paste #28 caught ("the
 * mirror answered while the screen said schedule only") and paste #29 proved twice more. These tests
 * pin what may print as Act and what must stay Est, because under-claiming beats fabricating.
 */
class MirrorOverlayTest {

    private fun stop(seq: Int, code: String, arr: Int?, dep: Int?) = StopRow(
        seq = seq, code = code, name = code, state = null, lat = null, lon = null,
        arrivalMin = arr, departureMin = dep, journeyDay = 0, schedKm = (seq - 1) * 10, isHalt = true,
    )

    private val route = TrainRoute(
        trainNo = "16228", trainName = "TLGP MYS EXP",
        srcCode = "AAA", srcName = "AAA", dstCode = "CCC", dstName = "CCC",
        totalKm = 20, totalMin = 200, runDays = 0x7F, returnNo = null,
        stops = listOf(
            stop(1, "AAA", null, 600),
            stop(2, "BBB", 700, 705),
            stop(3, "CCC", 800, null),
        ),
        segments = emptyList(),
    )

    private fun pos() = TrainPosition(
        anchorSeq = 0, nextSeq = 0, fraction = 0f, kmAlong = 0f, status = StopStatus.UPCOMING,
        etaNextMinute = null, delayMinutes = null, remainingMin = null,
        provenance = SourceKind.OFFLINE_SCHEDULE,
        confidence = TrainPosition.Confidence.LOW, ageSeconds = 0L,
    )

    private fun model(per: Map<String, LiveStopFields> = emptyMap()) = TrackModel.build(
        route, pos(), per, nowRailMinute = 730, boardSeq = 1, alightSeq = 3,
        runEpochDay = 0L, note = null,
    )

    @Test
    fun onlyOverlappingObservedOfficialCellsCorroborateTheSecondPage() {
        val official = PositionEngine.Answer(
            position = pos().copy(provenance = SourceKind.PROVIDER_NTES),
            anchorStopCode = "BBB", anchorStopName = "BBB", note = null,
            perStop = mapOf(
                "AAA" to LiveStopFields(actualDepartureMin = 605),
                "BBB" to LiveStopFields(actualArrivalMin = 702, actualDepartureMin = 707),
                "CCC" to LiveStopFields(actualArrivalMin = 799), // future at 730, not an actual
            ),
        )
        val base = model()
        val witness = LiveViewModel.officialStopsForCrossCheck(official, base, 730)
        assertEquals(2, witness.size)
        val secondPage = listOf(
            RunningStatusMirror.Stop("AAA", null, 600, null, 605),
            RunningStatusMirror.Stop("BBB", 700, 705, 702, 740), // future dep differs; only arrival
            RunningStatusMirror.Stop("CCC", 800, null, 799, null),
        )
        val verified = LiveViewModel.crossCheckedStops(witness, secondPage, base, 730)
        assertEquals(2, verified.size)
        assertEquals(702, verified.last().actArr)
        assertNull(verified.last().actDep)
        assertTrue(verified.none { it.stationCode == "CCC" })
        assertTrue(LiveViewModel.officialStopsForCrossCheck(
            official.copy(position = pos().copy(provenance = SourceKind.CACHED)), base, 730,
        ).isEmpty())
    }

    @Test
    fun coveredMinutePrintsAsActWithTheScreensArithmetic() {
        // The device clock has passed the witnessed minute; the displayed timetable is the baseline.
        val stops = listOf(RunningStatusMirror.Stop("BBB", 690, 695, 700, 735))
        val out = LiveViewModel.overlayActuals(model(), stops, nowRail = 740)
        val bbb = out.rows.first { it.code == "BBB" }
        assertEquals(TrackModel.MinuteSource.REPORTED, bbb.source)
        assertEquals(735, bbb.showDep)
        assertEquals("delay runs against the DISPLAYED schedule (705), not the mirror's pair", 30, bbb.delay)
        val verdict = LiveViewModel.mirrorVerdict(out)
        assertNotNull(verdict)
        assertTrue(
            "verdict must name the halt and the slip: '${verdict!!.first}'",
            verdict.first.contains("BBB") && verdict.first.contains("30 min late"),
        )
        assertEquals(30, verdict.second)
    }

    @Test
    fun aScheduleEqualMinuteIsActOnceCoveredByTheDeviceClock() {
        // R48: clock coverage, not comparison to the mirror's schedule, decides Act.
        val stops = listOf(RunningStatusMirror.Stop("BBB", 700, 705, 700, 705))
        val out = LiveViewModel.overlayActuals(model(), stops, nowRail = 730)
        val bbb = out.rows.first { it.code == "BBB" }
        assertEquals(TrackModel.MinuteSource.REPORTED, bbb.source)
        assertEquals(705, bbb.showDep)
        assertNotNull(LiveViewModel.mirrorVerdict(out))
    }

    @Test
    fun aFutureWitnessMinuteIsEstFromDisplayedScheduleAndCoveredDelay() {
        val stops = listOf(
            RunningStatusMirror.Stop("AAA", null, 600, null, 610),
            RunningStatusMirror.Stop("BBB", 700, 705, 770, 775),
        )
        val out = LiveViewModel.overlayActuals(model(), stops, nowRail = 690)
        val bbb = out.rows.first { it.code == "BBB" }
        assertEquals(TrackModel.MinuteSource.PROJECTED, bbb.source)
        assertEquals(715, bbb.showDep) // 705 + 10, NOT the future witness's 775
    }

    @Test
    fun haltsTheWitnessDoesNotCarryKeepTheirTimetableRowUntouched() {
        val stops = listOf(RunningStatusMirror.Stop("BBB", 700, 705, 700, 735))
        val out = LiveViewModel.overlayActuals(model(), stops, nowRail = 730)
        val aaa = out.rows.first { it.code == "AAA" }
        assertEquals(TrackModel.MinuteSource.SCHEDULE, aaa.source)
        // A schedule row can retain its timetable minute internally; the adapter prints a dash
        // instead of Act/Est when source is SCHEDULE.
        val ccc = out.rows.first { it.code == "CCC" }
        assertEquals(TrackModel.MinuteSource.PROJECTED, ccc.source)
        assertEquals(800, ccc.showArr)
    }

    @Test
    fun theDayWrapKeepsTheSlipInsideTheHonestBand() {
        // AAA dep scheduled 600; mirror reports 00:05 next day. Whatever the fold, the slip the
        // screen computes must stay inside the wrap-safe +/-720 band or the colour lies.
        val stops = listOf(RunningStatusMirror.Stop("AAA", null, 600, null, 1445))
        val overnight = model().copy(rows = model().rows.map { row ->
            if (row.code == "AAA") row.copy(schedDep = 1435) else row
        })
        val out = LiveViewModel.overlayActuals(overnight, stops, nowRail = 1500)
        val aaa = out.rows.first { it.code == "AAA" }
        assertEquals(10, aaa.delay)
    }

    @Test
    fun extensionHaltWithNoScheduleShowsWitnessAsEstimate() {
        val ext = model().copy(rows = model().rows + model().rows.last().copy(
            code = "EXT", schedArr = null, schedDep = null, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE,
        ))
        val out = LiveViewModel.overlayActuals(
            ext, listOf(RunningStatusMirror.Stop("EXT", null, null, 900, null)), 730,
        )
        assertEquals(TrackModel.MinuteSource.PROJECTED, out.rows.last().source)
        assertEquals(900, out.rows.last().showArr)
    }

    @Test
    fun aFutureStopCannotTravelBackToBeforeTheRunAndRewriteEarlierEstimates() {
        val today = model().copy(rows = model().rows.map { row ->
            when (row.code) {
                "AAA" -> row.copy(schedDep = 900)      // SBC 15:00
                "BBB" -> row.copy(schedArr = 910, schedDep = 912) // YPR 15:10/15:12
                else -> row.copy(schedArr = 1140)       // BDVT 19:00
            }
        })
        val raw = listOf(RunningStatusMirror.Stop("CCC", null, null, 903, null)) // 15:03 at 19:00 halt
        assertTrue(LiveViewModel.validateStops(today, raw, 924).all {
            it.actArr == null && it.actDep == null
        })
        assertTrue(LiveViewModel.crossCheckedStops(emptyList(), raw, today, 924).isEmpty())
        val shown = LiveViewModel.overlayActuals(today, raw, 924)
        assertNull("no verified position", LiveViewModel.mirrorVerdict(shown))
        assertEquals(TrackModel.MinuteSource.SCHEDULE, shown.rows[1].source)
        assertEquals(TrackModel.MinuteSource.SCHEDULE, shown.rows[2].source)
    }

    @Test
    fun departureIsReportedOnlyAtTheWitnessedHaltAndEstimatesMoveForward() {
        val stops = listOf(
            RunningStatusMirror.Stop("AAA", null, 600, null, 610),
            RunningStatusMirror.Stop("BBB", 700, 705, null, null),
        )
        val result = LiveViewModel.overlayActuals(model(), stops, 690)
        assertEquals(TrackModel.MinuteSource.REPORTED, result.rows[0].source)
        assertEquals(610, result.rows[0].showDep)
        assertEquals(TrackModel.MinuteSource.PROJECTED, result.rows[1].source)
        assertEquals(715, result.rows[1].showDep)
        assertEquals(800, result.rows[2].showArr)
        assertEquals("only the immediate next stop may be projected",
            TrackModel.MinuteSource.SCHEDULE, result.rows[2].source)
        assertEquals("confirmed only where first witness agrees",
            emptyList<RunningStatusMirror.Stop>(),
            LiveViewModel.crossCheckedStops(stops, listOf(
                RunningStatusMirror.Stop("AAA", null, null, null, 640)), model(), 690))
    }

    @Test
    fun contradictoryPlausibleWitnessesCannotBothClaimAct() {
        val first = listOf(RunningStatusMirror.Stop("BBB", 700, 705, 700, 720))
        val second = listOf(RunningStatusMirror.Stop("BBB", null, null, 700, 732))
        assertEquals(setOf("BBB"), LiveViewModel.conflictingCodes(first, second, model(), 740))
        assertTrue(LiveViewModel.crossCheckedStops(first, second, model(), 740)
            .all { it.actDep == null })
    }

    @Test
    fun theConfirmTktWalkFeedsTheColumnsFromTheSameCaptureTheLinesComeFrom() {
        val text = RunningStatusMirror.stripTags(
            java.io.File("src/test/resources/captures/confirmtkt-20651-2026-10-12.txt").readText(),
        )
        val stops = listOf(
            "SBC" to "KSR BENGALURU", "YPR" to "YASVANTPUR JN", "TK" to "Tumakuru",
            "ASK" to "Arsikere Jn", "SMET" to "Shivamogga Town", "TLGP" to "Talguppa",
        )
        val lines = ConfirmTktMirror.parse(text, stops)
        val structured = ConfirmTktMirror.stopsOf(text, stops)
        assertEquals(
            "same halts on both shapes",
            lines.keys.toList(),
            structured.map { it.stationCode },
        )
        assertTrue("fixture must carry halts", structured.isNotEmpty())
        for (st in structured) {
            val line = lines[st.stationCode] ?: continue
            if (st.actArr != null) assertTrue("arr must be in its line: '$line'", line.contains("arr"))
            if (st.actDep != null) assertTrue("dep must be in its line: '$line'", line.contains("dep"))
        }
    }

    @Test
    fun anErailReportedRowKeepsItsLiveFieldsWhenTheMirrorIsSilentForIt() {
        // erail's per-stop minute for BBB must survive an overlay that says nothing about BBB.
        val per = mapOf("BBB" to LiveStopFields(actualArrivalMin = 702, actualDepartureMin = 712))
        val stops = listOf(RunningStatusMirror.Stop("AAA", null, 600, null, 604))
        val out = LiveViewModel.overlayActuals(model(per), stops, nowRail = 730)
        val bbb = out.rows.first { it.code == "BBB" }
        assertEquals("erail's minute survives", 712, bbb.showDep)
        assertEquals(TrackModel.MinuteSource.REPORTED, bbb.source)
    }
}
