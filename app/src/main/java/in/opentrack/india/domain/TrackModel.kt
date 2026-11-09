package `in`.opentrack.india.domain

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import kotlin.math.abs

/**
 * Everything the tracking screen prints, computed once, in one place.
 *
 * This type exists because of a specific bug. The header was phrased by the position engine while the
 * stop list decided each row's state with its own `seq < anchorSeq` comparison, and the two disagreed:
 * the screen said "departed TIPTUR" and showed Tiptur's own row as *not* departed. Two rules over the
 * same facts will drift like that, and the user reads whichever they look at first. So the header is
 * now derived from the rows below, and the rows are the only place a state is computed.
 *
 * Pure Kotlin, no Android: the invariant that matters ("the highlighted row is the row the headline
 * talks about") is asserted in a plain unit test.
 */
object TrackModel {

    enum class State { PASSED, HERE, NEXT, LATER, CANCELLED, SKIPPED }

    enum class Tone { LIVE_OK, LIVE_LATE, LIVE_EARLY, SCHEDULE, PROBLEM, NONE }

    /**
     * Where the minute in a row's bright column came from. Three states, because there are three true
     * answers and the UI must not present them as two: a reported minute, a minute we moved off the
     * timetable because the train is running late, and the timetable itself.
     *
     * Collapsing the third into the second — which a single `estimated` boolean did — is how a screen with
     * no live feed at all managed to print every upcoming stop in projection amber and label it "expected",
     * i.e. to claim a forecast it had no data for.
     */
    enum class MinuteSource { REPORTED, SITE_REPORTED, PROJECTED, SCHEDULE }

    /**
     * One stop as the screen shows it.
     *
     * Times are rail minutes (see [RailTime]); the formatter adds the `( +1d )` the traveller needs to
     * read a 01:10 arrival on a train that started yesterday.
     *
     * @param estimated the shown time is schedule plus a carried delay, not a report about the train.
     *   The UI must style it as a forecast: a projected minute and a confirmed minute are different
     *   claims and a passenger decides differently on each.
     * @param isAnchor the stop the headline is about. Highlighting this row is what keeps the sentence
     *   and the table pointing at the same place.
     */
    data class Stop(
        val seq: Int,
        val code: String,
        val name: String,
        val schedArr: Int?,
        val schedDep: Int?,
        val showArr: Int?,
        val showDep: Int?,
        val delay: Int?,
        val source: MinuteSource,
        val platform: String?,
        val state: State,
        val isAnchor: Boolean,
        val kmFromOrigin: Int,
        val kmFromBoard: Int,
        val kmRemaining: Int,
        /** Print the distance column at all, and print it for the 0 km origin too. */
        val showKm: Boolean = false,
        val isBoarding: Boolean,
        val isAlighting: Boolean,
        /** minutes from `now` to this stop's shown departure (negative = it has gone) */
        val minutesFromNow: Int?,
    ) {
        /** The minute the comparison for this row is made on: departure, or arrival at the end. */
        val pivotMinute: Int? get() = showDep ?: showArr

        /** The shown minute is schedule-plus-carried-delay, not a report about the train. */
        val estimated: Boolean get() = source == MinuteSource.PROJECTED
    }

    /** Screen-ready strings, so the Activity contains no arithmetic and cannot invent a claim. */
    data class View(
        val trainNo: String,
        val trainName: String,
        val routeLabel: String,
        val runDateLabel: String,
        val headline: String,
        val tone: Tone,
        val provenanceLabel: String,
        val isLive: Boolean,
        val ageLabel: String,
        val delayLabel: String,
        val nextLabel: String,
        val progressLabel: String,
        val progressPct: Int,
        /**
         * Where the timetable puts the train at this minute, as a percentage of the same segment. The
         * progress bar draws it as a tick beside the fill, so "how late" is visible as a distance
         * instead of only as a number; -1 when there is nothing to compare against.
         */
        val schedulePct: Int,
        val rows: List<Stop>,
        val hasDistances: Boolean,
        val note: String?,
        val anchorSeq: Int,
        /**
         * "feed stamped 07:56 IST", from the provider's own generation time, or blank when the source
         * stamps nothing. Shown in the provenance disclosure beside the relative age: "4 min old" is the
         * number that decides whether to trust the screen, and the clock is the number that lets you check
         * it against your own.
         */
        val feedStampLabel: String = "",
    )

