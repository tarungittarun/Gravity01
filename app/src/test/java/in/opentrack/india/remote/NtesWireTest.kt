package `in`.opentrack.india.remote

import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The live feed's protocol, pinned by vectors from an independent implementation.
 *
 * These tests are the reason the provider can ship without a key of its own: the crypto is the only part of
 * the app whose correctness cannot be judged by looking at the screen, and a wrong byte in the envelope
 * produces "no live data" on a device that is otherwise working perfectly. So the expected ciphertexts here
 * were produced by a separate reference implementation of the same scheme (AES-128-CBC with the shared
 * static key/IV, base64, uppercase hex, MD5 signature over plaintext+secret) and pasted in as constants.
 * If this file passes, the app asks the question the way the official app does.
 */
class NtesWireTest {

    @Test
    fun `envelope matches the reference implementation byte for byte`() {
        assertEquals(
            "B1F8A437E806CE68C8A6C68FD8BC323F#" +
                "69514E6B525334524B55704C526673456F48565A6C673D3D",
            NtesWire.wrap("a"),
        )
        assertEquals(
            "2A3D2D2DADDA9C79AF2D39B5A272D4DC#" +
                "4D4A4D576741796C695A4E6578727A777272634E346E622F6D384A3772414A6C7A734A6D4D78734B6A6B343D",
            NtesWire.wrap("16-bytes-exactly!"),
        )
        assertEquals(
            "F113F345D1296F0DB6A849F4B6694E9A#" +
                "6430384136355152636A2B7345435539465A72522B37364E7358716157656E6F756C52516F4A6169513773624462" +
                "43325A4B4A534B5363426837667A4968785661666B394632352F2B6C4C794C526C67625A4D6F617144356B746632" +
                "392B795251303061386975714A3864556E5452753038794853754B653152314E37473446",
            NtesWire.wrap(
                "service=TrainRunningMob&subService=ShowFullRunJson&trainNo=12627&startDate=11-Oct-2026",
            ),
        )
    }

    @Test
    fun `openEnvelope accepts a bare payload and a jsonIn wrapper`() {
        val hex = "454E466C634D6548416631476A625A32626A6C4D45673D3D"
        val sig = "F5E96DF72E4C79070B2769876465AAD0"
        assertEquals("{\"a\":1}", NtesWire.unwrap("$sig#$hex"))
        assertEquals("{\"a\":1}", NtesWire.unwrap("""{"jsonIn":"$sig#$hex"}"""))
        val doc = NtesWire.unwrapJson("""{"jsonIn":"15519AE17B475EE7BE1B620B20A2FAC0#373354785A4B4F31354B3063345A7843452B4B787245396A57714F433770374F3056646D526F454C6345493D"}""")
        assertNotNull(doc)
        assertEquals("MAS", doc!!.getJSONArray("S").getJSONObject(0).getString("STN"))
    }

    @Test
    fun `base64 matches the jdk implementation on every tail length`() {
        // The hand-rolled codec exists only because java.util.Base64 needs API 26 and android.util.Base64 is
        // a stub under plain JUnit, so it is pinned here against the JDK over 0..64-byte blocks and the
        // padding cases, which is where a by-hand implementation actually breaks.
        val rnd = java.util.Random(7)   // fixed seed: the padding cases must be reproducible
        for (len in 0..64) {
            val bytes = ByteArray(len).also { rnd.nextBytes(it) }
            assertEquals("len=$len", Base64.getEncoder().encodeToString(bytes), NtesWire.b64EncodeForTest(bytes))
            assertTrue(
                "round trip for len=$len",
                NtesWire.b64DecodeForTest(Base64.getEncoder().encodeToString(bytes)).contentEquals(bytes),
            )
        }
    }

    @Test
    fun `wire date is the Indian train day in dd-MMM-yyyy`() {
        // 2026-10-11 as an epoch day, formatted the way the feed keys a run.
        assertEquals("11-Oct-2026", NtesWire.dateForWire(20737L))
        assertTrue(NtesWire.plainFor(NtesWire.SUB_LIVE, "12627", 20737L).startsWith("service=TrainRunningMob"))
        assertEquals(
            "service=TrainRunningMob&subService=ShowFullRunJson&trainNo=12627&startDate=11-Oct-2026",
            NtesWire.plainFor(NtesWire.SUB_LIVE, "12627", 20737L),
        )
    }

