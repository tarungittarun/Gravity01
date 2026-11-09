package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** The short failure display names a failed CONNECTION, never an unchanged stop. */
object RefreshFlash {
    /** A failed first-load website request can leave NTES displayed. Name the failed
     * preferred source in the warning instead of falsely calling the NTES answer a 403. */
    fun preferredWebsiteError(displayed: String?, manualTarget: String?,
                              websiteState: Pair<String, String>?, websiteAnswered: Boolean?): String? =
        if (displayed == TrackingSources.OFFICIAL && manualTarget == PublicPageChecks.ORG)
            connectionError(PublicPageChecks.ORG, websiteState, null, websiteAnswered)
        else null

    fun connectionError(source: String, state: Pair<String, String>?,
                        official: OfficialRequestReceipt?, websiteAnswered: Boolean?): String? {
        val detail = state?.second.orEmpty()
        if (source == PublicPageChecks.ORG && websiteAnswered == true) return null
        val reason = detail + " " + official?.reason.orEmpty()
        if (source == PublicPageChecks.ORG) {
            if (detail.startsWith("Website request paused")) return "Website request paused"
            // A refused request is not the same as a rate limit. Do not guess the reason
            // for the refusal, but make the HTTP outcome visible on the phone itself.
            if (detail.contains("HTTP 403", true)) return "Website refused request (HTTP 403)"
            if (detail.contains("HTTP 429", true)) return "Website rate limited (HTTP 429)"
        }
        if (source == TrackingSources.OFFICIAL &&
            official?.kind == OfficialRequestReceipt.Kind.REQUEST_FAILED) return when {
            reason.contains("HTTP 403", true) || reason.contains("HTTP 429", true) -> "Source blocked request"
            reason.contains("timed out", true) || reason.contains("timeout", true) -> "Source timed out"
            else -> "Connection failed"
        }
        return when {
            detail.contains("HTTP 403", true) || detail.contains("HTTP 429", true) -> "Source blocked request"
            detail.contains("timed out", true) || detail.contains("timeout", true) -> "Source timed out"
            detail.contains("Request failed", true) || detail.contains("could not connect", true) ||
                detail.contains("did not answer", true) -> "Connection failed"
            else -> null
        }
    }
}
