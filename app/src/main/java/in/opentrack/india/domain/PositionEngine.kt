package `in`.opentrack.india.domain

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.core.Text
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Derives "where is the train" from "what did the network last confirm".
 *
 * The Indian Railways feeds usable for free report **events**, not a GPS trace: *at BRC, departed
 * 12:22, 14 min late*. Turning that into a moving marker needs an explicit model, and the model *is*
 * the product — a marker that jumps, rewinds, or sits at a station for two hours after departure
 * destroys trust faster than no marker at all. Rules, in priority order:
 *
 *  1. **A confirmed event wins.** The position is pinned to the stop until enough elapsed time on
 *     the next leg makes interpolation meaningful. That hysteresis is what stops the marker
 *     flickering between "at BRC" and "between BRC and RTM" on every refresh.
 *  2. **Between stations, interpolate on time, not distance.** The feed gives the delay at the anchor,
 *     so the honest estimate is `elapsed / (scheduledLegMin + delay)`; when the leg duration is
 *     unknown we report the anchor rather than guessing.
 *  3. **Never move backwards** ([RailTimePosition.held]). Re-broadcast of an older event (routine on
 *     relayed feeds) must not drag the marker 200 km back.
 *  4. **A measurement outranks a relay.** If the device is on the train and GPS places it more than
 *     [GPS_AHEAD_SEGMENTS] segment(s) ahead of the interpolated point, follow the device and label it
 *     `DERIVED · GPS`. GPS is never required.
 *  5. **Staleness is visible.** Beyond [STALE_MS] the snapshot is history: still drawn (that is what
 *     offline-first is for) but the provenance changes so the UI can dim it.
 *  6. **No invented precision.** An ETA is produced only when the feed supplied one or the remaining
 *     leg is short; otherwise [TrainPosition.etaNextMinute] is null and the UI must say "between A
 *     and B".
 */
object PositionEngine {

    const val STALE_MS = 10 * 60_000L
    const val HISTORY_MS = 6 * 60 * 60_000L
    /** Beyond this, "the train is on time" is not a useful assumption, so we stop estimating. */
    const val SCHEDULE_ONLY_MAX_MIN = 45
    /** Long legs are too variable for a minute-level promise. */
    const val MAX_ETA_LEG_MIN = 90
    const val GPS_AHEAD_SEGMENTS = 1
    const val GPS_MAX_ACCURACY_M = 120f

    data class Request(
        val route: TrainRoute,
        val snapshot: LiveSnapshot?,
        /** rail minutes for the query instant, on the same axis as the route */
        val nowRailMinute: Int,
        val nowMillis: Long,
        val previous: TrainPosition? = null,
        val gps: GpsEvidence? = null,
    )

    /** What the device itself can prove, independent of any server. */
    data class GpsEvidence(
        val nearestStationCode: String?,
        val nearestStationSeq: Int,
        val distanceKm: Float,
        val groundSpeedKmh: Float,
        val accuracyM: Float,
    )

    /** Boxed previous position, so the UI layer can hand one back without naming [TrainPosition]. */
    data class TrainPositionInput(val position: TrainPosition)

    /** Answer to [Request], including the prose the UI shows and whether we had to fall back. */
    data class Answer(
        val position: TrainPosition,
        val anchorStopCode: String?,
        val anchorStopName: String?,
        val note: String?,
        /** the feed's last station was not on the scheduled route (diversion / coverage gap) */
        val unresolved: Boolean = false,
        val perStop: Map<String, LiveStopFields> = emptyMap(),
        /** An earlier accepted official clock carried on this exact run, never a new event. */
        val savedOfficialClocks: Set<String> = emptySet(),
        /** true when rule 3 or 4 replaced the raw estimate */
        val held: Boolean = false,
    )

    fun locate(req: Request): Answer {
        val route = req.route
        if (route.stops.isEmpty()) {
            return Answer(
                position = TrainPosition(
                    anchorSeq = 0, nextSeq = 0, fraction = 0f, kmAlong = 0f,
                    status = StopStatus.UNKNOWN, etaNextMinute = null, delayMinutes = null,
                    remainingMin = null, provenance = SourceKind.NONE,
                    confidence = TrainPosition.Confidence.LOW, ageSeconds = 0,
                ),
                anchorStopCode = null, anchorStopName = null,
                note = "no schedule data for this train on this device",
                unresolved = true,
            )
        }
        val snap = req.snapshot?.takeIf {
            it.atCode != null || it.atSeq > 0 || it.perStop.isNotEmpty()
        }
        return if (snap == null) scheduleOnly(req) else fromSnapshot(req, snap)
    }

