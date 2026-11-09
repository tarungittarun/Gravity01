package `in`.opentrack.india.v2.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second witness's parser against the capture it was written from: confirmtkt's running-status
 * page for 20651, fetched 2026-10-12 ~18:05 IST while the train ran (~30 min late past Shimoga Town).
 * Name-aligned (the page has no codes), order-preserving, and - asserted here - it must read the
 * MIDDLE halts' two times as arr+dep, the origin's single time as a departure, and the terminus's as
 * an arrival, with the page's own "Delay by N min" becoming the delay word.
 */
class ConfirmTktMirrorTest {

    private val stops = listOf(
        "SBC" to "KSR Bengaluru", "YPR" to "Yesvantpur Jn", "TK" to "Tumkur", "TTR" to "Tiptur",
        "ASK" to "Arsikere Jn", "DRU" to "Kadur", "RRB" to "Birur Jn", "TKE" to "Tarikere Jn",
        "BDVT" to "Bhadravati", "SME" to "Shivamogga H", "SMET" to "Shivamogga Town",
        "KMSI" to "Kumsi", "ARU" to "Arasalu", "ANF" to "Anandapuram", "SRF" to "Sagar Jambagaru",
        "TLGP" to "Talguppa",
    )

    private fun capture(): String =
        javaClass.getResourceAsStream("/captures/confirmtkt-20651-2026-10-12.txt")!!
            .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `the capture parses to one labelled line per halt`() {
        val lines = ConfirmTktMirror.parse(capture(), stops)
        assertEquals("every routed halt found by name", 16, lines.size)
        assertEquals("12-Oct", ConfirmTktMirror.pageDayOf(capture()))
        assertTrue(lines.containsKey("SBC") && lines.containsKey("TLGP"))
    }

    @Test
    fun `middle halts read arr and dep from the two times`() {
        val lines = ConfirmTktMirror.parse(capture(), stops)
        assertEquals(
            "ASK arr 17:33 · dep 17:39 — via confirmtkt (24 min late)",
            lines["ASK"],
        )
        assertEquals(
            "SMET arr 19:37 · dep 19:40 — via confirmtkt (30 min late)",
            lines["SMET"],
        )
    }

    @Test
    fun `the origin's single minute is a departure, the terminus's an arrival`() {
        val lines = ConfirmTktMirror.parse(capture(), stops)
        assertEquals("SBC dep 15:16 — via confirmtkt (16 min late)", lines["SBC"])
        assertEquals("TLGP arr 22:15 — via confirmtkt (30 min late)", lines["TLGP"])
    }

    @Test
    fun `name drift across sources still matches`() {
        val strict = ConfirmTktMirror.parse(capture(), listOf("TK" to "Tumkur"))
        assertTrue("Tumakuru (capture) matches Tumkur (our timetable) via the prefix fallback", strict.containsKey("TK"))
    }

    @Test
    fun `a page without our halts renders nothing and the wire says why`() {
        assertTrue(ConfirmTktMirror.parse("<html>no such train</html>", stops).isEmpty())
        val dead = ConfirmTktMirror.fetchOrWhy("20651", stops) { null to "HTTP 403" }
        assertEquals(false, dead.ok)
    }
}