    /** Number of rows the timeline shows as "the train is here or past here". */
    fun passedCount(rows: List<Stop>): Int = rows.count { it.state == State.PASSED || it.isAnchor }

    /**
     * @param boardSeq/alightSeq the traveller's own segment. Distance and progress are measured from
     *   the boarding stop, not from the train's first station: "138 km covered" means nothing if the
     *   passenger got on at stop 40 of 60, which is the mistake most trackers make.
     * @param perStop what a feed confirmed per stop; may be empty.
     */
    fun build(
        route: TrainRoute,
        pos: TrainPosition,
        perStop: Map<String, LiveStopFields>,
        nowRailMinute: Int,
        boardSeq: Int,
        alightSeq: Int,
        runEpochDay: Long,
        note: String?,
        /** Only observed station events locate the train; other cells are the timetable. */
        positionOnly: Boolean = false,
    ): View {
        val live = pos.provenance.live
        val board = route.stops.firstOrNull { it.seq == boardSeq }
        // The full-route extension (round 47) adds halts the device timetable carries no km for
        // (schedKm -1); the ladder floors at the first KNOWN km so the known rows stay true.
        val boardKm = board?.schedKm?.takeIf { it >= 0 } ?: 0
        val hasAnchor = pos.anchorSeq > 0
        val overlay = PositionEngine.delayProfile(route, perStop, pos.anchorSeq, pos.delayMinutes)
        // The first stop still ahead of the clock, found once. Inside the row loop this would be a
        // second scan per row, and a 950-halt passenger train is 900 000 comparisons on the UI thread.
        val nextIdx = route.stops.indexOfFirst { (it.departureMin ?: it.arrivalMin ?: 0) > nowRailMinute }

        val stops = route.stops.mapIndexed { index, s ->
            val liveFields = perStop[s.code]
            // A cached report is a past observation, not permission to keep forecasting.
            val carried = if (positionOnly || pos.provenance == SourceKind.CACHED ||
                (live && s.seq != pos.nextSeq)) null else overlay.getOrNull(index)
            // Aligned to the scheduled minute before use: the feed answers with a clock, the table counts
            // from the run's first halt, and without this a halt on day 2 put "00:20" under "00:30 (+1d)" —
            // two cells in one row that a traveller has to subtract 24 hours between. The alignment is what
            // `LargeScreenRenderTest`'s picture showed happening; see `RailTime.alignClock`.
            // Even a feed field named 'actual' may be a future prediction. Never put it
            // under Act for an unreached stop. Past reported events remain historical facts.
            val reached = !positionOnly || (pos.anchorSeq > 0 && s.seq <= pos.anchorSeq)
            val actualArr = if (reached) RailTime.alignClock(liveFields?.actualArrivalMin, s.arrivalMin) else null
            val actualDep = if (reached) RailTime.alignClock(liveFields?.actualDepartureMin, s.departureMin) else null
            // Official ETA/ETD at a future stop are predictions, not historical delay.
            // Only the immediate next stop earns a forecast; later rows remain pure Sch.
            val delay = (if (positionOnly && s.seq > pos.anchorSeq) null
                else if (!live || s.seq <= pos.anchorSeq || s.seq == pos.nextSeq)
                    liveFields?.let { it.delayArrivalMin ?: it.delayDepartureMin } else null) ?: carried
            // Confirmed time where the feed reported one; otherwise the schedule, nudged by the delay
            // we are allowed to carry forward (which delayProfile restricts to stops still ahead).
            val estArr = s.arrivalMin?.let { it + (delay ?: 0) }
            val estDep = s.departureMin?.let { it + (delay ?: 0) }
            val shown = actualDep != null || actualArr != null
            val state = state(s, index, nextIdx, pos, live, nowRailMinute)
            Stop(
                seq = s.seq, code = s.code, name = s.name,
                schedArr = s.arrivalMin, schedDep = s.departureMin,
                // A reported arrival does not turn an unreported scheduled departure into an
                // "Act" minute (and vice versa). Row source is shared by both columns.
                showArr = if (shown) actualArr else estArr,
                showDep = if (shown) actualDep else estDep,
                delay = delay,
                source = when {
                    shown -> MinuteSource.REPORTED
                    // Nothing moved this row off the timetable: no report, no delay to carry. Printing it
                    // as an estimate would be a forecast invented out of an empty field.
                    delay == null -> MinuteSource.SCHEDULE
                    live -> MinuteSource.PROJECTED
                    else -> MinuteSource.SCHEDULE
                },
                platform = liveFields?.platform,
                state = state,
                isAnchor = hasAnchor && s.seq == pos.anchorSeq,
                kmFromOrigin = s.schedKm,
                kmFromBoard = (s.schedKm - boardKm).coerceAtLeast(0),
                kmRemaining = (alightKm(route, alightSeq) - s.schedKm).coerceAtLeast(0),
                isBoarding = s.seq == boardSeq,
                isAlighting = s.seq == alightSeq,
                // The next-stop label refers to its ARRIVAL, using the shown estimate,
                // not its scheduled departure (R55 called a 00:20 ETA "right now" at 00:15).
                minutesFromNow = (actualArr ?: estArr ?: actualDep ?: estDep)?.let { it - nowRailMinute },
            )
        }

        val anchor = stops.firstOrNull { it.isAnchor } ?: stops.lastOrNull { it.state == State.PASSED }
        val next = stops.firstOrNull { it.state == State.NEXT }
            ?: stops.firstOrNull { (it.minutesFromNow ?: -1) > 0 }
        val totalKm = alightKm(route, alightSeq) - boardKm
        val hasDistances = totalKm > 0 && stops.any { it.kmFromOrigin > 0 }
        // Once the dataset is known to carry distances, *every* row prints one — including the first, whose
        // "0 km" is the bottom rung of the ladder the reader is measuring the rest against. Gating the
        // column on `kmFromOrigin > 0` instead made the origin look like a stop with no distance.
        val rows = stops.map { it.copy(showKm = hasDistances && it.kmFromOrigin >= 0) }
        // Distance travelled within the passenger's own segment. The engine's `kmAlong` is measured
        // from the train's first station and is only trustworthy when a live (or schedule-interpolated)
        // position put it there; otherwise interpolate the anchor->next leg by the same fraction the
        // chart uses, so the bar, the sentence and the plot agree.
        val coveredKm = when {
            positionOnly && !live && pos.provenance != SourceKind.CACHED -> 0
            anchor == null -> 0
            positionOnly && pos.provenance != SourceKind.DERIVED_GPS -> anchor.kmFromOrigin.coerceAtLeast(0)
            pos.kmAlong > 0f -> pos.kmAlong.toInt()
            else -> anchor.kmFromOrigin + ((next?.kmFromOrigin ?: anchor.kmFromOrigin) - anchor.kmFromOrigin)
                .times(pos.fraction.coerceIn(0f, 1f)).toInt()
        }
        val covered = (coveredKm - boardKm).coerceAtLeast(0)
        val progressLabel: String
        val progressPct: Int
        if (positionOnly && !live && pos.provenance != SourceKind.CACHED) {
            progressPct = 0
            progressLabel = "Position unknown · timetable only"
        } else if (hasDistances) {
            progressPct = if (totalKm > 0) ((covered * 100) / totalKm).coerceIn(0, 100) else 0
            progressLabel = if (positionOnly && !live && pos.provenance != SourceKind.CACHED)
                "Position unknown · $totalKm km scheduled route"
            else if (positionOnly && pos.provenance != SourceKind.DERIVED_GPS)
                "Last reported halt · $covered of $totalKm scheduled km"
            else "$covered of $totalKm km" +
                (if (pos.fraction > 0f && pos.kmAlong > 0f) " · ${(totalKm - covered).coerceAtLeast(0)} km left" else "")
        } else {
            if (route.segments.isEmpty()) {
                // The endpoint route: "1 of 1 stops covered" measured nothing. The honest dial is the
                // elapsed share of the journey between the two times we DO have.
                val dep = route.stops.firstOrNull()?.departureMin
                val arr = route.stops.lastOrNull()?.arrivalMin
                progressPct = if (dep != null && arr != null && arr > dep) {
                    (((nowRailMinute - dep) * 100) / (arr - dep)).coerceIn(0, 100)
                } else 0
                progressLabel = "endpoint times only · per-stop halts unavailable on this device"
            } else {
                // Without credible distances the honest denominator is stops travelled, and it must say so.
                val done = passedCount(stops)
                val span = (route.stops.indexOfLast { it.seq == alightSeq } -
                    (route.stops.indexOfFirst { it.seq == boardSeq }).coerceAtLeast(0)).coerceAtLeast(1)
                progressPct = ((done * 100) / span).coerceIn(0, 100)
                progressLabel = if (positionOnly)
                    "Last reported halt · $done of $span scheduled stops · distances unavailable"
                else "$done of $span stops covered · distances unavailable offline"
            }
        }

        // The tick's denominator must be the *passenger's* segment, like the fill's, or the two marks
        // measure different journeys and the gap between them is meaningless.
        val seg = stops.filter { it.seq >= boardSeq && it.seq <= alightSeq }
        val schedAnchor = seg.lastOrNull { (it.schedDep ?: it.schedArr ?: Int.MAX_VALUE) <= nowRailMinute }
        val schedulePct = if (positionOnly) -1 else if (hasDistances && totalKm > 0) {
            ((schedAnchor?.kmFromBoard ?: 0) * 100 / totalKm).coerceIn(0, 100)
        } else {
            -1
        }

        return View(
            trainNo = route.trainNo,
            trainName = route.trainName,
            // Codes, distance, size of the working timetable — not the endpoints' full names, which are
            // longer than this line and were being ellipsised into "SBC → SMET · KSR BEN…". A header that
            // truncates mid-word is the kind of detail that makes a whole screen look untrustworthy.
            routeLabel = buildString {
                append(route.srcCode).append(" → ").append(route.dstCode)
                if (route.totalKm > 0) append(" · ").append(route.totalKm).append(" km")
                // Deliberately ends here. The stop count was next and it is what the 360 dp header ran out of
                // room for — "SBC → SMET · 274 km · …" — and an ellipsis on a two-clause line looks like the
                // app has more to say and is withholding it. The count is the first thing the table below
                // scrolls through, and the row count is asserted in `ShippedDataTest`.

            },
            runDateLabel = "run of ${RailTime.formatDate(runEpochDay)}",
            headline = if (positionOnly && live && pos.provenance != SourceKind.DERIVED_GPS && anchor != null) {
                val event = when (pos.status) {
                    StopStatus.ARRIVED -> "arrived at"
                    StopStatus.DEPARTED -> "departed"
                    else -> "reported near"
                }
                // A page's station claim has no passage minute. The anchor row still carries
                // its Sch time; it must never be repeated in a "Last reported" sentence.
                val time = (if (anchor.source == MinuteSource.REPORTED) {
                    if (pos.status == StopStatus.ARRIVED) anchor.showArr else anchor.showDep
                } else null)?.let { " · ${RailTime.formatRailMinute(it)}" } ?: ""
                "Last reported: $event ${TitleCase.of(anchor.name)}$time · current position unknown"
            } else headline(route, pos, anchor, next, live, nowRailMinute),
            tone = tone(pos, live),
            provenanceLabel = if (positionOnly && pos.provenance == SourceKind.PROVIDER_NTES)
                "NTES · last reported halt" else pos.provenance.label,
            feedStampLabel = pos.feedStampMillis?.let { "feed stamped ${RailTime.formatClock(it)} IST" } ?: "",
            isLive = live,
            // "1 min ago" on a screen with nothing to fetch is a small untruth with a big reach: the age
            // chip is the one place that says whether data arrived. `ageSeconds` is 0 for a schedule-only
            // answer, which `age()` renders as "live" — exactly the claim this app must never make.
            ageLabel = when {
                pos.provenance == SourceKind.CACHED ->
                    "last fetched ${pos.ageSeconds / 60} min ago · not live"
                !live -> "no live fetch"
                pos.feedAgeSeconds != null -> feedAgeLabel(pos.feedAgeSeconds!!)
                else -> age(pos.ageSeconds)
            },
            delayLabel = delayLabel(pos, live),
            nextLabel = if (positionOnly && next != null) {
                val t = next.schedArr ?: next.schedDep
                "Next scheduled: ${TitleCase.of(next.name)}" +
                    (t?.let { " · ${RailTime.formatRailMinute(it)}" } ?: "") +
                    " · arrival not confirmed"
            } else nextLabel(next, route, alightSeq, pos.provenance == SourceKind.CACHED),
            progressLabel = progressLabel,
            progressPct = progressPct,
            schedulePct = schedulePct,
            rows = rows,
            hasDistances = hasDistances,
            note = note,
            anchorSeq = pos.anchorSeq,
        )
    }