    // ---------------------------------------------------------------- live-feed path

    private fun fromSnapshot(req: Request, snap: LiveSnapshot): Answer {
        val route = req.route
        val age = (req.nowMillis - snap.fetchedAtMillis).coerceAtLeast(0L)
        val feedAge = feedAgeSeconds(snap, req)
        var provenance = when {
            age > HISTORY_MS -> SourceKind.CACHED
            snap.sourceKind.live && age > STALE_MS -> SourceKind.CACHED
            else -> snap.sourceKind
        }
        val anchor = resolveAnchor(route, snap)
        val stop = anchor?.let { route.stopAtSeq(it) }
        if (anchor == null || stop == null) {
            return unresolvedReport(req, snap, age, provenance)
        }

        val delay: Int? = snap.delayMinutes ?: run {
            val sched = snap.scheduledMinuteAtEvent
            val ev = snap.eventMinute
            if (ev != null && sched != null) ev - sched else null
        }
        val haltMin = maxOf(
            0,
            (stop.departureMin ?: stop.arrivalMin ?: 0) - (stop.arrivalMin ?: stop.departureMin ?: 0)
        )
        // An observed arrival does NOT become an observed departure when the scheduled
        // halt duration expires. The owner's 16227 had SMET Act arrival 04:53, no ATD,
        // and the old clock-only promotion said "departed" at 05:08.
        var status = when (snap.event) {
            StopStatus.CANCELLED_HERE -> StopStatus.CANCELLED_HERE
            StopStatus.ARRIVED, StopStatus.APPROACHING -> StopStatus.ARRIVED
            StopStatus.DEPARTED -> StopStatus.DEPARTED
            else -> StopStatus.UNKNOWN
        }
        var anchorSeq = anchor
        var fraction = 0f
        var km = stop.schedKm.toFloat()
        // While the train is standing at the anchor, the only honest future minute is its scheduled
        // departure from this halt; the next stop's arrival is derived from the leg below. A time
        // already behind us is dropped rather than displayed, because "next stop at 09:12" while it is
        // 11:00 reads as a broken app even when it is technically the schedule.
        var eta: Int? = (stop.departureMin ?: stop.arrivalMin)
            ?.let { it + (delay ?: 0) }
            ?.takeIf { it >= req.nowRailMinute }
        var confidence = TrainPosition.Confidence.MEDIUM
        var note: String? = null

        val segment = route.segmentAfter(anchor)
        val legMin = segment?.legMin
        val eventMin = snap.eventMinute
        if (status == StopStatus.ARRIVED && eventMin != null &&
            req.nowRailMinute - eventMin > haltMin) {
            eta = null
            note = "Last reported arrival at ${stop.code}; departure not reported, current position unknown"
        }
        if (status == StopStatus.DEPARTED) {
            eta = null
            if (provenance == SourceKind.CACHED) {
                note = "Last confirmed at ${stop.code}; current position unknown until a live report arrives"
            } else if (segment != null && legMin != null && legMin > 0 && eventMin != null) {
                val elapsed = req.nowRailMinute - eventMin
                if (elapsed > 0) {
                    // Lateness is already baked into the REPORTED departure eventMin.
                    // Adding it again to the leg duration trapped 20689 at Birur: at
                    // 13:21, seven minutes after its 13:14 departure on a 26-minute,
                    // 24-km leg, 70 min of accumulated delay turned 7/26 into 7/96.
                    // This is only a distance-by-time projection, never observed GPS.
                    val expected = max(1, legMin)
                    // 0.97 cap: "arrived" is the feed's word to say, not ours.
                    fraction = min(0.97f, elapsed.toFloat() / expected.toFloat())
                    km = stop.schedKm + segment.legKm * fraction
                    eta = etaIfTrustworthy(route, anchor, elapsed, expected, delay, req.nowRailMinute)
                    if (fraction >= 0.9f) confidence = TrainPosition.Confidence.LOW
                }
            } else {
                val nextName = route.stopAtSeq(anchor + 1)?.name ?: "the next stop"
                note = "between ${stop.name} and $nextName — leg duration not published"
            }
        }

        // Rule 3 — monotonic across refreshes. `req.previous` is only supplied by the caller when it
        // belongs to the same run, so no identity check is needed here.
        val anchorNow = anchorSeq
        req.previous?.let { prev ->
            if (prev.anchorSeq > anchorNow) {
                val kept = prev.copy(
                    ageSeconds = age / 1000, provenance = provenance,
                    feedAgeSeconds = feedAge, feedStampMillis = snap.feedStampMillis,
                )
                return Answer(
                    position = kept,
                    anchorStopCode = route.stopAtSeq(prev.anchorSeq)?.code,
                    anchorStopName = route.stopAtSeq(prev.anchorSeq)?.name,
                    note = "holding the last position: the feed re-reported an earlier stop",
                    perStop = snap.perStop,
                    savedOfficialClocks = snap.savedOfficialClocks,
                    held = true,
                )
            }
        }

        // Rule 4 — the device may know better than the relay.
        val gps = req.gps
        if (gps != null && gps.nearestStationSeq > anchorSeq + GPS_AHEAD_SEGMENTS &&
            gps.accuracyM <= GPS_MAX_ACCURACY_M && route.stopAtSeq(gps.nearestStationSeq) != null
        ) {
            anchorSeq = gps.nearestStationSeq
            fraction = 0f
            status = StopStatus.DEPARTED
            km = route.stopAtSeq(anchorSeq)?.schedKm?.toFloat() ?: km
            provenance = SourceKind.DERIVED_GPS
            confidence = TrainPosition.Confidence.MEDIUM
            note = "device GPS is ${anchorSeq - anchor} stop(s) ahead of the live feed; " +
                "showing the measured position"
        }

        val last = route.stops.last()
        val arrivalAtEnd = last.arrivalMin ?: last.departureMin
        val remaining = if (provenance == SourceKind.CACHED) null else arrivalAtEnd?.let {
            (it + (delay ?: 0) - req.nowRailMinute).takeIf { m -> m in 0..(20 * 1440) }
        }

        return Answer(
            position = TrainPosition(
                anchorSeq = anchorSeq,
                nextSeq = min(route.lastSeq, anchorSeq + 1),
                fraction = fraction,
                kmAlong = km,
                status = status,
                etaNextMinute = eta,
                delayMinutes = delay,
                remainingMin = remaining,
                provenance = provenance,
                confidence = confidence,
                ageSeconds = age / 1000,
                feedAgeSeconds = feedAge,
                feedStampMillis = snap.feedStampMillis,
            ),
            anchorStopCode = route.stopAtSeq(anchorSeq)?.code,
            anchorStopName = route.stopAtSeq(anchorSeq)?.name,
            note = note,
            perStop = snap.perStop,
            savedOfficialClocks = snap.savedOfficialClocks,
        )
    }

