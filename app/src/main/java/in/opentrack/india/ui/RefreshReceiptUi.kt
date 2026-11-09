package `in`.opentrack.india.ui

/** A one-line check receipt. Full evidence stays in TalkBack, Info and the diary.
 * Never use the page-check clock to redate a station event. */
object RefreshReceiptUi {
    data class Overlay(val took: String, val checked: String, val freshness: String)

    /** Three transient rows, over the card rather than in its measured contents.
     * The check clock never pretends to be a station event clock. */
    fun overlay(source: String, checked: String, tookMillis: Long, age: String,
                change: RefreshEvidence.Change, detail: String, cached: Boolean): Overlay = Overlay(
        took = "Took ${java.lang.String.format(java.util.Locale.ROOT, "%.1f", tookMillis.coerceAtLeast(0) / 1000.0)} s",
        checked = short(source, checked, change, detail, cached),
        freshness = "Freshness · $age",
    )

    fun short(source: String, checked: String, change: RefreshEvidence.Change,
              detail: String, cached: Boolean): String {
        val result = when {
            cached -> "cached reply · no new event"
            detail.contains("failed", true) || detail.contains("no usable", true) -> "check failed · last report retained"
            detail.contains("no validated", true) -> "no validated stop"
            detail.contains("site clocks changed", true) -> "site times revised"
            change == RefreshEvidence.Change.NEW_STOP -> "new stop reported"
            change == RefreshEvidence.Change.REVISED_AT_SAME_STOP -> "stop time revised"
            source == "website" -> "no newer qualified stop"
            else -> "no newer stop"
        }
        return "Checked $checked · $source · $result"
    }
}