    /**
     * A captured-shape answer from the mobile feed, indented for reading. It is fed to the parser as is:
     * JSON ignores the whitespace between tokens, and an earlier draft of this fixture stripped every space
     * out of the whole document — which quietly mangled the message inside the strings and turned the
     * "the feed said it is 12 minutes late" assertion into a test of the test's own formatter.
     */
    private val fixture = """
        {"CTT":"JKM","CTT_NAME":"Jhamat","NEXT":"BP","TMNAME":"12627 CHENNAI EXP",
         "MSG":"Train is running late by 12 minutes","LASTUPD":"11-10-2026 09:42",
         "S":[
           {"STN":"MAS","ARR":"--","ATA":"--","DEP":"20.00","ATD":"20.12","ARRPLT":"","DEPPLT":"5","DIST":"0","DELAY":"12"},
           {"STN":"AJJ","ARR":"20.48","ATA":"21.03","DEP":"20.53","ATD":"21.08","ARRPLT":"2","DEPPLT":"2","DIST":"46","DELAY":"15"},
           {"STN":"JKM","ARR":"21.54","ATA":"22.10","DEP":"21.56","ATD":"22.12","ARRPLT":"3","DIST":"89","DELAY":"16"},
           {"STN":"BP","ARR":"00.35","ATA":"","DEP":"00.40","ATD":"","ARRPLT":"","DIST":"231","DELAY":""}
         ]}
    """.trimIndent()

    @Test
    fun `running times arrive as actuals and stay absent where the feed said nothing`() {
        val p = checkNotNull(NtesWire.parse(fixture)) { "the fixture must parse" }
        assertEquals(4, p.rows.size)
        assertTrue("a feed with actuals must be recognised as live", p.hasLiveColumns)
        assertEquals("JKM", p.currentCode)
        assertEquals("BP", p.nextCode)
        assertEquals("Train is running late by 12 minutes", p.message)
        assertEquals(1791691920000L, p.feedStampMillis)

        val mas = p.rows[0]
        // "--" is the feed's own "not reported", and it must stay null: the difference between a dashed cell
        // and a zero here is the difference between "unknown" and "the train sat there for 00:00".
        assertNull(mas.schedArr)
        assertNull(mas.actArr)
        assertEquals(1200, mas.schedDep)
        assertEquals(1212, mas.actDep)
        assertEquals(12, mas.delayArr)
        assertEquals("5", mas.platform)
        assertEquals(0, mas.km)

        val ajj = p.rows[1]
        assertEquals(1248, ajj.schedArr)
        assertEquals(1263, ajj.actArr)
        assertEquals(1268, ajj.actDep)
        assertEquals("2", ajj.platform)
        assertEquals(46, ajj.km)

        // A halted-forever cell: scheduled time known, actual not yet — parsed as such, not as on time.
        val bp = p.rows[3]
        assertEquals(35, bp.schedArr)
        assertNull(bp.actArr)
        assertNull(bp.delayArr)
    }

    @Test
    fun `snapshot anchors on the last reported halt`() {
        val p = checkNotNull(NtesWire.parse(fixture))
        val snap = checkNotNull(NtesAdapter.toSnapshot(p, "12627", 20737L)) { "anchor must be a real halt" }
        assertEquals("JKM", snap.atCode)
        assertEquals(1332, snap.eventMinute)
        assertEquals(1316, snap.scheduledMinuteAtEvent)
        assertEquals(StopStatus.DEPARTED, snap.event)
        assertEquals(16, snap.delayMinutes)
        assertEquals("BP", snap.nextCode)
        // The feed had not reported an actual for BP, so no expected time is invented for it; the domain
        // projects one from the anchor and labels it a projection.
        assertNull(snap.expectedNextMinute)
        assertEquals(4, snap.perStop.size)
        assertEquals(1263, snap.perStop["AJJ"]?.actualArrivalMin)
        assertNull(snap.perStop["BP"]?.actualDepartureMin)
        assertEquals(SourceKind.PROVIDER_NTES, snap.sourceKind)
        assertEquals(1791691920000L, snap.feedStampMillis)
    }