    /**
     * Map the feed's "last reported station" onto *this* route.
     *
     * Sequence numbers from the feed are only comparable when the feed and the offline timetable agree
     * on the stop list, and they rarely do (the dump has 950 halts for some trains where the feed lists
     * 700). So the code is the primary key and the sequence a tiebreaker for the case that actually
     * bites on Indian routes: a train that visits the same station twice (a reversal/leg change), where
     * picking the first occurrence would put the marker hundreds of kilometres behind.
     *
     * Returns the route `seq` of the best match, or null when the feed's station is not on this route at
     * all — which the caller reports as an unresolved report rather than guessing.
     */
    private fun resolveAnchor(route: TrainRoute, snap: LiveSnapshot): Int? {
        val code = snap.atCode?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
        if (code != null) {
            val matches = route.stops.filter { it.code.equals(code, ignoreCase = true) }
            if (matches.isNotEmpty()) {
                val preferred = snap.atSeq.takeIf { it > 0 }?.let { wanted ->
                    // Closest occurrence to the claimed sequence — handles the two-visit case without
                    // trusting a sequence number that may be wrong.
                    matches.minByOrNull { kotlin.math.abs(it.seq - wanted) }
                } ?: matches.first()
                return preferred.seq
            }
        }
        val seq = snap.atSeq
        if (seq > 0 && route.stopAtSeq(seq) != null) {
            // No usable code (or an unknown one): trust the number only if that stop's code agrees,
            // otherwise a mismatched route would silently move the marker to a different station.
            val stop = route.stopAtSeq(seq)!!
            if (code == null || Text.same(stop.code, code)) return seq
            return null
        }
        return null
    }

