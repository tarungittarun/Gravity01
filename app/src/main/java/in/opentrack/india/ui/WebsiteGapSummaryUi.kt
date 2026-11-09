package `in`.opentrack.india.ui

/** Short first-glance copy only. The full website update label and comparison
 * qualifications remain in source details and the diary. */
object WebsiteGapSummaryUi {
    private val missingDestination = Regex(
        "website has no ([A-Z0-9]+) arrival row\\. Last displayed: ([A-Z0-9]+)",
    )

    fun concise(full: String): String {
        val match = missingDestination.find(full) ?: return full
        return "No ${match.groupValues[1]} site arrival · last listed " +
            "${match.groupValues[2]} (comparison). Position unknown."
    }
}