    // --------------------------------------------------------------------------- row states

    /**
     * The one rule for "where is this stop relative to the train".
     *
     * With a live report, position is a fact about the anchor and everything is compared to its
     * sequence. Without one, position is a *consequence of the clock*, so a stop counts as passed only
     * once its scheduled departure is behind us — which is why the offline header below never says
     * "departed", it says what the timetable implies.
     */
    private fun state(
        s: StopRow, index: Int, nextIdx: Int, pos: TrainPosition, live: Boolean, now: Int,
    ): State {
        if (s.status == StopStatus.CANCELLED_HERE) return State.CANCELLED
        if (s.status == StopStatus.SKIPPED) return State.SKIPPED
        val anchorSeq = pos.anchorSeq
        if (live && anchorSeq > 0) {
            return when {
                s.seq < anchorSeq -> State.PASSED
                s.seq == anchorSeq ->
                    if (pos.status == StopStatus.ARRIVED || pos.status == StopStatus.UNKNOWN) State.HERE
                    else State.PASSED
                s.seq == pos.nextSeq -> State.NEXT
                else -> State.LATER
            }
        }
        // Schedule-only: judge each stop by its own clock, and let the anchor fall out of that.
        val pivot = s.departureMin ?: s.arrivalMin ?: return State.LATER
        val left = pivot <= now
        val isAnchor = anchorSeq > 0 && s.seq == anchorSeq
        return when {
            isAnchor && pos.status == StopStatus.ARRIVED -> State.HERE
            left -> State.PASSED
            index == nextIdx -> State.NEXT
            else -> State.LATER
        }
    }

