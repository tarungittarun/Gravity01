package `in`.opentrack.india.domain

import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import kotlin.math.abs

/** Static schedule query, deliberately unrelated to live position or selected journey span.
 * The available list may contain only halts; callers MUST disclose when pass-through data is absent. */
object RouteBetween {
    /** A dated official timetable may confirm that a point the static map labelled "passing"
     * is in fact a scheduled halt. Keep map order, but use that timetable's halt status/times. */
    fun withVerifiedHalts(map: TrainRoute, schedule: TrainRoute): TrainRoute? {
        val codes = map.stops.map { it.code.uppercase() }
        val required = schedule.stops.map { it.code.uppercase() }
        if (map.trainNo != schedule.trainNo ||
            !map.srcCode.equals(schedule.srcCode, true) ||
            !map.dstCode.equals(schedule.dstCode, true) ||
            codes.filter { it in required } != required)
            return null
        val halts = schedule.stops.filter { it.isHalt }.associateBy { it.code.uppercase() }
        return map.copy(stops = map.stops.mapIndexed { index, point ->
            (halts[point.code.uppercase()] ?: point).copy(seq = index + 1)
        })
    }

    /** Only the points between this scheduled halt and the NEXT one. A different route or
     * out-of-order map contributes no rows; a point does not acquire a reported time. */
    fun groupedPoints(map: TrainRoute?, halts: TrainRoute): Map<String, List<StopRow>> {
        if (map == null || withVerifiedHalts(map, halts) == null) return emptyMap()
        val groups = LinkedHashMap<String, List<StopRow>>()
        var start = 0
        for (i in 0 until halts.stops.lastIndex) {
            val from = halts.stops[i]
            val end = map.stops.indexOfFirst { it.code.equals(halts.stops[i + 1].code, true) }
            if (end <= start) return emptyMap()
            val mapStart = map.stops[start].schedKm
            val mapEnd = map.stops[end].schedKm
            var lastKm = mapStart
            val points = map.stops.subList(start + 1, end).map { point ->
                val km = point.schedKm
                val credible = km >= 0 && mapStart >= 0 && mapEnd > mapStart &&
                    km in lastKm..mapEnd
                if (credible) lastKm = km
                point.copy(actualArrivalMin = null, actualDepartureMin = null,
                    schedKm = if (credible) km else -1)
            }
            if (points.isNotEmpty()) groups[from.code] = points
            start = end
        }
        return groups
    }

    fun followingPoints(map: TrainRoute?, halts: TrainRoute, fromCode: String): List<StopRow> =
        groupedPoints(map, halts)[fromCode].orEmpty()

    /** A departure is a state change, not an observed kilometre. Draw the icon a
     * visible quarter of the way to the FIRST mapped point (or next halt), solely as
     * a symbolic departure cue. Never expose its fraction as measured progress. */
    fun departureCueLeg(halts: TrainRoute, map: TrainRoute?, anchorCode: String,
                        websiteSuggestion: Boolean = false): LegProjection? {
        val base = lastReportedLeg(halts,
            map?.takeIf { withVerifiedHalts(it, halts) != null } ?: halts,
            anchorCode, if (websiteSuggestion) MarkerKind.SITE_DEPARTURE_SUGGESTION
                else MarkerKind.REPORTED_DEPARTURE_CUE) ?: return null
        val first = base.points.firstOrNull { it.fraction > 0f && it.fraction < 1f }
        return base.copy(fraction = (first?.fraction ?: 1f) * .25f,
            nearestPoint = first?.name ?: base.to)
    }

    /** A *projected* train icon within the two NTES halts. Never display this as observed
     * GPS or as an actual passage through an intermediate point. Hide on stale/schedule-only data. */
    data class LegPoint(val code: String, val name: String, val mapKm: Int, val fraction: Float,
                        val passageMinute: Int? = null, val approximate: Boolean = false,
                        val isHalt: Boolean = false, val siteArrivalMin: Int? = null,
                        val siteDepartureMin: Int? = null)
    enum class MarkerKind { PROJECTED, WEB_CACHE_CANDIDATE, SITE_INTERMEDIATE, SITE_LAST_STOP, REPORTED_HALT, STATION_CLAIM, REPORTED_DEPARTURE_CUE, SITE_DEPARTURE_SUGGESTION }
    data class LegProjection(
        val from: String, val to: String, val fraction: Float,
        val nearestPoint: String?, val estimateKm: Int,
        val fromCode: String, val toCode: String, val points: List<LegPoint>,
        val markerKind: MarkerKind = MarkerKind.PROJECTED,
    )

