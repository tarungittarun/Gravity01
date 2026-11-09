package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus

/** Supplemental *website* past-card clocks for a covered halt where the selected
 * NTES view has no accepted actual. Never write these into the Act column, the
 * official snapshot or the marker. The website does not state the run date. */
internal object WebsiteGapUi {
    fun caption(stop: TrackModel.Stop, site: OrgWebsiteStatus.SiteClocks?,
                saved: Boolean = false): String? {
        if (site == null || site.forecast ||
            !(stop.state == TrackModel.State.PASSED || stop.isAnchor)) return null
        val official = stop.source == TrackModel.MinuteSource.REPORTED
        val arr = site.arrivalMin.takeUnless { official && stop.showArr != null }
        val dep = site.departureMin.takeUnless { official && stop.showDep != null }
        if (arr == null && dep == null) return null
        val day = RouteClockUi.departureDay(dep, arr)
        return buildString {
            append(if (saved) "Web past (saved) · " else "Web past · ")
            if (arr != null) append("arr ").append(RailTime.formatRailMinuteBare(arr))
            if (dep != null) {
                if (arr != null) append(" · ")
                append("dep ").append(RailTime.formatRailMinuteBare(dep))
            }
            if (day != null) append(" · ").append(day)
        }
    }
}
