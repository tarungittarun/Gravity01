package `in`.opentrack.india.v2.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mirror parser, justified by the capture it was written from: runningstatus.in's server-rendered
 * running-status page for 20651, captured 2026-10-12 while the train was LIVE between Arsikere Jn and
 * Kadur (departed Ballekere Halt 18:00, ~20 min late). Every assertion below is a fact the capture
 * shows - the actual minutes of the passed halts, the "Update Awaited" halts that must render nothing,
 * the source/terminus single-minute rows, and the near-station row that must not contaminate its
 * neighbours. This file is the "no shape without a capture" gate for the mirror data path.
 */
class RunningStatusMirrorTest {

    private fun capture(): String =
        javaClass.getResourceAsStream("/captures/mirror-runningstatus-20651-2026-10-12.txt")!!
            .readBytes().toString(Charsets.UTF_8)

    private val routeCodes = listOf(
        "SBC", "YPR", "TK", "TTR", "ASK", "DRU", "RRB", "TKE", "BDVT", "SME", "SMET",
        "KMSI", "ARU", "ANF", "SRF", "TLGP",
    )

    @Test
    fun `capture parses to fourteen halt rows with actuals`() {
        val actuals = RunningStatusMirror.parse(RunningStatusMirror.stripTags(capture()))
        assertNotNull("the capture must parse", actuals)
        assertEquals(
            "16 halts minus the two \"Update Awaited\" rows the mirror cannot confirm yet",
            14, actuals!!.stops.size,
        )
        assertEquals("runningstatus.in", actuals.source)
        assertEquals("yesterday's captured page must not become today's run", 20738L, actuals.runEpochDay)
        assertEquals("SBC", actuals.stops.first().stationCode)
        assertEquals("TLGP", actuals.stops.last().stationCode)
    }

    @Test
    fun `the twelve-hour clock reads as rail minutes`() {
        assertEquals(910, RunningStatusMirror.h12("3", "10", "PM"))
        assertEquals(922, RunningStatusMirror.h12("3", "22", "PM"))
        assertEquals(0, RunningStatusMirror.h12("12", "00", "AM"))
        assertEquals(720, RunningStatusMirror.h12("12", "00", "PM"))
    }

    @Test
    fun `a passed halt carries scheduled AND actual minutes`() {
        val actuals = RunningStatusMirror.parse(RunningStatusMirror.stripTags(capture()))!!
        val ypr = actuals.stops.first { it.stationCode == "YPR" }
        assertEquals(910, ypr.schArr)
        assertEquals(912, ypr.schDep)
        assertEquals(922, ypr.actArr)
        assertEquals(924, ypr.actDep)
        val ask = actuals.stops.first { it.stationCode == "ASK" }
        assertEquals(1053, ask.actArr)
        assertEquals(1059, ask.actDep)
    }

    @Test
    fun `halts the mirror has not reached yet render nothing`() {
        val actuals = RunningStatusMirror.parse(RunningStatusMirror.stripTags(capture()))!!
        assertTrue(
            "\"Update Awaited\" halts are dropped, never half-parsed",
            actuals.stops.none { it.stationCode == "DRU" || it.stationCode == "RRB" },
        )
    }

    @Test
    fun `source and terminus carry their single actual`() {
        val actuals = RunningStatusMirror.parse(RunningStatusMirror.stripTags(capture()))!!
        val sbc = actuals.stops.first()
        assertNull("the source never arrives", sbc.actArr)
        assertEquals(900, sbc.actDep)
        assertNull("the source has no schedule arrival in the mirror", sbc.schArr)
        val tlgp = actuals.stops.last()
        assertEquals(1305, tlgp.actArr)
        assertNull(tlgp.actDep)
    }

    @Test
    fun `the near-station row does not contaminate its neighbours`() {
        val actuals = RunningStatusMirror.parse(RunningStatusMirror.stripTags(capture()))!!
        val ask = actuals.stops.first { it.stationCode == "ASK" }
        assertEquals(
            "the 24-hour clock of the near-station line must not enter ASK's block",
            4, listOfNotNull(ask.schArr, ask.schDep, ask.actArr, ask.actDep).size,
        )
        assertEquals(1053, ask.actArr)
    }

    @Test
    fun `sentences name both minutes, the delay, and the source`() {
        val actuals = RunningStatusMirror.parse(RunningStatusMirror.stripTags(capture()))!!
        val lines = RunningStatusMirror.sentences(actuals, routeCodes)
        assertEquals(
            "ASK arr 17:33 · dep 17:39 — via runningstatus.in (24 min late)",
            lines["ASK"],
        )
        assertEquals(
            "YPR arr 15:22 · dep 15:24 — via runningstatus.in (12 min late)",
            lines["YPR"],
        )
        assertEquals(
            "a right-time halt says so",
            "TKE arr 18:23 · dep 18:25 — via runningstatus.in (on time)",
            lines["TKE"],
        )
        assertEquals(
            "the terminus arrives, never departs",
            "TLGP arr 21:45 — via runningstatus.in (on time)",
            lines["TLGP"],
        )
        assertEquals(
            "the source departs, never arrives",
            "SBC dep 15:00 — via runningstatus.in (on time)",
            lines["SBC"],
        )
        assertFalse(lines.containsKey("DRU"))
    }

