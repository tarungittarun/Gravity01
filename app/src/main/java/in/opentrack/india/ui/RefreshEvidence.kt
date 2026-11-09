package `in`.opentrack.india.ui

/** A download, a revised minute at the same station, and a new station are
 * different observations. Keys deliberately omit page/update/fetch clocks. */
object RefreshEvidence {
    enum class Change { UNCHANGED, REVISED_AT_SAME_STOP, NEW_STOP }

    fun compare(previous: String?, current: String?): Change = when {
        current == null || current == previous -> Change.UNCHANGED
        previous != null && current.substringBefore(':') == previous.substringBefore(':') ->
            Change.REVISED_AT_SAME_STOP
        else -> Change.NEW_STOP
    }
}