    private fun headline(
        route: TrainRoute, pos: TrainPosition, anchor: Stop?, next: Stop?,
        live: Boolean, now: Int,
    ): String {
        if (anchor == null) {
            return if (pos.status == StopStatus.UPCOMING) "Not departed yet" else "Position not available yet"
        }
        // The live-only endpoint route (the offline copy lacks the train): a position sentence
        // measured against a two-stop phantom read as "4h 49m into the 0 km leg" - a malfunction to
        // the one person reading it. Say what the two endpoints know, and nothing more. The shape is
        // named by its segments: a real route with distances missing still earns the leg/stops
        // language (the kmAllZero fallback), the endpoint pair has no legs at all.
        if (route.stops.size == 2 && route.segments.isEmpty()) {
            val first = route.stops.firstOrNull()
            val last = route.stops.lastOrNull()
            val dep = first?.departureMin?.let { RailTime.formatRailMinute(it) } ?: "—"
            val arr = last?.arrivalMin?.let { RailTime.formatRailMinute(it) } ?: "—"
            return "Endpoints only: leaves ${TitleCase.of(first?.name ?: route.srcName)} $dep, reaches " +
                "${TitleCase.of(route.dstName)} $arr — this device has no halts for this train"
        }
        // The station names inside a *sentence* are title-cased, because the table below renders them that
        // way and a screen that writes "KSR Bengaluru" in one place and "YESVANTPUR JN" in another reads
        // like two apps stitched together. The rule is the one the rows already use (`TitleCase`), so the
        // two can never disagree about how a name looks.
        val name = TitleCase.of(anchor.name)
        val late = pos.delayMinutes?.let { if (abs(it) >= 2) " · ${RailTime.formatDelay(it)}" else "" } ?: ""
        if (anchor.state == State.CANCELLED) return "Cancelled at $name"
        if (pos.provenance == SourceKind.CACHED) {
            val t = anchor.showDep ?: anchor.showArr
            return "Last confirmed at $name" + (t?.let { " · ${RailTime.formatRailMinute(it)}" } ?: "") +
                " · current position unknown"
        }
        if (!live) {
            // Everything here is an inference from the timetable. Say "should be", never "is".
            val gap = now - (anchor.schedDep ?: anchor.schedArr ?: now)
            val ahead = next?.let { it.kmFromBoard - anchor.kmFromBoard }
            return when {
                anchor.state == State.HERE -> "Should be at $name now"
                // `now` and the schedule are RAIL MINUTES - 90 minutes is 90, not 90*60, so the old
                // comparison asked a run to be ninety HOURS stale before calling it finished, and a
                // run three hours past its terminus (20652 on the owner's 15:14 paste) fell through
                // to "Should have left <terminus> by now" - nonsense for a train that ends there.
                // Finished = a terminus anchor past 90 minutes, or any anchor after 90 hours.
                gap > 90 * 60 || (next == null && gap > 90) ->
                    "Should have finished the run at ${TitleCase.of(route.dstName)}"
                ahead != null && gap > 0 -> "Should be past $name, ${RailTime.formatDuration(gap)} into the " +
                    "$ahead km leg"
                else -> "Should have left $name by now"
            }
        }
        val platform = anchor.platform?.takeIf { it.isNotBlank() }?.let { " · platform $it" } ?: ""
        return when {
            anchor.state == State.HERE -> "At $name$platform"
            pos.fraction >= 0.95f && next != null -> "Approaching ${TitleCase.of(next.name)}"
            pos.fraction > 0f && next != null -> "Between $name and ${TitleCase.of(next.name)}"
            pos.status == StopStatus.DEPARTED -> "Departed $name$late"
            else -> "Last report at $name"
        }
    }