    /**
     * How old the *fact* is, as opposed to how old our socket read was. A provider that stamps its
     * payload lets the screen answer the traveller's real question ("is this where the train is now?"),
     * and a stamp in the future — device clock skew, or an upstream format change we are misreading —
     * is dropped rather than printed as a negative age, because the UI has no honest way to show that.
     */
    private fun feedAgeSeconds(snap: LiveSnapshot, req: Request): Long? =
        snap.feedStampMillis?.let { ((req.nowMillis - it) / 1000L).takeIf { s -> s >= 0L } }

    /**
     * The feed is alive but its station is not on our timetable — a diversion, or a halt the bundled
     * dump does not cover. Say so and show the last known state instead of snapping the marker to a
     * plausible-looking stop, which is how these apps generate wrong-train complaints.
     */
    private fun unresolvedReport(
        req: Request, snap: LiveSnapshot, age: Long, provenance: SourceKind,
    ): Answer {
        val route = req.route
        val byTime = route.stops.lastOrNull { (it.departureMin ?: it.arrivalMin ?: -1) <= req.nowRailMinute }
        return Answer(
            position = TrainPosition(
                anchorSeq = byTime?.seq ?: 0,
                nextSeq = byTime?.let { min(route.lastSeq, it.seq + 1) } ?: 0,
                fraction = 0f,
                kmAlong = byTime?.schedKm?.toFloat() ?: 0f,
                status = StopStatus.UNKNOWN,
                etaNextMinute = snap.expectedNextMinute,
                delayMinutes = snap.delayMinutes,
                remainingMin = null,
                provenance = provenance,
                confidence = TrainPosition.Confidence.LOW,
                ageSeconds = age / 1000,
                feedAgeSeconds = feedAgeSeconds(snap, req),
                feedStampMillis = snap.feedStampMillis,
            ),
            anchorStopCode = snap.atCode,
            anchorStopName = snap.atName,
            note = if (snap.atCode == null && snap.atName == null) {
                // The feed answered about *this train* without naming a station anywhere: this is what a
                // source that publishes no running times looks like from here, and quoting a report of
                // “null” at a traveller is not a sentence, it is a bug report on a screen.
                "the live feed named no station for this run, so there is nothing to place on the route"
            } else {
                "last report “${snap.atName ?: snap.atCode}” is not on the scheduled route " +
                    "(diversion, or not in the offline timetable)"
            },
            unresolved = true,
            perStop = snap.perStop,
            savedOfficialClocks = snap.savedOfficialClocks,
        )
    }

    /**
     * ETA at the next stop, or null when the estimate would be noise: no scheduled leg, or a leg with
     * more than [MAX_ETA_LEG_MIN] minutes left (long sections vary too much to promise a minute).
     */
    private fun etaIfTrustworthy(
        route: TrainRoute, anchorSeq: Int, elapsed: Int, expected: Int, delay: Int?,
        nowRailMinute: Int,
    ): Int? {
        if (expected <= 0 || expected - elapsed > MAX_ETA_LEG_MIN) return null
        val next = route.stopAtSeq(anchorSeq + 1) ?: return null
        val sched = next.arrivalMin ?: next.departureMin ?: return null
        val at = sched + (delay ?: 0)
        return at.takeIf { it >= nowRailMinute }
    }

    // ---------------------------------------------------------------- schedule-only path

