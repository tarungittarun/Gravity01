package `in`.opentrack.india.v2.domain

import `in`.opentrack.india.model.TrainHit
import `in`.opentrack.india.v2.source.PairAnswer
import `in`.opentrack.india.v2.contract.PairListing
import `in`.opentrack.india.v2.contract.TrainIdentity
import `in`.opentrack.india.v2.contract.Provenance

/**
 * The merge of one live pair answer with the offline asset's rows - pure, typed, and honest about
 * which decision is whose:
 *
 *  - SELECTION: a live listing that is fresh is the authority for WHICH trains run the pair today.
 *    A number the railway lists but our dump omits (the 16221 case) must appear; a number only our
 *    dump has (the 06228 case) stays, labelled `timetable`, because a retired number is not proof of
 *    a nonexistent train (7.3) - the list may contain both, each saying what it is.
 *  - ORDER: asset rows keep the asset's order (the finder's boarding-window sort), live-only rows are
 *    appended after them, soonest boarding first. One ordering decision per source of rows, never one
 *    comparator serving two decisions (lesson 5).
 *
 * The output carries the exact user-facing state line (8.2 vocabulary), chosen here - one place - so
 * no screen can improvise honesty.
 */
object PairMerge {

    /** One display row: the identity+times to show, and the label that says where they came from. */
    data class MergedRow(
        val identity: TrainIdentity,
        val boardMin: Int?,
        val alightMin: Int?,
        val alightDayOffset: Int,
        /** asset row whose number the live listing also carries - its times above are the live ones */
        val confirmedByLive: Boolean,
        /** a train only the live source lists - the asset has no route record for it at all */
        val liveOnly: Boolean,
    ) {
        val provenance: Provenance get() = identity.provenance
    }

    data class Merged(
        val rows: List<MergedRow>,
        /** The sentence that goes under the results header. Never null when live could not answer. */
        val stateLine: String,
        val liveListUsed: Boolean,
    )

    fun merge(
        assetRows: List<AssetRow>,
        live: PairAnswer?,
        nowMinuteOfTarget: Long,
    ): Merged {
        val listing: PairListing? = when (live) {
            is PairAnswer.Listing -> live.listing
            else -> null
        }
        val liveNumbers = listing?.numbers()?.toSet() ?: emptySet()
        val liveByNumber = listing?.trains?.associateBy { it.identity.number } ?: emptyMap()

        val rows = ArrayList<MergedRow>(assetRows.size + (listing?.trains?.size ?: 0))
        for (a in assetRows) {
            val liveTwin = liveByNumber[a.trainNo]
            rows.add(
                if (liveTwin != null) {
                    MergedRow(
                        identity = liveTwin.identity,
                        boardMin = liveTwin.boardMin ?: a.boardMinute,
                        alightMin = liveTwin.alightMin ?: a.alightMinute,
                        alightDayOffset = liveTwin.alightDayOffset,
                        confirmedByLive = true,
                        liveOnly = false,
                    )
                } else {
                    MergedRow(
                        identity = TrainIdentity(
                            a.trainNo, a.trainName, a.fromCode, a.toCode, a.provenance,
                        ),
                        boardMin = a.boardMinute,
                        alightMin = a.alightMinute,
                        alightDayOffset = 0,
                        confirmedByLive = false,
                        liveOnly = false,
                    )
                }
            )
        }

        val liveOnlyRows = listing?.trains
            ?.filter { it.identity.number !in assetNumbers(assetRows) }
            ?.sortedWith(compareBy({ it.boardMin ?: Int.MAX_VALUE }, { it.identity.number }))
            ?.map {
                MergedRow(
                    identity = it.identity,
                    boardMin = it.boardMin,
                    alightMin = it.alightMin,
                    alightDayOffset = it.alightDayOffset,
                    confirmedByLive = false,
                    liveOnly = true,
                )
            } ?: emptyList()
        rows.addAll(liveOnlyRows)

        val stateLine = stateLineFor(live, listing?.note, liveOnlyRows.size, nowMinuteOfTarget)
        return Merged(rows, stateLine, listing != null)
    }

