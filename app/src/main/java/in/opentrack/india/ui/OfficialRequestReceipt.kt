package `in`.opentrack.india.ui

/** Outcome of THIS official request, not the provenance of a saved answer.
 * A cached snapshot may still have correct old Act minutes when the socket fails. */
data class OfficialRequestReceipt(val kind: Kind, val reason: String = "") {
    enum class Kind { ANSWERED_WITH_ACTUALS, ANSWERED_UNUSABLE, REQUEST_FAILED, NOT_ASKED }

    companion object {
        fun from(tried: List<String>, notes: List<String>): OfficialRequestReceipt {
            if ("PROVIDER_NTES=hit" in tried) return OfficialRequestReceipt(Kind.ANSWERED_WITH_ACTUALS)
            if (tried.none { it.startsWith("PROVIDER_NTES=") }) return OfficialRequestReceipt(Kind.NOT_ASKED)
            val reason = notes.firstOrNull { it.startsWith("LIVE · running status:") }
                ?.substringAfter(':')?.trim().orEmpty()
            val failed = listOf("did not answer", "no network route", "could not open a connection",
                "refused the request", "request was sent").any { reason.contains(it, ignoreCase = true) }
            return OfficialRequestReceipt(if (failed) Kind.REQUEST_FAILED else Kind.ANSWERED_UNUSABLE, reason)
        }
    }
}