    @Test
    fun `an earlier delay or untimed status header cannot redate the latest arrival`() {
        val body = """{"CTT":"FUT","S":[
          {"STN":"OLD","ARR":"10.00","ATA":"10.18","DEP":"10.03","ATD":"10.21","DELAY":"18"},
          {"STN":"NEW","ARR":"10.40","ATA":"10.45","DEP":"10.42","ATD":""},
          {"STN":"FUT","ARR":"11.10","DEP":"11.12"}]}"""
        val snap = NtesAdapter.toSnapshot(NtesWire.parse(body)!!, "16221", 20742L)!!
        assertEquals("NEW", snap.atCode)
        assertEquals(645, snap.eventMinute)
        assertNull("the previous stop's +18m cannot belong to NEW", snap.delayMinutes)
        assertEquals(18, snap.perStop["OLD"]?.delayArrivalMin)
    }

    @Test
    fun `future timetable rows cannot turn an arrival-only anchor into a departure`() {
        val body = """{"S":[
          {"STN":"MYS","DEP":"19.40","ATD":"19.40"},
          {"STN":"SMET","ARR":"05.00+1","ATA":"04.53+1","DEP":"05.05+1","ATD":""},
          {"STN":"KMSI","ARR":"05.29+1","DEP":"05.30+1","DELAY":"14"},
          {"STN":"TLGP","ARR":"07.50+1"}]}"""
        val arrived = NtesAdapter.toSnapshot(NtesWire.parse(body)!!, "16227", 20740L)!!
        assertEquals("SMET", arrived.atCode)
        assertEquals(StopStatus.ARRIVED, arrived.event)
        assertNull(arrived.perStop["SMET"]!!.actualDepartureMin)
        // A separate feed departure flag may be meaningful, but supplies NO Act minute.
        val flagged = body.replace("\"ATD\":\"\"", "\"ATD\":\"\",\"DEPARTED\":true")
        val departed = NtesAdapter.toSnapshot(NtesWire.parse(flagged)!!, "16227", 20740L)!!
        assertEquals(StopStatus.DEPARTED, departed.event)
        assertNull(departed.perStop["SMET"]!!.actualDepartureMin)
    }

    @Test
    fun `a timetable-only answer is not live`() {
        val p = checkNotNull(
            NtesWire.parse(
                """{"S":[{"STN":"MAS","ARR":"--","ATA":"--","DEP":"20.00","ATD":"--","DIST":"0"},""" +
                    """{"STN":"AJJ","ARR":"20.48","ATA":"--","DEP":"20.53","ATD":"--","DIST":"46"}]}""",
            ),
        )
        assertEquals(2, p.rows.size)
        assertFalse("dashes must not read as live data", p.hasLiveColumns)
        assertFalse(p.hasReportedTimes)
        assertNull(NtesAdapter.toSnapshot(p, "12627", 20737L))
    }

    @Test
    fun `an unrecognised shape yields nothing rather than nonsense`() {
        assertNull(NtesWire.parse("""{"status":"NO DATA"}"""))
        assertNull(NtesWire.parse("not json at all"))
        // Rows that name no station anchor nothing, so the adapter refuses the answer and the chain stays
        // free to try the alternate source instead of painting an all-dashed screen in a "live" badge.
        val junk = checkNotNull(NtesWire.parse("""{"S":[{"nope":"1"}]}"""))
        assertNull(NtesAdapter.toSnapshot(junk, "12627", 20737L))
    }

    @Test
    fun `day rollover past midnight stays on the run axis`() {
        val p = NtesWire.parse("""{"S":[{"STN":"BP","ARR":"00.35+1","ATA":"01.02+1","DEP":"00.40"}]}""")!!
        assertEquals(35 + 1440, p.rows[0].schedArr)
        assertEquals(62 + 1440, p.rows[0].actArr)
    }
}
