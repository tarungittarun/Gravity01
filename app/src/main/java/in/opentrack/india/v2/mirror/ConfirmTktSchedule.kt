package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONArray

/** ConfirmTkt's embedded Schedule (not the running-status clock table) contains ordered halts
 * and each halt's intermediateStations. This is static route evidence ONLY; the page's visible
 * "Delay by" rows include future projections and must never be converted to actuals here. */
object ConfirmTktSchedule {
    data class Result(val halts: TrainRoute, val allStations: TrainRoute)

    private val code = Regex("[A-Z0-9]{1,6}")
    private val clock = Regex("(\\d{1,2}):(\\d{2})")

    /** A small bracket scanner for a JSON array embedded in otherwise unrelated HTML/JavaScript.
     * Never fall back to scraping numbers from the rest of the page on a malformed array. */
    private fun scheduleArray(html: String): JSONArray? {
        val marker = Regex("\"Schedule\"\\s*:\\s*\\[").find(html) ?: return null
        val from = marker.range.last
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in from until minOf(html.length, from + 200_000)) {
            val c = html[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return runCatching {
                        JSONArray(html.substring(from, i + 1))
                    }.getOrNull()
                }
            }
        }
        return null
    }

    /** expectedDate is the run's dd-MMM-yyyy in IST, not the device's current date. */
    fun parse(html: String, trainNo: String, expectedDate: String,
              fromCode: String, toCode: String): Result? {
        if (!trainNo.matches(Regex("\\d{4,6}")) || html.length > 400_000 ||
            !expectedDate.matches(Regex("\\d{2}-[A-Za-z]{3}-\\d{4}"))) return null
        // A date on the running-status page is not itself proof of actual minutes. It only
        // prevents today's static route from being presented for a different selected run.
        val visible = RunningStatusMirror.stripTags(html)
        val pageDay = ConfirmTktMirror.pageDayOf(visible)
        val updateYear = Regex("Last Updated:\\s*\\d{1,2}\\s+[A-Za-z]{3}\\s+(\\d{4})",
            RegexOption.IGNORE_CASE).find(visible)?.groupValues?.get(1)
        if (!expectedDate.substringBeforeLast('-').equals(pageDay, true) ||
            updateYear != expectedDate.takeLast(4)) return null
        val array = scheduleArray(html) ?: return null
        if (array.length() !in 3..500) return null
        val halts = ArrayList<StopRow>(array.length())
        val all = ArrayList<StopRow>()
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: return null
            val no = row.optString("TrainNo").trim()
            if (no != trainNo) return null
            val station = row.optString("StationCode").trim().uppercase()
            if (!code.matches(station) || !seen.add(station)) return null
            val name = row.optString("StationName").trim().ifBlank { station }
            val day = row.optInt("Day", 1)
            if (day !in 1..4) return null
            fun minute(key: String): Int? {
                val m = clock.matchEntire(row.optString(key).trim()) ?: return null
                val hour = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
                return if (hour < 24 && min < 60) (day - 1) * 1440 + hour * 60 + min else null
            }
            val arr = minute("ArrivalTime")
            val dep = minute("DepartureTime")
            if (arr == null && dep == null) return null
            val km = row.optString("Distance").toDoubleOrNull()?.takeIf { it >= 0.0 }
                ?.toInt() ?: -1
            val halt = StopRow(i + 1, station, name, null, null, null, arr, dep,
                day - 1, km, true)
            halts += halt
            all += halt.copy(seq = all.size + 1)
            val points = row.optJSONArray("intermediateStations") ?: JSONArray()
            if (points.length() > 100) return null
            for (j in 0 until points.length()) {
                val p = points.optJSONObject(j) ?: return null
                val c = p.optString("StationCode").trim().uppercase()
                if (!code.matches(c) || !seen.add(c)) return null
                val pointKm = p.optString("Distance").toDoubleOrNull()
                    ?.takeIf { it >= 0.0 }?.toInt() ?: -1
                all += StopRow(all.size + 1, c,
                    p.optString("StationName").trim().ifBlank { c }, null, null, null,
                    null, null, day - 1, pointKm, false)
            }
        }
        // A route not covering the user's selected corridor cannot be attached to their train.
        val codes = all.map { it.code }
        val from = codes.indexOf(fromCode.uppercase())
        if (from < 0 || codes.indexOf(toCode.uppercase()) <= from) return null
        if (halts.first().departureMin == null || halts.last().arrivalMin == null ||
            halts.zipWithNext().any { (a, b) ->
                (a.departureMin ?: a.arrivalMin ?: 0) > (b.arrivalMin ?: b.departureMin ?: 0) + 10
            }) return null
        fun route(stops: List<StopRow>): TrainRoute = TrainRoute(
            trainNo, "Train $trainNo", halts.first().code, halts.first().name,
            halts.last().code, halts.last().name, halts.last().schedKm.coerceAtLeast(0),
            halts.last().arrivalMin?.let { it - (halts.first().departureMin ?: 0) },
            null, null, stops, stops.zipWithNext { a, b ->
                SegmentRow(a.seq, a.code, b.code,
                    if (a.schedKm >= 0 && b.schedKm >= a.schedKm) b.schedKm - a.schedKm else 0,
                    a.departureMin?.let { dep -> b.arrivalMin?.let { (it - dep).coerceAtLeast(0) } })
            },
        )
        return Result(route(halts), route(all))
    }
}
