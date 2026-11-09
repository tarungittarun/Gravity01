package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.ConfirmTktMirror
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.RunningStatusMirror
import `in`.opentrack.india.v2.mirror.WimtCandidate

/** An HTTP 200 alone is not a reported stop. NTES and dated witnesses may supply
 * independently run-bound primary actuals. A selected undated website may separately
 * paint its parser-qualified past cards as SITE_REPORTED with visible provenance,
 * but must never inherit NTES actuals or claim an independently verified GPS fix. */
object SourceEvidencePolicy {
    fun comparisonOnly(source: String?): Boolean =
        source in PublicPageChecks.names || source == WimtCandidate.SOURCE

    fun primaryActuals(source: String?, runIdentified: Boolean, verifiedStopMinutes: Boolean): Boolean =
        runIdentified && verifiedStopMinutes && source in setOf(
            "NTES (official)", "erail", RunningStatusMirror.SOURCE,
            ConfirmTktMirror.SOURCE, "last known",
        )
}