    /** Position already passed the dated web-event checks. This ONLY scales the cache's km
     * claim to the independent scheduled halt distance axis. It proves neither passage
     * through a point nor GPS; the model keeps Act cells and ETAs out of this view. */
    fun webCacheLeg(halts: TrainRoute, map: TrainRoute?, fromCode: String,
                    toCode: String, routeKm: Double): LegProjection? {
        val index = halts.stops.indexOfFirst { it.code.equals(fromCode, true) }
        if (index < 0 || index + 1 >= halts.stops.size ||
            !halts.stops[index + 1].code.equals(toCode, true)) return null
        val from = halts.stops[index]
        val to = halts.stops[index + 1]
        // The finite, in-range check also rejects NaN and infinity without relying on
        // Android/Java API-specific Double.isFinite implementations.
        if (from.schedKm < 0 || to.schedKm <= from.schedKm ||
            routeKm !in from.schedKm.toDouble()..to.schedKm.toDouble()) return null
        val fraction = ((routeKm - from.schedKm) / (to.schedKm - from.schedKm)).toFloat()
        val mapStart = map?.stops?.firstOrNull { it.code.equals(fromCode, true) }?.schedKm ?: -1
        val mapEnd = map?.stops?.firstOrNull { it.code.equals(toCode, true) }?.schedKm ?: -1
        val points = followingPoints(map, halts, fromCode).filter { it.schedKm >= 0 }
            .mapNotNull { point ->
                if (mapStart < 0 || mapEnd <= mapStart || point.schedKm !in mapStart..mapEnd) null
                else LegPoint(point.code, point.name, point.schedKm,
                    (point.schedKm - mapStart).toFloat() / (mapEnd - mapStart))
            }
        return LegProjection(from.name, to.name, fraction, null, routeKm.toInt(),
            from.code, to.code, points, MarkerKind.WEB_CACHE_CANDIDATE)
    }

    /** No measured between-stop distance: pin the locomotive to the site's last reported
     * halt, at ZERO of the next scheduled leg. This is deliberately not the current
     * position. Only use a train-matched, ordered map with usable distances for the ticks;
     * without a validated map, the dated halt schedule still locates the reported halt. */
    fun siteLastStopLeg(halts: TrainRoute, map: TrainRoute?, anchorCode: String): LegProjection? =
        lastReportedLeg(halts, map?.takeIf { withVerifiedHalts(it, halts) != null } ?: halts,
            anchorCode, MarkerKind.SITE_LAST_STOP)

    /** NTES or another source with an actual stop minute: pin at that reported halt.
     * A timetable alone never calls this. If the expanded map is unavailable, the
     * dated halt timetable still supplies bounding halts, but no intermediate ticks. */
    fun reportedHaltLeg(halts: TrainRoute, map: TrainRoute?, anchorCode: String): LegProjection? =
        lastReportedLeg(halts, map?.takeIf { withVerifiedHalts(it, halts) != null } ?: halts,
            anchorCode, MarkerKind.REPORTED_HALT)

    /** Same-run provider station/status claim, but no event minute. Keep it amber and
     * pinned to the claimed stop, without inventing a train clock or moving distance. */
    fun stationClaimLeg(halts: TrainRoute, map: TrainRoute?, code: String): LegProjection? =
        lastReportedLeg(halts, map?.takeIf { withVerifiedHalts(it, halts) != null } ?: halts,
            code, MarkerKind.STATION_CLAIM)

    private fun lastReportedLeg(halts: TrainRoute, map: TrainRoute, anchorCode: String,
                                kind: MarkerKind): LegProjection? {
        if (withVerifiedHalts(map, halts) == null) return null
        val i = halts.stops.indexOfFirst { it.code.equals(anchorCode, true) }
        if (i < 0 || i >= halts.stops.lastIndex) return null
        val from = halts.stops[i]; val to = halts.stops[i + 1]
        val mapFrom = map.stops.firstOrNull { it.code.equals(from.code, true) }?.schedKm ?: -1
        val mapTo = map.stops.firstOrNull { it.code.equals(to.code, true) }?.schedKm ?: -1
        if (from.schedKm < 0 || to.schedKm <= from.schedKm || mapFrom < 0 || mapTo <= mapFrom)
            return null
        val points = followingPoints(map, halts, from.code).filter {
            it.schedKm > mapFrom && it.schedKm < mapTo
        }.map { LegPoint(it.code, it.name, it.schedKm,
            (it.schedKm - mapFrom).toFloat() / (mapTo - mapFrom)) }
        return LegProjection(from.name, to.name, 0f, from.name, from.schedKm,
            from.code, to.code, points, kind)
    }

