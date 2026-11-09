package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.TrainHit

/**
 * The frontend, captured by the app itself - the owner's ask of 2026-10-12 ("capture frontend data
 * as well with elements or screenshot. I cannot take screenshot again and again"): every screen
 * states what it is showing into the same diary the Save button writes, so one paste carries the
 * screens - headline, chips, the rows and their labels - with zero screenshots taken by hand.
 * Pure text builders; the Activities only call these, and [UiShot] is the picture half.
 */
object UiState {

    private const val MAX_ROWS = 8

    /** The tracking screen's visible state: headline, provenance chips, and the table's rows. */
    internal fun track(
        trainNo: String,
        headline: String,
        provenance: String,
        age: String,
        delay: String,
        next: String,
        progress: String,
        note: String?,
        rows: List<TrackModel.Stop>,
    ): String {
        val rowText = rows.take(MAX_ROWS).joinToString(" ; ") { r ->
            buildString {
                append(r.code)
                append(" arr ").append(r.showArr?.let { RailTime.formatRailMinute(it) } ?: "–")
                append(" dep ").append(r.showDep?.let { RailTime.formatRailMinute(it) } ?: "–")
                if (r.isBoarding) append(" [board]")
                if (r.isAlighting) append(" [alight]")
                append(" [").append(when (r.source) {
                    TrackModel.MinuteSource.SITE_REPORTED -> "Web"
                    TrackModel.MinuteSource.REPORTED -> "Act"
                    TrackModel.MinuteSource.PROJECTED -> "Est"
                    TrackModel.MinuteSource.SCHEDULE -> "Sch"
                }).append(']')
            }
        }
        return buildString {
            append("screen=track train=").append(trainNo)
            append(" | headline=\"").append(headline).append('"')
            append(" | chip=\"").append(provenance).append(" · ").append(age).append('"')
            append(" | delay=\"").append(delay).append('"')
            append(" | next=\"").append(next).append('"')
            append(" | progress=\"").append(progress).append('"')
            if (!note.isNullOrBlank()) append(" | note=\"").append(note).append('"')
            append(" | rows=").append(rowText)
            if (rows.size > MAX_ROWS) append(" ; (+").append(rows.size - MAX_ROWS).append(" more halts)")
            // The relevant website claims are usually near the END of an overnight run;
            // the usual first-eight-row diary preview cannot reveal them. Keep a bounded
            // source-specific tail so one user paste compares the site's times to our UI.
            val website = rows.filter { it.source == TrackModel.MinuteSource.SITE_REPORTED }.takeLast(4)
            if (website.isNotEmpty()) append(" | website-published=").append(website.joinToString(" ; ") {
                it.code + " Web arr " + (it.showArr?.let(RailTime::formatRailMinute) ?: "–") +
                    " dep " + (it.showDep?.let(RailTime::formatRailMinute) ?: "–") +
                    " pf " + (it.platform ?: "–")
            })
        }
    }

    /** The finder screen's visible state: query, train count, live-state line, rows with labels. */
    internal fun list(
        fromCode: String,
        toCode: String,
        count: Int,
        stateLine: String?,
        hits: List<TrainHit>,
    ): String {
        val rowText = hits.take(MAX_ROWS).joinToString(" ; ") { h ->
            h.trainNo + " " + h.trainName + " " + RailTime.formatRailMinute(h.boardMinute) + "→" +
                RailTime.formatRailMinute(h.alightMinute) + " " + h.sourceTag.orEmpty()
        }
        return buildString {
            append("screen=list ").append(fromCode).append("→").append(toCode)
            append(" · ").append(count).append(" train(s)")
            stateLine?.let { append(" | ").append(it) }
            append(" | rows=").append(rowText)
            if (hits.size > MAX_ROWS) append(" ; (+").append(hits.size - MAX_ROWS).append(" more)")
        }
    }
}