    private fun tone(pos: TrainPosition, live: Boolean): Tone = when {
        pos.status == StopStatus.CANCELLED_HERE || pos.status == StopStatus.SKIPPED -> Tone.PROBLEM
        !live -> Tone.SCHEDULE
        pos.delayMinutes == null -> Tone.NONE
        pos.delayMinutes!! >= 2 -> Tone.LIVE_LATE
        pos.delayMinutes!! <= -2 -> Tone.LIVE_EARLY
        else -> Tone.LIVE_OK
    }

    private fun delayLabel(pos: TrainPosition, live: Boolean): String {
        val d = pos.delayMinutes
        return when {
            // Short on purpose: this sits in a cell labelled "Delay" that is about 90dp wide, and the
            // sentence it used to carry is already printed full-width in the note strip below.
            !live && d == null -> "no live feed"
            // A cached or GPS-derived answer is stale but it does carry a delay, and hiding the number
            // would be less honest than showing it next to the age of the fetch.
            !live -> RailTime.formatDelay(d!!)
            d == null -> "delay not reported"
            abs(d) < 2 -> "on time"
            else -> RailTime.formatDelay(d)
        }
    }

    private fun nextLabel(next: Stop?, route: TrainRoute, alightSeq: Int, cached: Boolean): String {
        if (next == null) return "Last halt reached · destination ${TitleCase.of(route.dstName)}"
        val t = next.showArr ?: next.showDep ?: return "Next: ${TitleCase.of(next.name)}"
        val clock = RailTime.formatRailMinute(t)
        val when0 = next.minutesFromNow?.let {
            if (next.estimated && it < 0) " · estimate overdue; arrival unconfirmed"
            else " · ${formatGap(it)}"
        } ?: ""
        val kind = if (next.estimated) "expected" else "due"
        val nm = TitleCase.of(next.name)
        val at = if (next.seq == alightSeq) "$nm (your stop)" else nm
        return if (cached) "Next scheduled: $at · $clock · arrival not confirmed"
            else "Next: $at · $clock $kind$when0"
    }

