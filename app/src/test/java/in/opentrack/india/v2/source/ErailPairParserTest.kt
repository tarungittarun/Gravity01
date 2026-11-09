package `in`.opentrack.india.v2.source

import `in`.opentrack.india.v2.contract.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the erail pair parser, driven by the CAPTURED payload (4.5: a parser may only be written
 * against a captured payload - this file IS that payload, byte-exact from the CI probe comment of
 * 2026-10-12 01:31 IST). The expected values were read off the owner's railway reality: 16228/16205/16226/
 * 16221 are the four services erail listed for SMET->MYS, and 16221 - missing from our offline dump since
 * forever - is the Kuvempu Express, the fact the owner corrected by hand on 2026-10-12.
 */
class ErailPairParserTest {

    private val captured: String by lazy {
        val stream = ErailPairParserTest::class.java
            .getResourceAsStream("/captures/erail-pair-SMET-MYS-2026-10-12.txt")
            ?: throw AssertionError("the capture resource is missing - the parser may not run without it")
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private val parser = ErailPairParser()
    private val fetchedAt = 1_760_100_000_000L
    private val now = fetchedAt + 30_000L

    @Test
    fun theRealCaptureYieldsTheFourRealServices() {
        val parse = parser.parse(captured, fetchedAt)
        assertTrue("got $parse", parse is PairParse.Rows)
        val listing = (parse as PairParse.Rows).listing
        assertEquals(listOf("16228", "16205", "16226", "16221"), listing.numbers())
        assertEquals("SMET", listing.fromCode)
        assertEquals("MYS", listing.toCode)
    }

    @Test
    fun theKuvempuIsOnThePairOurDumpMisses() {
        // The whole reason v2 exists: 16221 vanished from SMET->MYS in our copy because the dump keyed it
        // in the reverse direction. The live answer has it, with the identity the owner verified by hand.
        val listing = (parser.parse(captured, fetchedAt) as PairParse.Rows).listing
        val kuvempu = listing.trains.first { it.identity.number == "16221" }
        assertEquals("TLGP-MYS KUVEMPU EXPRESS", kuvempu.identity.name)
        assertEquals("TLGP", kuvempu.identity.fromCode)
        assertEquals("MYS", kuvempu.identity.toCode)
    }

    @Test
    fun timesAreMinutesWithTheMidnightWrapAsDayOffset() {
        val listing = (parser.parse(captured, fetchedAt) as PairParse.Rows).listing
        val t16228 = listing.trains.first { it.identity.number == "16228" }
        assertEquals(23 * 60, t16228.boardMin)          // 23.00 dep from SMET
        assertEquals(8 * 60 + 20, t16228.alightMin)     // 08.20 into MYS
        assertEquals(1, t16228.alightDayOffset)          // ...which is tomorrow
        val t16226 = listing.trains.first { it.identity.number == "16226" }
        assertEquals(11 * 60 + 25, t16226.boardMin)     // 11.25 -> 17.05 same day
        assertEquals(17 * 60 + 5, t16226.alightMin)
        assertEquals(0, t16226.alightDayOffset)
    }

    @Test
    fun everyRowAndTheListingCarryTheWireFetchTimeAsProvenance() {
        val listing = (parser.parse(captured, fetchedAt) as PairParse.Rows).listing
        assertEquals("via erail", listing.provenance.label(now))
        assertEquals(SourceKind.LIVE_PAIR, listing.provenance.kind)
        for (t in listing.trains) {
            assertEquals(fetchedAt, t.identity.provenance.fetchedAtMillis)
            assertEquals("via erail", t.identity.provenance.label(now))
        }
    }

    @Test
    fun theFeedsOwnStampTravelsInTheNoteNotDressedUpAsFreshness() {
        // The capture's header stamp says 2026-8-6: the erail pair feed is timetable-vintage, not
        // minutes-fresh. The label says "via erail" (our fetch is fresh); the note says the data's date.
        val listing = (parser.parse(captured, fetchedAt) as PairParse.Rows).listing
        assertNotNull(listing.note)
        assertTrue(listing.note!!.contains("2026-8-6-21-30-34"))
        assertTrue(listing.note!!.contains("not our fetch time"))
    }

    @Test
    fun boardableTrainsComeFirstWhenTheListingIsSortedForBoarding() {
        // 11:40 at the station: 16205 (16.50) and 16228 (23.00) are still catchable, soonest first;
        // 16226 left 15 min ago; 16221 left hours ago. The M2 promise, on the real live answer.
        val listing = (parser.parse(captured, fetchedAt) as PairParse.Rows).listing
        val order = listing.sortedForBoarding(11 * 60 + 40).map { it.identity.number }
        assertEquals(listOf("16205", "16228", "16226", "16221"), order)
    }

    @Test
    fun aRecordWithoutTimesStillListsTheTrainWithNullableTimes() {
        // Derived from the capture: 16226's record truncated after the alighting code. The train exists;
        // the times are unknown; unknown renders as a dash - never zero, never dropped (2.4).
        val header = captured.substringBefore("^")
        val rec = "^16226~SMET-MYS UR EXP~Shivamogga Town~SMET~Mysore Jn~MYS~Shivamogga Town~SMET~Mysore Jn~MYS~"
        val parse = parser.parse(header + rec, fetchedAt)
        val listing = (parse as PairParse.Rows).listing
        val row = listing.trains.single()
        assertEquals("16226", row.identity.number)
        assertNull(row.boardMin)
        assertNull(row.alightMin)
        assertEquals(0, row.alightDayOffset)
    }

    @Test
    fun recordsWeCannotReadNeverBecomeNothingRunning() {
        // CI caught this one live (a446511): a body whose only record we could not read returned
        // NothingRunning - our blindness dressed up as "the railway runs nothing", the exact 2.3
        // conflation. It is Unreadable, carrying the sample.
        val header = captured.substringBefore("^")
        val broken = "^99999~NAME WITHOUT ROUTE FIELDS"
        val parse = parser.parse(header + broken, fetchedAt)
        assertTrue("got $parse", parse is PairParse.Unreadable)
        // ...and the same body WITH one good record is a listing whose note counts what it could not
        // read. (The first version sliced off the ^16226 anchor and re-prefixed only "^", so the
        // number field came out empty and the parser HONESTLY returned Unreadable - the 898b196
        // ClassCast was this test's bug, not the parser's.)
        val good = "^16226" + captured.substringAfter("^16226").substringBefore("^16221")
        val mixed = parser.parse(header + good + broken, fetchedAt)
        val listing = (mixed as PairParse.Rows).listing
        assertEquals(listOf("16226"), listing.numbers())
        assertTrue("note was ${listing.note}", listing.note!!.contains("1 record(s) not readable"))
    }

    @Test
    fun aValidHeaderWithNoRecordsIsAnsweredEmptyNotProtocolChanged() {
        val headerOnly = "~SMET~Shivamogga Town~MYS~Mysore Jn~~2026-8-6-21-30-34~~~"
        assertEquals(PairParse.NothingRunning, parser.parse(headerOnly, fetchedAt))
    }

    @Test
    fun garbageIsUnreadableCarryingASample() {
        val parse = parser.parse("<html>404 page</html>", fetchedAt)
        assertTrue(parse is PairParse.Unreadable)
        assertEquals("<html>404 page</html>".take(40), (parse as PairParse.Unreadable).sample)
        assertEquals(PairParse.Unreadable(""), parser.parse("", fetchedAt))
    }

    @Test
    fun aNumberIsNeverAcceptedAsAStationCode() {
        // The SMET->MET lesson in its final form: codes are validated, not sliced, and 16228 is a number.
        assertTrue(ErailPairParser.isStationCode("SMET"))
        assertTrue(ErailPairParser.isStationCode("SBC"))
        assertTrue(!ErailPairParser.isStationCode("16228"))
        assertTrue(!ErailPairParser.isStationCode("SHIVAMOGGA"))
    }
}
