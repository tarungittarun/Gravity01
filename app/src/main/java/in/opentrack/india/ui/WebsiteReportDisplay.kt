package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus
import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** A parsed past-card timeline is usable in the *selected website's* own view.
 * It is not an independently run-verified NTES actual, and a page clock is not
 * a train event. Only [OrgWebsiteStatus.parse]'s guarded cells may be painted. */
object WebsiteReportDisplay {
    const val STALE_BADGE = "WEBSITE · mapped arrival · stale · run inferred"

    fun usable(state: String?, answered: Boolean, report: OrgWebsiteStatus.Report?): Boolean =
        state == "comparison" && answered && report != null && report.rows.isNotEmpty()

    /** The route table is a history, NOT the site's forecast board. A white card
     * for the following halt is not an arrival, even when prefixed with '~'. The
     * latest past card may also print a departure later than its own page stamp:
     * keep the arrival, but never show that departure before it occurred. Compare
     * against both the page and device clocks, using the selected run's IST day.
     * Fail closed if the site's anchor cannot be located on this route. */
    fun displayedClocks(report: OrgWebsiteStatus.Report?, originDay: Long,
        haltCodes: List<String>, now: Long): Map<String, OrgWebsiteStatus.SiteClocks> {
        if (report == null) return emptyMap()
        val anchor = report.intermediate?.precedingHalt ?: report.anchorCode
        val lastIndex = haltCodes.indexOfFirst { it.equals(anchor, ignoreCase = true) }
        if (lastIndex < 0) return emptyMap()
        val latestPossible = minOf(now, report.pageUpdatedAt)
        fun past(minute: Int?): Int? = minute?.takeIf {
            RailTime.railMinuteToEpochMillis(originDay, it) <= latestPossible
        }
        return report.siteClocks.mapNotNull { (code, clocks) ->
            val index = haltCodes.indexOfFirst { it.equals(code, ignoreCase = true) }
            if (index !in 0..lastIndex || clocks.forecast) return@mapNotNull null
            val arrival = past(clocks.arrivalMin)
            val departure = past(clocks.departureMin)?.takeIf { dep ->
                // Defence against older/injected reports: never display a
                // departure before the SAME card's arrival, even if the parser
                // once retained a raw timetable echo in siteClocks.
                (arrival ?: report.rows[code]?.actualArrivalMin)?.let { dep >= it } ?: true
            }
            if (arrival == null && departure == null) null
            else code to clocks.copy(arrivalMin = arrival, departureMin = departure)
        }.toMap()
    }

    fun rows(model: TrackModel.View, report: OrgWebsiteStatus.Report): TrackModel.View =
        model.copy(rows = model.rows.map { row ->
            if (row.code in report.rows && row.source == TrackModel.MinuteSource.REPORTED)
                row.copy(source = TrackModel.MinuteSource.SITE_REPORTED)
            else row
        })

    fun selected(source: String?): Boolean = source == PublicPageChecks.ORG
}
