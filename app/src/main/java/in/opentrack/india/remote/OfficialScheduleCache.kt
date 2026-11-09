package `in`.opentrack.india.remote

import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONArray
import org.json.JSONObject

/** A previously run/day/route-checked static timetable (NTES or ConfirmTkt), never actuals.
 * Callers must keep sources in SEPARATE preference namespaces and revalidate the selected span.
 * Passing points retain their unknown-halt status and unpublished times on reopen. */
object OfficialScheduleCache {
    const val MAX_AGE_MS = 24 * 60 * 60_000L

    fun encode(route: TrainRoute, day: Long, savedAt: Long): String = JSONObject().apply {
        put("schema", 1); put("train", route.trainNo); put("day", day); put("saved", savedAt)
        put("name", route.trainName)
        put("stops", JSONArray().apply {
            for (s in route.stops) put(JSONObject().apply {
                put("code", s.code); put("name", s.name); put("km", s.schedKm)
                put("halt", s.isHalt)
                s.arrivalMin?.let { put("arr", it) }
                s.departureMin?.let { put("dep", it) }
            })
        })
    }.toString()

    fun decode(raw: String?, train: String, day: Long, from: String, to: String,
               now: Long): TrainRoute? = runCatching {
        val root = JSONObject(raw ?: return null)
        if (root.optInt("schema") != 1 || root.optString("train") != train ||
            root.optLong("day", -1) != day || train.length !in 4..6) return null
        val saved = root.optLong("saved", 0L)
        if (saved <= 0 || now - saved !in 0L..MAX_AGE_MS) return null
        val array = root.optJSONArray("stops") ?: return null
        if (array.length() !in 3..1000) return null
        val stops = (0 until array.length()).map { i ->
            val obj = array.optJSONObject(i) ?: return null
            val code = obj.optString("code").uppercase()
            if (!code.matches(Regex("[A-Z0-9]{1,6}"))) return null
            val arr = obj.optInt("arr").takeIf { obj.has("arr") && it >= 0 }
            val dep = obj.optInt("dep").takeIf { obj.has("dep") && it >= 0 }
            StopRow(i + 1, code, obj.optString("name").ifBlank { code }, null,
                null, null, arr, dep, ((dep ?: arr ?: 0) / 1440),
                obj.optInt("km", -1), obj.optString("halt", "true") != "false")
        }
        val codes = stops.map { it.code }
        if (codes.distinct().size != codes.size || stops.first().departureMin == null ||
            (stops.last().arrivalMin == null && stops.last().departureMin == null) || stops.count {
                it.arrivalMin != null || it.departureMin != null } < 3) return null
        val first = codes.indexOf(from.uppercase())
        if (first < 0 || codes.indexOf(to.uppercase()) <= first) return null
        val segments = stops.zipWithNext { a, b -> SegmentRow(a.seq, a.code, b.code,
            if (a.schedKm >= 0 && b.schedKm >= a.schedKm) b.schedKm - a.schedKm else 0,
            a.departureMin?.let { d -> b.arrivalMin?.let { (it - d).coerceAtLeast(0) } }) }
        TrainRoute(train, root.optString("name").ifBlank { "Train $train" },
            stops.first().code, stops.first().name, stops.last().code, stops.last().name,
            stops.last().schedKm.coerceAtLeast(0),
            (stops.last().arrivalMin ?: stops.last().departureMin)?.let {
                it - stops.first().departureMin!! },
            null, null, stops, segments)
    }.getOrNull()
}
