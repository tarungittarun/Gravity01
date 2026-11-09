package `in`.opentrack.india.remote

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject

/** Historical infrastructure, NOT a dated timetable or evidence of train movement.
 * The old public timetable numbered 16227 ran SBC->SMET, not today's MYS->TLGP.
 * The bundled corridor joins the old 16221 MYS->SBC, 16227 SBC->SMET and 16206
 * SMET->TLGP. Each leg must independently fit two consecutive halts in today's
 * timetable; unknown/divergent legs remain unmapped. No old clock or halt flag is used. */
object OfflineCorridorMap {
    const val ASSET = "corridors/16227.json"
    const val SOURCE = "Offline historical corridor · archive commit 2026-06-20; timetable age unknown"
    data class Result(val route: TrainRoute, val mappedLegStarts: Set<String>)
    private data class Point(val code: String, val name: String, val km: Int)

    fun parse(json: String, official: TrainRoute): Result? {
        if (official.trainNo != "16227" || !official.srcCode.equals("MYS", true) ||
            !official.dstCode.equals("TLGP", true) || official.stops.size < 3) return null
        val doc = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (doc.optString("train") != official.trainNo ||
            doc.optString("kind") != "historical corridor, not current schedule or location" ||
            doc.optString("source_vintage") != "2026-06-20") return null
        val arr = doc.optJSONArray("points") ?: return null
        if (arr.length() < 30 || arr.length() > 200) return null
        val points = (0 until arr.length()).map { i ->
            val row = arr.optJSONArray(i) ?: return null
            Point(row.optString(0).trim().uppercase(), row.optString(1).trim(), row.optInt(2, -1))
        }
        if (points.first().code != "MYS" || points.first().km != 0 ||
            points.last().code != "TLGP" || points.any { it.name.isEmpty() || it.km < 0 } ||
            points.map { it.code }.distinct().size != points.size ||
            points.zipWithNext().any { (a, b) -> b.km <= a.km }) return null
        val byCode = points.withIndex().associate { it.value.code to it.index }
        val officialCodes = official.stops.map { it.code.uppercase() }
        if (officialCodes.distinct().size != officialCodes.size ||
            officialCodes.first() != "MYS" || officialCodes.last() != "TLGP") return null
        val halts = officialCodes.toSet()
        val routeStops = mutableListOf<StopRow>()
        val covered = linkedSetOf<String>()
        routeStops += official.stops.first()
        official.stops.zipWithNext().forEach { (from, to) ->
            val a = byCode[from.code.uppercase()] ?: -1
            val b = byCode[to.code.uppercase()] ?: -1
            val span = if (a >= 0 && b > a) points.subList(a, b + 1) else emptyList()
            val physicalKm = if (span.isNotEmpty()) span.last().km - span.first().km else 0
            val currentKm = to.schedKm - from.schedKm
            // An old corridor is not permission to route across a changed junction,
            // skip a current scheduled stop, or manufacture unverified kilometre marks.
            val valid = span.isNotEmpty() && span.size <= 30 && physicalKm in 1..130 &&
                currentKm in 1..130 && physicalKm.toDouble() / currentKm in 0.65..1.5 &&
                span.drop(1).dropLast(1).none { it.code in halts }
            if (valid) {
                covered += from.code.uppercase()
                span.drop(1).dropLast(1).forEach { p ->
                    val scaled = from.schedKm +
                        ((p.km - span.first().km).toDouble() / physicalKm * currentKm).toInt()
                    routeStops += StopRow(0, p.code, p.name, null, null, null,
                        null, null, 0,
                        scaled.takeIf { it > from.schedKm && it < to.schedKm } ?: -1,
                        false)
                }
            }
            routeStops += to
        }
        if (covered.isEmpty()) return null
        val validatedRoute = official.copy(
            stops = routeStops.mapIndexed { i, row -> row.copy(seq = i + 1) },
            segments = emptyList())
        return Result(validatedRoute, covered)
    }
}
