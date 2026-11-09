package `in`.opentrack.india.ui

import android.content.Intent

/** A user-initiated Android share, NOT a WIMT API or a verified sender. Never promote it to Act.
 * The official WIMT app advertises sharing a location, but its actual share format is not documented.
 * Keep the text on-device and require a real consented fixture before parsing station times. */
object SharedTrainIntake {
    const val MAX_CHARS = 8_192

    data class Preview(val text: String, val linkOnly: Boolean)

    fun preview(action: String?, mime: String?, shared: CharSequence?): Preview? {
        if (action != Intent.ACTION_SEND || mime != "text/plain") return null
        // Reject, don't truncate: a truncated URL or time could look like a complete report.
        val text = shared?.toString()?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_CHARS || text.any { it == '\u0000' }) return null
        val linkOnly = text.matches(Regex("https?://\\S+", RegexOption.IGNORE_CASE))
        return Preview(text, linkOnly)
    }
}
