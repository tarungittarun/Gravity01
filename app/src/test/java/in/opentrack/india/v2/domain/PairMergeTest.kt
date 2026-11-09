package `in`.opentrack.india.v2.domain

import `in`.opentrack.india.v2.source.PairAnswer
import `in`.opentrack.india.v2.contract.PairListing
import `in`.opentrack.india.v2.contract.Provenance
import `in`.opentrack.india.v2.contract.SourceKind
import `in`.opentrack.india.v2.contract.TrainIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge is where the product's honesty is decided: which trains are listed, whose times win, and
 * what the screen SAYS about it. Every test here is one 8.2 sentence, and the 16221/06228 pair is the
 * real situation this app exists for.
 */
class PairMergeTest {

    private val t0 = 1_760_000_000_000L
    private val live = Provenance(SourceKind.LIVE_PAIR, "erail", t0)
    private val asset = Provenance(SourceKind.OFFLINE_ASSET, "refdb", 0L)

    private fun liveListing(vararg rows: Triple<String, Int?, Int?>): PairListing = PairListing(
        "SMET", "MYS",
        rows.map { (no, b, a) ->
            PairListing.TrainAtStation(
                TrainIdentity(no, "TRAIN $no", "SMET", "MYS", live),
                boardMin = b,
                alightMin = a,
                alightDayOffset = if (b != null && a != null && a <= b) 1 else 0,
            )
        },
        note = "erail data stamp 2026-8-6-21-30-34 (the feed's own date, not our fetch time)",
        provenance = live,
    )

    private fun assetRow(no: String, board: Int?, alight: Int?) =
        AssetRow(no, "TRAIN $no", "SMET", "MYS", board, alight, asset)

    @Test
    fun theRenumberedTrainIsNotShownTwiceWhenTheSourceNamesBothNumbers() {
        // 16579 beside 20689 at 09:15 (the owner's screenshots, 2026-10-12): one physical train,
        // renumbered; the offline dump still carries the old number. The evidence is the source's
        // own answer - asked about "16579", erail replied with the name it also answers for 20689.
        val assetRows = listOf(assetRow("16579", 555, 855))
        val answer = PairAnswer.Listing(liveListing(Triple("20689", 555, 845)))
        val merged = PairMerge.merge(assetRows, answer, nowMinuteOfTarget = 600)
        assertEquals("the duplicate first: offline row + live row for one train", 2, merged.rows.size)

        val (rows, applied) = PairMerge.dedupeRenumbered(
            merged.rows,
            listOf(PairMerge.Renumber("16579", "20689", "SMET INTERCITY")),
        )
        assertEquals("one physical train, one row", listOf("20689"), rows.map { it.identity.number })
        assertEquals(1, applied.size)
    }

