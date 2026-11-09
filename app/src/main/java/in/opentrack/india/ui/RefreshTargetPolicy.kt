package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** Displayed fallback is not a new source choice. A deliberate Refresh retries the traveller's
 * selected source before NTES; the opt-in timer stays on the displayed fallback after failure,
 * rather than sending a denied source another request every minute. Choosing NTES explicitly
 * changes the saved preference and makes NTES first again. */
object RefreshTargetPolicy {
    /** The initial background survey must not ask the source for an NTES view. */
    fun surveyWebsite(active: String?, alreadyChecked: Boolean): Boolean =
        (active == TrackingSources.WIMT || active == PublicPageChecks.ORG) && !alreadyChecked

    /** A first-load refusal must not leave an already answered official source hidden
     * behind a blank table. This changes only the displayed source, never the saved
     * preference, so the next deliberate Refresh still asks the preferred source first. */
    fun afterWebsiteSurvey(displayed: String?, siteReportAvailable: Boolean,
                           officialActualsAvailable: Boolean): String? =
        if ((displayed == TrackingSources.WIMT || displayed == PublicPageChecks.ORG) &&
            !siteReportAvailable && officialActualsAvailable)
            TrackingSources.OFFICIAL else displayed

    fun target(displayed: String?, preferred: String?, auto: Boolean): String {
        val active = displayed ?: TrackingSources.OFFICIAL
        val pref = TrackingSources.valid(preferred)
        return if (!auto && active == TrackingSources.OFFICIAL &&
            (pref == TrackingSources.WIMT || pref == PublicPageChecks.ORG)) pref
        else active
    }
}