    /**
     * The v1 row conversion, pure and tested (the seam that lets the v1 results screen render v2's
     * merge output). v1's convention is CUMULATIVE rail minutes - a day-2 arrival is minute+1440
     * (TrainFinder filters `alight <= board`), so an overnight live row (23:00 -> 08:20) converts to
     * alight 1940, duration 560, and the adapter's "arrives next day" follows. Rows without a readable
     * board time are NOT rendered as midnight (2.4): they are not in the hits, and the count comes
     * back as the second element of the pair so the state line can name it.
     */
    fun toTrainHits(
        rows: List<MergedRow>,
        targetEpochMinute: Long,
        originOf: (boardMin: Int) -> Long,
    ): Pair<List<TrainHit>, Int> {
        val renderable = rows.filter { it.boardMin != null }
        val hits = renderable.map { r ->
            val board = r.boardMin ?: 0
            val alight = when {
                r.alightMin == null -> board
                r.alightDayOffset > 0 && r.alightMin < board -> r.alightMin + 1440
                else -> r.alightMin
            }
            TrainHit(
                trainNo = r.identity.number,
                trainName = r.identity.name,
                trainType = null,
                originDateEpochDay = originOf(board),
                boardMinute = board,
                alightMinute = alight,
                fromCode = r.identity.fromCode,
                toCode = r.identity.toCode,
                fromSeq = 0,
                toSeq = 0,
                // the row renderer hides km when 0 - no fabricated distance (2.4)
                distanceKm = 0,
                runDays = null,
                sourceTag = when {
                    r.liveOnly -> "erail endpoints · route checked on open"
                    r.confirmedByLive -> "via erail"
                    else -> "timetable"
                },
            )
        }
        return hits to (rows.size - renderable.size)
    }

    /**
     * One live-source proof that an offline-only number is the same physical train as a listed one.
     * The 16579 case (the owner's paste of 2026-10-12): erail answers "16579" with the name
     * "SMET INTERCITY" - the same name as 20689 at the same departure minute - i.e. the railway
     * renumbered the train and the offline dump still carries the old number, so the merged list
     * showed one physical train twice at 09:15 while every live app showed it once.
     */
    data class Renumber(val staleNumber: String, val liveNumber: String, val liveName: String)

    /**
     * Drop offline-only rows the live source itself resolves into a live-listed train. Guards:
     * the live twin must still be present (never drop the only copy of a train), and a
     * live-confirmed row is never dropped (its number was answered as itself). Pure: the evidence
     * is the caller's bounded fetching; the decision here is the name equality the SOURCE stated,
     * never a similarity guess.
     */
    fun dedupeRenumbered(rows: List<MergedRow>, evidence: List<Renumber>): Pair<List<MergedRow>, List<Renumber>> {
        if (evidence.isEmpty()) return rows to emptyList()
        val applied = evidence.filter { e ->
            e.staleNumber != e.liveNumber &&
                rows.any { it.liveOnly && it.identity.number == e.liveNumber }
        }
        if (applied.isEmpty()) return rows to emptyList()
        val stale = applied.map { it.staleNumber }.toSet()
        return rows.filter { it.liveOnly || it.confirmedByLive || it.identity.number !in stale } to applied
    }

    /** The sentence under the list: the merge is named, with the source's own answer as the reason. */
    fun renumberNote(applied: List<Renumber>): String = applied.joinToString(" · ") { e ->
        "${e.staleNumber} is the old number of ${e.liveNumber} (${e.liveName}) — the live source " +
            "answers both numbers with one train; the offline row is merged"
    }

    private fun assetNumbers(assetRows: List<AssetRow>): Set<String> =
        assetRows.map { it.trainNo }.toSet()

    /**
     * The exact copy per 8.2 state. Written once, here: a screen that needs different words must
     * change the state, not improvise a sentence.
     */
    fun stateLineFor(live: PairAnswer?, listingNote: String?, liveOnlyCount: Int, nowMinute: Long): String {
        return when (live) {
            is PairAnswer.Listing -> buildString {
                append("live list via ${live.listing.provenance.source}")
                listingNote?.let { append(" · ").append(it) }
                if (liveOnlyCount > 0) append(" · $liveOnlyCount train(s) the offline copy lacks")
            }
            is PairAnswer.AnsweredEmpty ->
                "the live list shows no trains for this pair right now — the timetable rows below " +
                    "are unverified"
            is PairAnswer.CouldNotLook ->
                "no live list reached this device (${live.detail}) — every time below is the timetable"
            is PairAnswer.ProtocolChanged ->
                "the live feed's answer could not be read — its format may have changed; " +
                    "showing the timetable"
            is PairAnswer.DisabledByPolicy ->
                "the live list is disabled by policy — showing the timetable"
            null -> "offline — every time below is the timetable"
        }
    }
}

/**
 * The asset-side row, narrowed to what a pair row needs. Deliberately a v2 type: the v1 query result
 * converts at the edge, and nothing in v2 learns v1's model.
 */
data class AssetRow(
    val trainNo: String,
    val trainName: String,
    val fromCode: String,
    val toCode: String,
    val boardMinute: Int?,
    val alightMinute: Int?,
    val provenance: Provenance,
)