    /**
     * The provider's own "as of" stamp, worded for somebody standing on a platform. `age()` above can
     * only report when *we* last read the network, and a feed generated six hours ago is not made
     * current by fetching it fast; that is the difference between "live" and "live · feed 6 h old", and
     * the second one is the only honest reading of a delayed upstream.
     */
    private fun feedAgeLabel(seconds: Long): String = when {
        seconds < 120 -> "feed is current"
        seconds < 3600 -> "feed ${seconds / 60} min old"
        seconds < 86_400 -> "feed ${seconds / 3600} h old"
        else -> "feed is ${seconds / 86_400} days old — read this as a timetable"
    }

    private fun age(seconds: Long): String = when {
        seconds <= 0 -> "live"
        seconds < 60 -> "updated ${seconds}s ago"
        seconds < 3600 -> "updated ${seconds / 60} min ago"
        else -> "updated ${seconds / 3600} h ago"
    }

    /**
     * "in 40 min" / "42 min ago" / "in 1h 30m" — the same words on both sides of now, so a column of
     * them can be scanned. Minutes below the hour are spelled out rather than abbreviated to `40m`,
     * because this string is read at arm's length across a platform; `formatDuration` keeps the compact
     * form for the dense meta lines it was written for.
     */
    fun formatGap(minutes: Int): String {
        if (minutes == 0) return "right now"
        val abs = if (minutes > 0) minutes else -minutes
        val body = if (abs < 60) "$abs min" else RailTime.formatDuration(abs)
        return if (minutes > 0) "in $body" else "$body ago"
    }

    private fun alightKm(route: TrainRoute, alightSeq: Int): Int =
        route.stops.firstOrNull { it.seq == alightSeq }?.schedKm?.takeIf { it >= 0 }
            ?: route.stops.lastOrNull { it.schedKm >= 0 }?.schedKm ?: 0

}
