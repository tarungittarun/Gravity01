package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import java.util.Locale

/** A short request receipt. Checked time comes from the device; freshness comes
 * only from a separately timed station event. Neither is a current position. */
object ResponseScore {
    fun durationMillis(sourceDetail: String?): Long? =
        Regex("checked in (\\d+) ms").find(sourceDetail.orEmpty())
            ?.groupValues?.get(1)?.toLongOrNull()

    fun text(elapsedMs: Long, eventAtMillis: Long?, checkedAtMillis: Long): String =
        "Took: ${String.format(Locale.ROOT, "%.1f", elapsedMs.coerceAtLeast(0) / 1000.0)} sec\n" +
            "Freshness: ${SourceFreshness.label(eventAtMillis, checkedAtMillis)}\n" +
            "Checked: ${RailTime.formatClockSeconds(checkedAtMillis)}"
}