    /** The site's past card names a NON-HALT point, while an independent static map
     * supplies its distance along the leg. This is position AT that named station,
     * not a measured location or a claim of progress after leaving it. */
    fun siteIntermediateLeg(halts: TrainRoute, map: TrainRoute?, fromCode: String,
                            toCode: String, pointCode: String, pointKm: Int): LegProjection? {
        if (map == null) return null
        val i = halts.stops.indexOfFirst { it.code.equals(fromCode, true) }
        if (i < 0 || i >= halts.stops.lastIndex ||
            !halts.stops[i + 1].code.equals(toCode, true)) return null
        val from = halts.stops[i]; val to = halts.stops[i + 1]
        val mapFrom = map.stops.firstOrNull { it.code.equals(fromCode, true) }?.schedKm ?: -1
        val mapTo = map.stops.firstOrNull { it.code.equals(toCode, true) }?.schedKm ?: -1
        val points = followingPoints(map, halts, fromCode).filter { it.schedKm >= 0 }
        val point = points.firstOrNull { it.code.equals(pointCode, true) &&
            it.schedKm == pointKm } ?: return null
        if (mapFrom < 0 || mapTo <= mapFrom || pointKm <= mapFrom || pointKm >= mapTo)
            return null
        val projectedPoints = points.filter { it.schedKm in mapFrom..mapTo }.map { p ->
            LegPoint(p.code, p.name, p.schedKm, (p.schedKm - mapFrom).toFloat() / (mapTo - mapFrom))
        }
        return LegProjection(from.name, to.name, (pointKm - mapFrom).toFloat() / (mapTo - mapFrom),
            point.name, pointKm, from.code, to.code, projectedPoints, MarkerKind.SITE_INTERMEDIATE)
    }

    fun projectLeg(halts: TrainRoute, map: TrainRoute?, position: TrainPosition): LegProjection? {
        if (!position.provenance.live || position.ageSeconds > PositionEngine.STALE_MS / 1000 ||
            (position.feedAgeSeconds ?: 0L) > PositionEngine.STALE_MS / 1000 ||
            position.status != StopStatus.DEPARTED || position.fraction <= 0f ||
            position.fraction >= 1f) return null
        val from = halts.stopAtSeq(position.anchorSeq) ?: return null
        val to = halts.stopAtSeq(position.nextSeq) ?: return null
        if (from.schedKm < 0 || to.schedKm <= from.schedKm ||
            position.nextSeq != position.anchorSeq + 1) return null
        val fraction = position.fraction.coerceIn(0f, 0.97f)
        // Map distances come from a separate static source, so interpolate on that source's OWN
        // leg axis. Official and map total km differ by a kilometre on 20652.
        val mapStart = map?.stops?.firstOrNull { it.code.equals(from.code, true) }?.schedKm ?: -1
        val mapEnd = map?.stops?.firstOrNull { it.code.equals(to.code, true) }?.schedKm ?: -1
        val points = if (mapStart >= 0 && mapEnd > mapStart) followingPoints(map, halts, from.code)
            .filter { !it.isHalt && it.schedKm in mapStart..mapEnd }
            .map { LegPoint(it.code, it.name, it.schedKm,
                (it.schedKm - mapStart).toFloat() / (mapEnd - mapStart)) } else emptyList()
        val projected = if (mapStart >= 0 && mapEnd > mapStart)
            mapStart + (mapEnd - mapStart) * fraction else -1f
        val near = if (projected >= 0) points.minByOrNull { abs(it.mapKm - projected) }
            ?.takeIf { abs(it.mapKm - projected) <= maxOf(1.5f, (mapEnd - mapStart) * 0.15f) }
            ?.name else null
        return LegProjection(from.name, to.name, fraction, near,
            (from.schedKm + (to.schedKm - from.schedKm) * fraction).toInt(),
            from.code, to.code, points)
    }

    fun inclusive(route: TrainRoute, firstCode: String, secondCode: String): List<StopRow> {
        val a = route.stops.indexOfFirst { it.code.equals(firstCode, true) }
        val b = route.stops.indexOfFirst { it.code.equals(secondCode, true) }
        if (a < 0 || b < 0) return emptyList()
        return route.stops.subList(minOf(a, b), maxOf(a, b) + 1)
    }
}