    @Test
    fun `sentences outside the route are dropped`() {
        val actuals = RunningStatusMirror.Actuals(
            "runningstatus.in",
            listOf(RunningStatusMirror.Stop("ZZZZ", 100, 110, 120, 130)),
        )
        assertTrue(RunningStatusMirror.sentences(actuals, routeCodes).isEmpty())
    }

    @Test
    fun `a delay crossing midnight wraps to the same day`() {
        val actuals = RunningStatusMirror.Actuals(
            "runningstatus.in",
            listOf(RunningStatusMirror.Stop("SBC", null, 1430, null, 1450)),
        )
        assertEquals(
            "the house clock pads minutes, never the hour (5:15, not 05:15)",
            "SBC dep 0:10 — via runningstatus.in (20 min late)",
            RunningStatusMirror.sentences(actuals, listOf("SBC"))["SBC"],
        )
    }

    @Test
    fun `tags and entities strip to the text the parser trusts`() {
        val html = "<td>KSR BENGALURU (SBC)</td><td>03:00&nbsp;PM 12-Oct</td><td>Dep:&nbsp;RT</td>"
        val text = RunningStatusMirror.stripTags(html)
        assertTrue(text.contains("(SBC)"))
        assertTrue(text.contains("03:00 PM 12-Oct"))
        assertTrue(text.contains("Dep: RT"))
        assertFalse(text.contains('<'))
    }

    @Test
    fun `the wire reports its reason when the mirror does not answer`() {
        val dead = RunningStatusMirror.fetchOrWhy("20651") { null to "HTTP 403" }
        assertNull(dead.actuals)
        assertEquals("HTTP 403", dead.why)
        val silent = RunningStatusMirror.fetchOrWhy("20651") { null to "" }
        assertNull(silent.actuals)
        assertEquals("no answer", silent.why)
        val junk = RunningStatusMirror.fetchOrWhy("20651") { "<html>no halt rows here</html>" to "ok" }
        assertNull(junk.actuals)
        assertEquals("answered but no halt rows parsed", junk.why)
    }
    @Test
    fun `the last confirmed departure is the strongest past-here signal`() {
        val actuals = RunningStatusMirror.parse(RunningStatusMirror.stripTags(capture()))!!
        // 19:43 device time: BDVT departed 29 minutes ago - the newest REAL observation in the capture
        // (paste #24's exact situation: the tail "actuals" for ANF/SRF/TLGP are schedule echoes lying
        // ahead of the clock, and an echo must never anchor position).
        // At 19:43 the fixture's tail is all echo or unprovable: ARU (+3 ahead of the clock), ANF
        // (+22) and SRF (+52) sit in or beyond the future, and every halt from BDVT onward is an
        // on-time COPY of its schedule - unprovable by definition. The newest PROVABLE deviation
        // is ASK: actual dep 17:39 against a 17:15 schedule.
        val (last, echoes) = RunningStatusMirror.lastConfirmed(actuals, routeCodes, 19 * 60 + 43)!!
        assertEquals("ASK dep 17:39 (+24) is the newest provable deviation", "ASK", last.stationCode)
        assertEquals(1059, last.actDep)
        assertEquals("ARU and ANF sit ahead of the clock - echoes, named for the diary", listOf("ARU", "ANF"), echoes)
        assertEquals(24, RunningStatusMirror.slipMinutes(last))
    }

    @Test
    fun `a departure minutes ahead of the clock is an echo, not an observation`() {
        val actuals = RunningStatusMirror.Actuals(
            "runningstatus.in",
            listOf(
                RunningStatusMirror.Stop("AAA", null, 1000, null, 1005),
                RunningStatusMirror.Stop("BBB", null, 1180, null, 1186),
            ),
        )
        val (last, echoes) = RunningStatusMirror.lastConfirmed(actuals, listOf("AAA", "BBB"), 1183)!!
        assertEquals("BBB sits 3 minutes ahead of the clock - an echo, so AAA wins", "AAA", last.stationCode)
        assertEquals(listOf("BBB"), echoes)
        val dev = RunningStatusMirror.Actuals(
            "runningstatus.in",
            listOf(
                RunningStatusMirror.Stop("AAA", null, 1000, null, 1000),
                RunningStatusMirror.Stop("BBB", null, 900, null, 940),
            ),
        )
        assertEquals(
            "an on-time copy of the schedule is not an observation; the deviation behind it is",
            "BBB", RunningStatusMirror.lastConfirmed(dev, listOf("AAA", "BBB"), 1183)!!.first.stationCode,
        )
        assertNull("every halt an echo means nothing is confirmed", RunningStatusMirror.lastConfirmed(
            RunningStatusMirror.Actuals("runningstatus.in", listOf(
                RunningStatusMirror.Stop("BBB", null, 1180, null, 1186),
            )), listOf("BBB"), 1183,
        ))
    }

    @Test
    fun `slip minutes read straight off the mirror pair`() {
        val actuals = RunningStatusMirror.parse(RunningStatusMirror.stripTags(capture()))!!
        assertEquals(12, RunningStatusMirror.slipMinutes(actuals.stops.first { it.stationCode == "YPR" }))
        assertEquals(24, RunningStatusMirror.slipMinutes(actuals.stops.first { it.stationCode == "ASK" }))
        assertNull("the terminus never departs", RunningStatusMirror.slipMinutes(actuals.stops.last()))
    }

}
