package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus

/** Website's last-card departure is a revisable suggestion, NEVER an Act cell.
 * Require a separate non-schedule departure, a prior arrival, two full minutes
 * since the suggested departure, and a recent page for the selected origin run.
 * The page's clock establishes publication, not motion. */
object DepartureCue {
    /** Show the page's revisable departure beside the station immediately, but
     * outside ACTUAL. This is neither a departure event nor permission to move the marker. */
    fun websitePending(report: OrgWebsiteStatus.Report?, now: Long): Int? {
        if (report == null || report.intermediate != null ||
            now - report.pageUpdatedAt !in 0L..300_000L) return null
        val departure = report.provisionalDeparture ?: return null
        val arrival = report.rows[report.anchorCode]?.actualArrivalMin ?: return null
        return departure.takeIf { it > arrival }
    }

    fun websiteSuggestion(report: OrgWebsiteStatus.Report?, originDay: Long,
                          now: Long): Int? {
        val departure = websitePending(report, now) ?: return null
        // Only a later website publication that retains this earlier minute can
        // yield even an unconfirmed outbound cue. Ageing the phone clock is not enough.
        report ?: return null
        val stamp = RailTime.railMinuteToEpochMillis(originDay, departure)
        if (report.pageUpdatedAt - stamp < 120_000L || now - stamp !in 0L..600_000L)
            return null
        return departure
    }

    fun between(from: String, firstMapped: String?, nextHalt: String): String =
        "Between ${RowText.of(from)} and ${RowText.of(firstMapped ?: nextHalt)}"

    fun isCue(kind: `in`.opentrack.india.domain.RouteBetween.MarkerKind?): Boolean =
        kind == `in`.opentrack.india.domain.RouteBetween.MarkerKind.REPORTED_DEPARTURE_CUE ||
            kind == `in`.opentrack.india.domain.RouteBetween.MarkerKind.SITE_DEPARTURE_SUGGESTION
}
