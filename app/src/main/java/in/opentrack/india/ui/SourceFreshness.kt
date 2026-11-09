package `in`.opentrack.india.ui

/** Age the last *reported station event*, never an HTTP reply or a page-update clock.
 * An undated or future station minute is unknown rather than recent. */
object SourceFreshness {
    const val STALE_AFTER_MS = 25 * 60_000L

    fun ageMillis(eventAtMillis: Long?, nowMillis: Long): Long? =
        eventAtMillis?.takeIf { it > 0 && it <= nowMillis }?.let { nowMillis - it }

    fun isStale(eventAtMillis: Long?, nowMillis: Long): Boolean =
        ageMillis(eventAtMillis, nowMillis)?.let { it >= STALE_AFTER_MS } == true

    fun label(eventAtMillis: Long?, nowMillis: Long): String {
        val age = ageMillis(eventAtMillis, nowMillis) ?: return "unknown"
        return when {
            age < 60_000L -> "<1m old"
            age < 3_600_000L -> "${age / 60_000L}m old"
            age < 86_400_000L -> "${age / 3_600_000L}h ${age % 3_600_000L / 60_000L}m old"
            else -> "${age / 86_400_000L}d old"
        }
    }
}
