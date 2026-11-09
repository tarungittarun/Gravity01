package `in`.opentrack.india.ui

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The feed's data stamp is not a download timestamp or a train-position update. */
object SearchResultSummary {
    private val stamp = Regex("""erail data stamp (\d{4}-\d{1,2}-\d{1,2}-\d{1,2}-\d{1,2}-\d{1,2})""")

    fun line(count: Int, note: String?, tags: List<String?> = emptyList()): String {
        val countText = "$count ${if (count == 1) "train" else "trains"}"
        if (note?.startsWith("live list via erail") != true) {
            return "$countText · on-device timetable"
        }
        val withTimetable = tags.isEmpty() || tags.any { it == null || it.contains("timetable", true) }
        val source = (if (note.contains("cached", true) || note.contains("served from cache", true))
            "cached erail" else "erail") + if (withTimetable) " + timetable" else ""
        val date = stamp.find(note)?.groupValues?.get(1)?.let(::sourceDate)
        return "$countText · $source" + (date?.let { " · source data as of $it" }
            ?: " · source date not supplied")
    }

    private fun sourceDate(raw: String): String? = runCatching {
        val parser = SimpleDateFormat("yyyy-M-d-H-m-s", Locale.ROOT).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        val parsed = parser.parse(raw) ?: return@runCatching null
        val formatter = SimpleDateFormat("d MMM yyyy, HH:mm 'IST'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }
        formatter.format(parsed)
    }.getOrNull()
}