    @Test
    fun withoutEvidenceNothingIsDropped() {
        val assetRows = listOf(assetRow("16579", 555, 855))
        val answer = PairAnswer.Listing(liveListing(Triple("20689", 555, 845)))
        val merged = PairMerge.merge(assetRows, answer, nowMinuteOfTarget = 600)
        val (rows, applied) = PairMerge.dedupeRenumbered(merged.rows, emptyList())
        assertEquals("both rows stay when the live source was never asked", 2, rows.size)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun theLiveTwinMustBePresentBeforeAnOfflineRowIsDropped() {
        val assetRows = listOf(assetRow("16579", 555, 855))
        val answer = PairAnswer.Listing(liveListing(Triple("20689", 555, 845)))
        val merged = PairMerge.merge(assetRows, answer, nowMinuteOfTarget = 600)
        val (rows, applied) = PairMerge.dedupeRenumbered(
            merged.rows,
            listOf(PairMerge.Renumber("16579", "20690", "SMET INTERCITY")),
        )
        assertEquals("never drop the only copy of a train", 2, rows.size)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun theMergeNoteNamesOldNumberNewNumberAndTheSourceAnswer() {
        val note = PairMerge.renumberNote(listOf(PairMerge.Renumber("16579", "20689", "SMET INTERCITY")))
        assertTrue("got: $note", note.contains("16579"))
        assertTrue("got: $note", note.contains("20689"))
        assertTrue("got: $note", note.contains("SMET INTERCITY"))
        assertTrue("got: $note", note.contains("one train"))
    }

    @Test
    fun aLiveListingUpgradesItsAssetTwinAndAddsLiveOnlyTrains() {
        // The real shape: asset has 16226 (and, wrongly, 06228); the live list carries 16226 and the
        // 16221 our dump has never listed in this direction.
        val assetRows = listOf(assetRow("16226", 690, 1030), assetRow("06228", 1330, 480))
        val answer = PairAnswer.Listing(
            liveListing(Triple("16226", 685, 1025), Triple("16221", 475, 930))
        )
        val merged = PairMerge.merge(assetRows, answer, nowMinuteOfTarget = 700)

        val byNo = merged.rows.associateBy { it.identity.number }
        val upgraded = byNo.getValue("16226")
        assertTrue(upgraded.confirmedByLive)
        assertFalse(upgraded.liveOnly)
        assertEquals("live times win over the asset's for the same train", 685, upgraded.boardMin)
        val liveOnly = byNo.getValue("16221")
        assertTrue(liveOnly.liveOnly)
        assertEquals(475, liveOnly.boardMin)
        // the number only our dump has stays, labelled for what it is (7.3: not proof of absence)
        val assetOnly = byNo.getValue("06228")
        assertFalse(assetOnly.confirmedByLive)
        assertFalse(assetOnly.liveOnly)
        assertEquals(1330, assetOnly.boardMin)
    }

    @Test
    fun liveOnlyRowsAreAppendedAfterTheAssetRowsInBoardingOrder() {
        val assetRows = listOf(assetRow("16226", 690, 1030))
        val answer = PairAnswer.Listing(
            liveListing(Triple("16221", 475, 930), Triple("56209", 1400, 1500))
        )
        val merged = PairMerge.merge(assetRows, answer, 700)
        assertEquals(listOf("16226", "16221", "56209"), merged.rows.map { it.identity.number })
        assertTrue(merged.liveListUsed)
    }

    @Test
    fun aNullLiveBoardFallsBackToTheAssetMinuteNotToZero() {
        // A live record without a time is unknown, and unknown must never render as midnight (2.4).
        val assetRows = listOf(assetRow("16226", 690, 1030))
        val answer = PairAnswer.Listing(liveListing(Triple("16226", null, null)))
        val merged = PairMerge.merge(assetRows, answer, 700)
        assertEquals(690, merged.rows.single().boardMin)
    }

    @Test
    fun everyStateHasItsExactSentence() {
        val assetRows = listOf(assetRow("16226", 690, 1030))
        assertEquals(
            "offline — every time below is the timetable",
            PairMerge.merge(assetRows, null, 700).stateLine,
        )
        val couldNot = PairAnswer.CouldNotLook("NETWORK: timed out")
        assertEquals(
            "no live list reached this device (NETWORK: timed out) — every time below is the timetable",
            PairMerge.merge(assetRows, couldNot, 700).stateLine,
        )
        val empty = PairAnswer.AnsweredEmpty(live)
        assertTrue(PairMerge.merge(assetRows, empty, 700).stateLine
            .startsWith("the live list shows no trains for this pair"))
        val changed = PairAnswer.ProtocolChanged("<html>404")
        assertTrue(PairMerge.merge(assetRows, changed, 700).stateLine.contains("format may have changed"))
        val disabled = PairAnswer.DisabledByPolicy("erail")
        assertTrue(PairMerge.merge(assetRows, disabled, 700).stateLine.contains("disabled by policy"))
    }

    @Test
    fun theListingStateLineNamesTheSourceTheStampAgeAndTheAddedTrains() {
        val assetRows = listOf(assetRow("16226", 690, 1030))
        val answer = PairAnswer.Listing(
            liveListing(Triple("16221", 475, 930))
        )
        val line = PairMerge.merge(assetRows, answer, 700).stateLine
        assertTrue("was: $line", line.startsWith("live list via erail"))
        assertTrue(line.contains("2026-8-6-21-30-34"))
        assertTrue(line.contains("1 train(s) the offline copy lacks"))
    }

    @Test
    fun assetRowsWithoutALiveAnswerKeepTheirOwnProvenance() {
        val merged = PairMerge.merge(listOf(assetRow("16226", 690, 1030)), null, 700)
        val row = merged.rows.single()
        assertEquals(SourceKind.OFFLINE_ASSET, row.identity.provenance.kind)
        assertEquals("timetable is the only honest word for it", 0L, row.identity.provenance.fetchedAtMillis)
    }

    @Test
    fun anOvernightLiveRowConvertsToCumulativeMinutesTheV1RowsSpeak() {
        // The symptom this is named after: 16228 boards 23:00, alights 08:20 (+1). Naive minute-of-day
        // conversion made the duration NEGATIVE (500-1380 = -880) and hid "arrives next day". v1 rows
        // speak cumulative minutes, so the conversion adds the day.
        val answer = PairAnswer.Listing(liveListing(Triple("16228", 1380, 500)))
        val merged = PairMerge.merge(emptyList(), answer, 700)
        val (hits, hidden) = PairMerge.toTrainHits(merged.rows, 700, originOf = { 20L })
        val hit = hits.single()
        assertEquals(1380, hit.boardMinute)
        assertEquals(500 + 1440, hit.alightMinute)
        assertEquals("a 9h20 journey, not minus fourteen hours", 560, hit.durationMin)
        assertEquals("erail endpoints · route checked on open", hit.sourceTag)
        assertEquals(20L, hit.originDateEpochDay)
        assertEquals(0, hidden)
    }

    @Test
    fun aRowWithoutAReadableTimeIsCountedNotRenderedAsMidnight() {
        val assetRows = listOf(assetRow("16226", 690, 1030))
        val answer = PairAnswer.Listing(liveListing(Triple("56209", null, null)))
        val merged = PairMerge.merge(assetRows, answer, 700)
        val (hits, hidden) = PairMerge.toTrainHits(merged.rows, 700, originOf = { 0L })
        assertEquals(listOf("16226"), hits.map { it.trainNo })
        assertEquals(1, hidden)
    }

    @Test
    fun theAssetOnlyRowIsLabelledTimetableTheLiveRowViaErail() {
        val assetRows = listOf(assetRow("16226", 690, 1030))
        val answer = PairAnswer.Listing(liveListing(Triple("16221", 475, 930)))
        val merged = PairMerge.merge(assetRows, answer, 700)
        val (hits, _) = PairMerge.toTrainHits(merged.rows, 700, originOf = { 0L })
        val byNo = hits.associateBy { it.trainNo }
        assertEquals("timetable", byNo.getValue("16226").sourceTag)
        assertEquals("erail endpoints · route checked on open", byNo.getValue("16221").sourceTag)
    }
}
