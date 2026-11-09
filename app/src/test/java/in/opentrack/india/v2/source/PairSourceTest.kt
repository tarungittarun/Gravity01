package `in`.opentrack.india.v2.source

import `in`.opentrack.india.v2.contract.PairListing
import `in`.opentrack.india.v2.contract.Provenance
import `in`.opentrack.india.v2.contract.SourceKind
import `in`.opentrack.india.v2.contract.TrainIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed-failure contract of a live pair source. Each test is a 8.2 state boundary, and the two the
 * product owes most: "answered empty" and "could not look" never collapse into each other, and an
 * unparseable body never becomes invented rows. The parser here is a fake BY DESIGN - the real erail
 * parser may only be written against the CI capture (4.5), which is a later commit by rule.
 */
class PairSourceTest {

    private val fetchedAt = 1_760_000_000_000L
    private val now = fetchedAt + 60_000L

    private fun liveProvenance(at: Long = fetchedAt) =
        Provenance(SourceKind.LIVE_PAIR, "erail", at)

    private fun listingWithOneRow(at: Long = fetchedAt) = PairListing(
        "SMET", "MYS",
        listOf(
            PairListing.TrainAtStation(
                TrainIdentity("16226", "SMET MYS EXP", "SMET", "MYS", liveProvenance(at)),
                boardMin = 23 * 60 + 25,
            )
        ),
        liveProvenance(at),
    )

    /** Parser fake with three captured-behaviours; the real one arrives with the capture, by rule. */
    private val fakeParser = PairParser { body, at ->
        when {
            body == "UNPARSEABLE GARBAGE" -> PairParse.Unreadable(body.take(20))
            body == "NOTHING RUNS" -> PairParse.NothingRunning
            else -> PairParse.Rows(listingWithOneRow(at))
        }
    }

    private fun deadWire(failure: PairWireFailure) = PairSource(
        "erail",
        { PairWire.Unreachable(failure, "measured detail") },
        fakeParser,
    )

    @Test
    fun disabledByPolicyAnswersWithoutTouchingTheWire() {
        val refused = PairSource("erail", { throw AssertionError("flag off means the wire is never touched") },
                                 fakeParser, enabled = false)
        val answer = refused.search(PairQuery("SMET", "MYS"))
        assertEquals(PairAnswer.DisabledByPolicy("erail"), answer)
    }

    @Test
    fun aNetworkFailureIsCouldNotLookNeverAnEmptyAnswer() {
        // The v1 failure this type exists for: "could not reach" rendered as "no trains", and a passenger
        // believed the route was dead. The types cannot collapse any more.
        val answer = deadWire(PairWireFailure.NETWORK).search(PairQuery("SMET", "MYS"))
        assertTrue("got $answer", answer is PairAnswer.CouldNotLook)
        assertTrue((answer as PairAnswer.CouldNotLook).detail.contains("NETWORK"))
    }

    @Test
    fun timeoutsHttpStatusAndTlsFailuresAreCouldNotLookToo() {
        for (failure in listOf(PairWireFailure.TIMEOUT, PairWireFailure.HTTP_STATUS, PairWireFailure.TLS)) {
            val answer = deadWire(failure).search(PairQuery("SMET", "MYS"))
            assertTrue(failure.toString(), answer is PairAnswer.CouldNotLook)
        }
    }

    @Test
    fun anUnreadableBodyIsProtocolChangedCarryingTheSampleNotInventedRows() {
        val answer = PairSource("erail", { PairWire.Ok("UNPARSEABLE GARBAGE", fetchedAt) }, fakeParser)
            .search(PairQuery("SMET", "MYS"))
        assertEquals(PairAnswer.ProtocolChanged("UNPARSEABLE GARBAGE".take(20)), answer)
    }

    @Test
    fun anEmptyLiveAnswerIsAnsweredEmptyAndSaysViaWhom() {
        val answer = PairSource("erail", { PairWire.Ok("NOTHING RUNS", fetchedAt) }, fakeParser)
            .search(PairQuery("SMET", "MYS"))
        assertTrue("got $answer", answer is PairAnswer.AnsweredEmpty)
        val provenance = (answer as PairAnswer.AnsweredEmpty).provenance
        assertEquals(SourceKind.LIVE_PAIR, provenance.kind)
        assertEquals(fetchedAt, provenance.fetchedAtMillis)
        assertEquals("via erail", provenance.label(now))
    }

    @Test
    fun rowsFlowThroughWithTheirProvenanceUntouched() {
        val answer = PairSource("erail", { PairWire.Ok("BODY", fetchedAt) }, fakeParser)
            .search(PairQuery("SMET", "MYS"))
        val listing = (answer as PairAnswer.Listing).listing
        assertEquals(listOf("16226"), listing.numbers())
        assertEquals("via erail", listing.provenance.label(now))
    }

    @Test
    fun theParserSeesTheWireFetchTimeNotTheWallClockOfParsing() {
        // Provenance stamps come from when the wire answered; a slow parse must never age the data by
        // its own duration (the same sin as stamping a build time as a data vintage).
        var seenAt = -1L
        val parser = PairParser { _, at -> seenAt = at; PairParse.NothingRunning }
        PairSource("erail", { PairWire.Ok("x", fetchedAt) }, parser).search(PairQuery("SMET", "MYS"))
        assertEquals(fetchedAt, seenAt)
    }
}
