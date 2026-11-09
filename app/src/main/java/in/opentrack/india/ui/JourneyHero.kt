package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel

/** A short, truthful first glance. The source's full sentence stays in the
 * accessible description and Info; a scheduled next minute is never an ETA. */
object JourneyHero {
    data class Official(val title: String, val next: String)

    fun official(rows: List<TrackModel.Stop>, anchorCode: String?): Official? {
        // The official feed can have actual stop minutes without a separately
        // selected mirror anchor. The final timed row is still the last report;
        // a null UI anchor must not force the long fallback sentence onto phones.
        val index = rows.indexOfLast { it.source == TrackModel.MinuteSource.REPORTED &&
            (it.showArr != null || it.showDep != null) &&
            (anchorCode == null || it.code.equals(anchorCode, true)) }
            .takeIf { it >= 0 } ?: rows.indexOfLast {
                it.source == TrackModel.MinuteSource.REPORTED &&
                    (it.showArr != null || it.showDep != null)
            }
        if (index < 0) return null
        val anchor = rows[index]
        if (anchor.showDep == null && anchor.showArr == null) return null
        val title = (if (anchor.showDep != null) "Departed " else "Arrived at ") +
            RowText.of(anchor.name)
        val next = rows.getOrNull(index + 1)?.let { stop ->
            buildString {
                append(RowText.of(stop.name))
                (stop.schedArr ?: stop.schedDep)?.let {
                    append(" · ").append(RailTime.formatRailMinute(it))
                }
                append("\nTimetable")
            }
        } ?: "End of route · no next stop"
        return Official(title, next)
    }
}
