package `in`.opentrack.india.v2.source

import `in`.opentrack.india.v2.contract.PairListing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * The erail by-number ("identity") reply, parsed by the SAME parser as the pair reply - because the
 * 2026-10-12 CI captures show it IS the same tile-record shape, one `^`-anchored record, empty
 * pre-header. These four bodies are byte-exact from the probe job's capture comment on this date
 * (run 38178241527, job `probe`); the parser's field map was already justified by the pair capture,
 * and these tests prove the reuse claim instead of assuming it (4.5: no shape without a capture).
 *
 * What this buys the app: the details screen can ask "what does this number mean today" from the one
 * keyless source a phone can reach, and know name/origin/terminal/endpoint times/days - the founding
 * bug's class (16228 was a different train) is caught live, not archived.
 */
class ErailIdentityCaptureTest {

    private val parser = ErailPairParser()

    private fun capture(trainNo: String): String =
        load("/captures/erail-identity-$trainNo-2026-10-12.txt")

    private fun load(path: String): String =
        javaClass.getResourceAsStream(path)!!.readBytes().toString(Charsets.UTF_8)

    private fun parseOne(trainNo: String): PairListing.TrainAtStation {
        val parse = parser.parse(capture(trainNo), 1_760_000_000_000L)
        val rows = (parse as? PairParse.Rows)?.listing?.trains
        assertNotNull("16221 capture must parse as Rows, got $parse", rows)
        assertEquals("the identity reply carries exactly one record", 1, rows!!.size)
        assertEquals(trainNo, rows[0].identity.number)
        return rows[0]
    }

    @Test
    fun kuvenpu_16221_identity_reads_from_the_capture() {
        val row = parseOne("16221")
        assertEquals("TLGP-MYS KUVEMPU EXPRESS", row.identity.name)
        assertEquals("TLGP", row.identity.fromCode)
        assertEquals("MYS", row.identity.toCode)
        // 05.50 -> 15.30 with no wrap: 350 -> 930 (the identity tile times the ORIGIN departure;
        // the pair tile for the same train times the SMET boarding, 07.55 - both coherent).
        assertEquals(350, row.boardMin)
        assertEquals(930, row.alightMin)
        assertEquals(0, row.alightDayOffset)
    }

    @Test
    fun the_2028_records_day_wrap_is_the_cumulative_rule_not_a_reset() {
        val row = parseOne("16228")
        assertEquals("TLGP MYS EXP", row.identity.name)
        assertEquals("TLGP", row.identity.fromCode)
        assertEquals("MYS", row.identity.toCode)
        // 20.55 -> 08.20 next day: 1255 -> 500, wrap flagged, never midnight-erased.
        assertEquals(1255, row.boardMin)
        assertEquals(500, row.alightMin)
        assertEquals(1, row.alightDayOffset)
    }

    @Test
    fun the_12627_long_haul_identity_reads_from_the_capture() {
        val row = parseOne("12627")
        assertEquals("KARNATAKA EXP", row.identity.name)
        assertEquals("SBC", row.identity.fromCode)
        assertEquals("NDLS", row.identity.toCode)
        // 19.20 -> 09.00 next day: 1160 -> 540, wrap flagged.
        assertEquals(1160, row.boardMin)
        assertEquals(540, row.alightMin)
        assertEquals(1, row.alightDayOffset)
    }

    @Test
    fun the_16226_identity_reads_from_the_capture() {
        val row = parseOne("16226")
        assertEquals("SMET-MYS UR EXP", row.identity.name)
        assertEquals("SMET", row.identity.fromCode)
        assertEquals("MYS", row.identity.toCode)
        assertEquals(685, row.boardMin) // 11.25 = 11*60+25
        assertEquals(1025, row.alightMin) // 17.05
        assertEquals(0, row.alightDayOffset)
    }

    @Test
    fun every_identity_capture_carries_the_feeds_own_data_stamp_in_the_note() {
        for (no in listOf("16221", "16226", "16228", "12627")) {
            val parse = parser.parse(capture(no), 1_760_000_000_000L) as? PairParse.Rows
            assertNotNull(no, parse)
            val note = parse!!.listing.note ?: ""
            assertTrue("no stamp note for $no: $note", note.contains("erail data stamp"))
            assertTrue("note must not dress the stamp as fetch time: $note", note.contains("not our fetch time"))
        }
    }

    @Test
    fun a_reply_with_no_record_anchor_is_unreadable_not_an_empty_truth() {
        val body = capture("16221").replace("^", "") // no anchor: no record, no guess
        val parse = parser.parse(body, 1_760_000_000_000L)
        assertTrue("got $parse", parse is PairParse.Unreadable)
    }
}
