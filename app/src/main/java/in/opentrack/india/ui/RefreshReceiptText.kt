package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** Checked-at clock and event clock are separate. No page-update stamp can be a new stop. */
object RefreshReceiptText {
    fun format(source: String, sourceLabel: String, activeLabel: String,
        checked: String, age: String, status: Pair<String, String>?,
        official: OfficialRequestReceipt?, websiteAnswered: Boolean?,
        cachedBody: Boolean, change: RefreshEvidence.Change, switched: Boolean,
        websiteClocksChanged: Boolean = false,
    ): String {
        val context = if (switched) "showing $activeLabel · $age" else "keeping last data · $age"
        if (source == "NTES (official)") {
            return when (official?.kind) {
                OfficialRequestReceipt.Kind.REQUEST_FAILED ->
                    "Checked $checked · NTES request failed" +
                        official.reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty() +
                        " · $context"
                OfficialRequestReceipt.Kind.ANSWERED_UNUSABLE ->
                    "Checked $checked · NTES answered, no validated new stop · $context"
                OfficialRequestReceipt.Kind.NOT_ASKED ->
                    "Checked $checked · NTES not reached in this request · $context"
                OfficialRequestReceipt.Kind.ANSWERED_WITH_ACTUALS -> {
                    val finding = when (change) {
                        RefreshEvidence.Change.NEW_STOP -> "new reported stop"
                        RefreshEvidence.Change.REVISED_AT_SAME_STOP -> "revised time at same stop"
                        RefreshEvidence.Change.UNCHANGED -> "same last reported stop, no newer event"
                    }
                    "NTES answered at $checked · $finding · $age"
                }
                null -> "Checked $checked · NTES outcome unknown · $context"
            }
        }
        if (cachedBody) return "Checked $checked · $sourceLabel request timed out; " +
            "cached HTTP body is not a new reply · $context"
        val detail = status?.second.orEmpty()
        if (source == PublicPageChecks.ORG && detail.startsWith("Website request paused"))
            return "At $checked · website not asked: $detail · $context"
        if (detail.contains("Request failed:", true) || detail.contains("timed out", true) ||
            detail.contains("could not connect", true) || detail.contains("did not answer", true)) {
            val short = detail.substringAfter("Request failed:", detail).substringBefore(';')
                .trim().take(95)
            return "Checked $checked · $sourceLabel request failed: $short · $context"
        }
        if (source == PublicPageChecks.ORG) {
            if (websiteAnswered == true) {
                if (status?.first != "comparison")
                    return "Website answered at $checked · no validated site stop report " +
                        "for this run · $context"
                val finding = when (change) {
                    RefreshEvidence.Change.NEW_STOP -> "new site-reported stop"
                    RefreshEvidence.Change.REVISED_AT_SAME_STOP -> "revised site stop time"
                    RefreshEvidence.Change.UNCHANGED -> if (websiteClocksChanged)
                        "site clocks changed; no newer mapped arrival" else
                        "same last qualifying site stop, no newer event"
                }
                val omission = if (change == RefreshEvidence.Change.UNCHANGED &&
                    detail.contains("Unqualified later card:"))
                    " · ${detail.substringAfter("Unqualified later card:").substringBefore(" · ").trim()}" else ""
                return "Website answered at $checked · $finding$omission · $age"
            }
        }
        if (switched) return "Checked $checked · $sourceLabel has no usable report; $context"
        if (status?.first !in listOf("live", "candidate"))
            return "Checked $checked · no usable new report from $sourceLabel · $age"
        val finding = when (change) {
            RefreshEvidence.Change.NEW_STOP -> "New stop report"
            RefreshEvidence.Change.REVISED_AT_SAME_STOP -> "Revised time at same stop"
            RefreshEvidence.Change.UNCHANGED -> "No newer stop"
        }
        return "$finding · $sourceLabel · checked $checked · $age"
    }
}