    /**
     * No live data at all. With the timetable on the device and an on-time assumption, the traveller
     * still gets an honest "it should be near X now", bounded by [SCHEDULE_ONLY_MAX_MIN].
     */
    private fun scheduleOnly(req: Request): Answer {
        val route = req.route
        val now = req.nowRailMinute
        val idx = route.stops.indexOfLast { (it.departureMin ?: it.arrivalMin ?: Int.MIN_VALUE) <= now }
        if (idx < 0) {
            return Answer(
                position = TrainPosition(
                    anchorSeq = 0, nextSeq = min(route.lastSeq, 1), fraction = 0f, kmAlong = 0f,
                    status = StopStatus.UPCOMING,
                    etaNextMinute = route.stops.first().departureMin?.takeIf { it >= now },
                    // null, not 0: 0 renders as "on time", which is a report about the train. With no
                    // feed there is nothing to report, and an assumption must not look like a fact.
                    delayMinutes = null, remainingMin = null,
                    provenance = SourceKind.OFFLINE_SCHEDULE,
                    confidence = TrainPosition.Confidence.LOW, ageSeconds = 0,
                ),
                anchorStopCode = route.stops.first().code,
                anchorStopName = route.stops.first().name,
                note = "has not departed yet (scheduled)",
            )
        }
        val at: StopRow = route.stops[idx]
        val next = route.stops.getOrNull(idx + 1)
        val dep = at.departureMin ?: at.arrivalMin ?: now
        val arrNext = next?.arrivalMin ?: next?.departureMin
        val elapsed = now - dep
        val legMin = arrNext?.let { max(1, it - dep) }
        val canEstimate = next != null && legMin != null && elapsed in 1..SCHEDULE_ONLY_MAX_MIN
        if (!canEstimate) {
            return Answer(
                position = TrainPosition(
                    anchorSeq = at.seq, nextSeq = next?.seq ?: at.seq, fraction = 0f,
                    kmAlong = at.schedKm.toFloat(),
                    status = if (elapsed <= 2) StopStatus.DEPARTED else StopStatus.UNKNOWN,
                    etaNextMinute = arrNext?.takeIf { it >= now },
                    delayMinutes = null, remainingMin = null,
                    provenance = SourceKind.OFFLINE_SCHEDULE,
                    confidence = TrainPosition.Confidence.LOW, ageSeconds = 0,
                ),
                anchorStopCode = at.code, anchorStopName = at.name,
                note = if (next == null) "end of the scheduled run"
                else "no live data; shown position assumes the schedule",
            )
        }
        val fraction = min(0.97f, elapsed.toFloat() / legMin!!.toFloat())
        val last = route.stops.last()
        val endArrival = last.arrivalMin ?: last.departureMin
        return Answer(
            position = TrainPosition(
                anchorSeq = at.seq, nextSeq = next!!.seq, fraction = fraction,
                kmAlong = at.schedKm + (route.segmentAfter(at.seq)?.legKm ?: 0) * fraction,
                status = StopStatus.DEPARTED, etaNextMinute = arrNext?.takeIf { it >= now },
                delayMinutes = null,
                remainingMin = endArrival?.let { (it - now).takeIf { m -> m in 0..(20 * 1440) } },
                provenance = SourceKind.OFFLINE_SCHEDULE,
                confidence = TrainPosition.Confidence.LOW, ageSeconds = 0,
            ),
            anchorStopCode = at.code, anchorStopName = at.name,
            note = "no live data; position assumes the train is on time",
        )
    }

    // ---------------------------------------------------------------- helpers for the UI

    /**
     * Per-stop delay for the timetable screen: the feed's value where it confirmed a stop, the anchor's
     * delay carried *forward* for the stops still to come (that is how the station boards phrase it), and
     * null where nothing is known — never 0, because "0" on a departure board reads as "confirmed on
     * time", which is a claim about the past rather than a forecast.
     */
    fun delayProfile(
        route: TrainRoute, perStop: Map<String, LiveStopFields>, anchorSeq: Int, anchorDelay: Int?,
    ): List<Int?> {
        val out = ArrayList<Int?>(route.stops.size)
        for (s in route.stops) {
            val live = perStop[s.code]
            val fromLive = live?.let { it.delayArrivalMin ?: it.delayDepartureMin }
            // Forward-only carry: a stop the train has already left either has its own confirmed number
            // or none at all. Filling it with the anchor's delay would rewrite history.
            out += fromLive ?: if (s.seq > anchorSeq) anchorDelay else null
        }
        return out
    }


}
