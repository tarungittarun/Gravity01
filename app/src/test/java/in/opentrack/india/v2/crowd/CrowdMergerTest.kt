package `in`.opentrack.india.v2.crowd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The witness arithmetic (ADR-006): the median survives one liar, staleness is dropped and counted,
 * and the sentence is the tested wording - never improvised in a layout. Pure JVM, no network.
 */
class CrowdMergerTest {

    private fun rep(station: String, kind: StopReport.Kind, minute: Int, at: Long) =
        StopReport("16579", station, kind, minute, at)

    @Test
    fun the_median_survives_one_liar() {
        val now = 1_760_000_000_000L
        // Five honest sightings of the same halt, one liar claiming 900. Sorted:
        // [637, 638, 638, 639, 900]; the rule is sorted[size/2] = index 2 = 638. The liar moves
        // nothing; even a second liar would drag the value by one minute, not forty.
        val honest = listOf(637, 638, 639, 638).map { rep("SMET", StopReport.Kind.DEPARTED, it, now - 60_000) }
        val liar = rep("SMET", StopReport.Kind.DEPARTED, 900, now - 30_000)
        val merged = CrowdMerger.merge(honest + liar, now)
        val smet = merged.stops.single { it.stationCode == "SMET" }
        assertEquals(5, smet.reports)
        assertEquals(638, smet.medianRailMinute)
    }

    @Test
    fun stale_reports_are_dropped_and_counted_never_shown() {
        val now = 1_760_000_000_000L
        val fresh = rep("SMET", StopReport.Kind.ARRIVED, 630, now - 60_000)
        val old = rep("SMET", StopReport.Kind.ARRIVED, 640, now - CrowdMerger.WINDOW_MILLIS - 1)
        val merged = CrowdMerger.merge(listOf(fresh, old), now)
        assertEquals(1, merged.stops.size)
        assertEquals(630, merged.stops[0].medianRailMinute)
        assertEquals(1, merged.droppedStale)
    }

    @Test
    fun nothing_fresh_is_an_empty_answer_not_a_timetable() {
        val now = 1_760_000_000_000L
        val merged = CrowdMerger.merge(emptyList(), now)
        assertTrue(merged.stops.isEmpty())
        assertEquals(
            "no traveller reports for SMET yet",
            CrowdMerger.sentence(null, "SMET"),
        )
    }

    @Test
    fun the_sentence_names_the_source_count_and_age() {
        val now = 1_760_000_000_000L
        val merged = CrowdMerger.merge(
            listOf(
                rep("SMET", StopReport.Kind.DEPARTED, 637, now - 4 * 60_000),
                rep("SMET", StopReport.Kind.DEPARTED, 637, now - 2 * 60_000),
            ),
            now,
        )
        assertEquals(
            "SMET departed 10:37 — reported by travellers (2 reports, 2 min ago)",
            CrowdMerger.sentence(merged.stops.single(), "SMET"),
        )
    }

    @Test
    fun the_reader_line_prefers_departed_and_names_the_stop() {
        val now = 1_000_000L
        val merged = CrowdMerger.merge(
            listOf(
                StopReport("16579", "SMET", StopReport.Kind.ARRIVED, 630, now - 60_000),
                StopReport("16579", "SMET", StopReport.Kind.DEPARTED, 637, now - 60_000),
            ),
            now,
        )
        val lines = CrowdMerger.lines(merged, listOf("TLGP", "SMET"))
        assertTrue("got: ${lines["SMET"]}", lines.getValue("SMET").contains("SMET departed 10:37"))
        assertTrue("got: ${lines["TLGP"]}", lines.getValue("TLGP").contains("no traveller reports for TLGP yet"))
        assertEquals("every asked stop gets a sentence, never a blank", 2, lines.size)
    }

    @Test
    fun an_arrival_only_stop_still_states_its_arrival() {
        val now = 2_000_000L
        val merged = CrowdMerger.merge(
            listOf(StopReport("16579", "TLGP", StopReport.Kind.ARRIVED, 315, now - 120_000)),
            now,
        )
        val lines = CrowdMerger.lines(merged, listOf("TLGP"))
        val line = lines.getValue("TLGP")
        // hours are unpadded in the shipped sentence (10:37's own test pins the format)
        assertTrue("got: $line", line.contains("TLGP arrived 5:15"))
        assertTrue("got: $line", line.contains("reported by travellers"))
    }
}
