package `in`.opentrack.india.v2.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the v2 contract. Each one is a rule v1 broke in production, on the owner's phone, and could not
 * detect: they are here so that "the data looked fine" stops being a possible outcome.
 */
class ContractTest {

    private val now = 1_760_000_000_000L
    private val live = Provenance(SourceKind.LIVE_STOPS, "NTES", now - 60_000L)
    private val asset = Provenance(SourceKind.OFFLINE_ASSET, "refdb", 0L)

    @Test
    fun bakedInTimetableIsNeverWordingThatImpliesLive() {
        assertEquals("timetable", asset.label(now))
    }

    @Test
    fun freshLiveDataNamesItsSource() {
        assertEquals("via NTES", live.label(now))
        assertTrue(live.isAuthoritative(now))
    }

    @Test
    fun liveDataPastItsShelfLifeSaysItIsOldInsteadOfLookingCurrent() {
        val stale = live.copy(fetchedAtMillis = now - 20L * 60_000L)   // 4x the 5-minute life
        assertEquals("live data 20 min old", stale.label(now))
        assertFalse("a 20-minute-old running report must not be called authoritative",
            stale.isAuthoritative(now))
    }

    @Test
    fun anUnstampedFetchIsTreatedAsStaleRatherThanAsNow() {
        // This is the v1 bug in type form: a timestamp that described something else (a build) was printed as
        // though it described the data. A missing time is therefore the least trusted state, not a free pass.
        assertEquals("no fetch time - treat as stale", live.copy(fetchedAtMillis = 0L).label(now))
        assertFalse(live.copy(fetchedAtMillis = -5L).isFresh(now))
    }

    @Test
    fun aCachedRowIsLabelledCachedAndNotViaSource() {
        val cached = Provenance(SourceKind.CACHE, "NTES", now - 3L * 3_600_000L)
        assertEquals("cached 3 h ago", cached.label(now))
        assertFalse(cached.isAuthoritative(now))
    }

    @Test
    fun shelfLivesAreOrderedByHowFastTheUnderlyingFactChanges() {
        assertTrue(Freshness.ttlMillis(SourceKind.LIVE_STOPS) < Freshness.ttlMillis(SourceKind.LIVE_PAIR))
        assertTrue(Freshness.ttlMillis(SourceKind.LIVE_PAIR) < Freshness.ttlMillis(SourceKind.CACHE))
        assertTrue(Freshness.ttlMillis(SourceKind.CACHE) < Freshness.ttlMillis(SourceKind.OFFLINE_ASSET))
    }

    @Test
    fun aReusedNumberIsDetectedAsAConflictAndNotShownAsTheSameTrain() {
        val ours = TrainIdentity("16228", "SHIMOGA-BANGALORE EXPRESS", "SMET", "SBC", asset)
        val liveNow = TrainIdentity("16228", "TALGUPPA MYSURU EXPRESS", "TLGP", "MYS",
            Provenance(SourceKind.LIVE_IDENTITY, "erail.in", now - 60_000L))

        assertTrue(liveNow.disagreesWith(ours))
        val message = liveNow.conflictWith(ours)
        assertNotNull("the contradiction has to be sayable, or the app silently picks a winner", message)
        assertTrue(message!!.contains("16228") && message.contains("this device says SMET→SBC") &&
            message.contains("erail.in says TLGP→MYS"))
    }

    @Test
    fun agreeingIdentitiesProduceNoNoise() {
        val ours = TrainIdentity("16226", "SMET MYS EXP", "SMET", "MYS", asset)
        val liveNow = ours.copy(provenance = Provenance(SourceKind.LIVE_IDENTITY, "erail.in", now))
        assertFalse(liveNow.disagreesWith(ours))
        assertNull(liveNow.conflictWith(ours))
    }

    @Test
    fun aTrainDueBeforeMidnightAndSeenAfterIsFourMinutesLateNotFourteenHoursEarly() {
        val due2358 = RouteStop("SMET", 1, schedDepMin = 1438, actualDepMin = 2, dayOffset = 1,
            provenance = live)
        assertEquals(4, due2358.departureDelayMin())

        // The other form some feeds send: the day already folded into the minute count.
        val folded = due2358.copy(actualDepMin = 1442, dayOffset = 0)
        assertEquals(4, folded.departureDelayMin())
    }

    @Test
    fun aMissingActualIsUnknownRatherThanOnTime() {
        val noArrivalYet = RouteStop("MYS", 9, schedArrMin = 420, actualArrMin = null, provenance = live)
        assertNull("an unreported arrival must not become delay=0", noArrivalYet.arrivalDelayMin())
        assertFalse(noArrivalYet.hasActuals)
    }

    @Test
    fun anEmptyLiveListingIsNotTheSameAsNoAnswer() {
        val emptyButAnswered = PairListing("SMET", "MYS", emptyList(),
            Provenance(SourceKind.LIVE_PAIR, "NTES", now))
        assertTrue("a real 'nothing runs in this window' is a legitimate answer", emptyButAnswered.isEmpty)

        val unreadable = emptyButAnswered.copy(note = "reply had 2 columns, expected 5")
        assertNotNull("and the reason is kept, so the two are distinguishable on screen", unreadable.note)
    }

    @Test
    fun theListSomeoneBooksTheirEveningByStartsWithWhatTheyCanStillCatch() {
        fun at(no: String, board: Int) = PairListing.TrainAtStation(
            TrainIdentity(no, no, "SMET", "MYS", asset), boardMin = board)

        val listing = PairListing("SMET", "MYS", listOf(at("06228", 1330), at("16228", 1380)),
            Provenance(SourceKind.LIVE_PAIR, "NTES", now))
        // 22:51 on the clock: the 22:10 has gone, the 23:00 is in nine minutes. v1 showed them the other way
        // round because one comparator served both dedupe and display.
        assertEquals(listOf("16228", "06228"), listing.sortedForBoarding(1371).map { it.identity.number })
    }
}
