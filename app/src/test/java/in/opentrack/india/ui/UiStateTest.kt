package `in`.opentrack.india.ui

import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.TrainHit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test per sentence the screens state into the diary - the paste is the product here. */
class UiStateTest {

    private fun stop(code: String, arr: Int?, dep: Int?, board: Boolean = false, alight: Boolean = false) =
        TrackModel.Stop(
            seq = 1, code = code, name = code, schedArr = arr, schedDep = dep,
            showArr = arr, showDep = dep, delay = null, source = TrackModel.MinuteSource.SCHEDULE,
            platform = null, state = TrackModel.State.LATER, isAnchor = false,
            kmFromOrigin = 0, kmFromBoard = 0, kmRemaining = 0,
            isBoarding = board, isAlighting = alight, minutesFromNow = null,
        )

    private fun hit(no: String, board: Int, alight: Int, tag: String?) = TrainHit(
        trainNo = no, trainName = "TRAIN $no", trainType = null, originDateEpochDay = 0L,
        boardMinute = board, alightMinute = alight, fromCode = "YPR", toCode = "SMET",
        fromSeq = 0, toSeq = 0, distanceKm = 0, runDays = null, sourceTag = tag,
    )

    @Test
    fun theTrackScreenStatesItsHeadlineChipsAndRows() {
        val line = UiState.track(
            "20689", "Endpoints only: leaves YPR 09:15, reaches SMET 14:05",
            "OFFLINE · schedule only", "no live fetch", "no live feed",
            "Next: Smet (your stop) · 14:05 due · in 1 min",
            "endpoint times only · per-stop halts unavailable on this device",
            null,
            listOf(stop("YPR", null, 555, board = true), stop("SMET", 845, null, alight = true)),
        )
        assertTrue("got: $line", line.contains("screen=track train=20689"))
        assertTrue("got: $line", line.contains("headline=\"Endpoints only"))
        assertTrue("got: $line", line.contains("chip=\"OFFLINE · schedule only · no live fetch\""))
        assertTrue("got: $line", line.contains("YPR arr – dep 09:15 [board]"))
        assertTrue("got: $line", line.contains("SMET arr 14:05 dep – [alight]"))
    }

    @Test
    fun diaryPreviewLabelsEachVisibleRowByItsActualDisplaySource() {
        val rows = listOf(
            stop("MYS", null, 1180),
            stop("ARU", 1783, 1784).copy(showArr = 1790, showDep = 1794,
                source = TrackModel.MinuteSource.SITE_REPORTED),
            stop("ANF", 1800, 1802).copy(source = TrackModel.MinuteSource.SCHEDULE),
        )
        val line = UiState.track("16227", "Website reports Arasalu", "WEBSITE",
            "checked", "—", "Next scheduled", "last website stop", null, rows)
        assertTrue("lost the website label: $line", line.contains("ARU arr 05:50 (+1d) dep 05:54 (+1d) [Web]"))
        assertTrue("lost the static label: $line", line.contains("ANF arr 06:00 (+1d) dep 06:02 (+1d) [Sch]"))
        assertFalse("a site claim was called verified actual: $line", line.contains("ARU arr 05:50 (+1d) dep 05:54 (+1d) [Act]"))
    }

    @Test
    fun aLongRouteIsStatedWithACapAndAMoreCount() {
        val rows = (1..12).map { stop("S$it", 600 + it, 601 + it) }
        val line = UiState.track("16579", "h", "chip", "age", "d", "n", "p", null, rows)
        assertTrue("got: $line", line.contains("(+4 more halts)"))
        assertFalse("the cap leaked: $line", line.contains("S12"))
    }

    @Test
    fun websiteClaimNearEndOfRouteIsInDiaryEvenBeyondFirstEightRows() {
        val rows = (1..12).map { stop("S$it", 600 + it, 601 + it) }.toMutableList()
        rows[10] = stop("ARU", 1783, 1784).copy(
            showArr = 1790, showDep = 1794, source = TrackModel.MinuteSource.SITE_REPORTED)
        val line = UiState.track("16227", "Website reports departed Arasalu", "WEBSITE",
            "checked", "—", "Next scheduled", "past card", null, rows)
        assertTrue("missing website values in diary: $line",
            line.contains("website-published=ARU Web arr 05:50 (+1d) dep 05:54 (+1d)"))
        assertFalse("must not call the site independently verified: $line", line.contains("Act 05:50"))
    }

    @Test
    fun theListScreenStatesItsCountStateLineAndRowLabels() {
        val line = UiState.list(
            "YPR", "SMET", 2,
            "live list via erail · 16579 is the old number of 20689 (SMET INTERCITY) — the live " +
                "source answers both numbers with one train; the offline row is merged",
            listOf(hit("20689", 555, 845, "via erail"), hit("16579", 555, 855, "timetable")),
        )
        assertTrue("got: $line", line.contains("screen=list YPR→SMET · 2 train(s)"))
        assertTrue("got: $line", line.contains("the offline row is merged"))
        assertTrue("got: $line", line.contains("20689 TRAIN 20689 09:15→14:05 via erail"))
        assertTrue("got: $line", line.contains("16579 TRAIN 16579 09:15→14:15 timetable"))
    }

    @Test
    fun aNullStateLineLeavesTheListSentenceOut() {
        val line = UiState.list("YPR", "SMET", 0, null, emptyList())
        assertTrue("got: $line", line.contains("screen=list YPR→SMET · 0 train(s)"))
        assertFalse("got: $line", line.contains("rows= ;"))
    }
}
