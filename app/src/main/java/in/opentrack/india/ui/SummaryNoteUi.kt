package `in`.opentrack.india.ui

/** Do not repeat routine website qualifications as a large painted paragraph.
 * Badge, source name, event age and source details already disclose them. Keep
 * exceptional warnings, and retain the full model note in the copied report. */
object SummaryNoteUi {
    fun visible(note: String?, websiteGapShown: Boolean = false): String? = note?.takeUnless {
        websiteGapShown && it == "No confirmed times from this site; timetable shown." ||
        it.startsWith("Website past-card times shown in the station table;") ||
            it.startsWith("Website card clocks shown in the WEBSITE column;") ||
            it.startsWith("Past website card clocks shown;")
    }
}
