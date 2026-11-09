package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel

/** A marker at a REPORTED HALT, never an interpolated current position. A website card is a
 * source-attributed claim (amber), not a verified actual (blue). No timetable row earns a train. */
object TableMarker {
    data class Halt(val code: String, val caption: String, val website: Boolean)

    /** Passage age, never HTTP/page-update age; the clock can advance without movement. */
    fun elapsedLabel(ageMillis: Long): String = when {
        ageMillis < 60_000L -> "under 1 min"
        ageMillis < 3_600_000L -> "${ageMillis / 60_000L} min"
        else -> "${ageMillis / 3_600_000L} h " +
            "${(ageMillis % 3_600_000L) / 60_000L} min"
    }

    fun atHalt(rows: List<TrackModel.Stop>, anchorCode: String?, originDay: Long,
               nowMillis: Long): Halt? {
        val row = rows.firstOrNull { it.code.equals(anchorCode, true) } ?: return null
        val website = row.source == TrackModel.MinuteSource.SITE_REPORTED
        if (!website && row.source != TrackModel.MinuteSource.REPORTED) return null
        val minute = row.showDep ?: row.showArr
        // Feed age is NOT passage age. If no reported minute exists, print no age at the marker.
        val age = minute?.let { nowMillis - RailTime.railMinuteToEpochMillis(originDay, it) }
            ?.takeIf { it >= 0 }
        val ageText = if (age == null || age < 60_000L) "" else
            " · ${elapsedLabel(age).replace(" min", "m").replace(" h ", "h ")} ago"
        return Halt(row.code, (if (website) "Site's last mapped arrival" else "Last reported stop") + ageText,
            website)
    }
}
