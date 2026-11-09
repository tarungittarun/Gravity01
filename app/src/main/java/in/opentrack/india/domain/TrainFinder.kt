package `in`.opentrack.india.domain

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.data.RefQueries
import `in`.opentrack.india.model.PairStop
import `in`.opentrack.india.model.StationHit
import `in`.opentrack.india.model.TrainHit

/**
 * The "find my train" brain: turns a from/to pair plus a target instant into the list of runs the
 * traveller can actually board, entirely from the on-device timetable.
 *
 * Why this is not pushed into SQL. The window is *asymmetric in meaning* even though it is symmetric
 * in size, and it depends on data the SQL layer deliberately does not own:
 *   - the run's origin date is `targetDate - (boardMinute div 1440)`, so a train that leaves its
 *     source the previous evening but passes the user's station at 01:20 is a valid hit;
 *   - the weekday bit is checked against **that origin date**, not the boarding date, because Indian
 *     Railways publishes weekly running days per origin date (e.g. a train that "runs Wed" from NZM
 *     and reaches the user's station on Thursday).
 * Doing that with a join would need a date table and a UDF; doing it in 40 lines of Kotlin makes it
 * unit-testable, which is what actually matters here — this is the logic most likely to be quietly
 * wrong and most visible when it is.
 */
object TrainFinder {

    /** The requirement: "-24 to +24 hours" around the selected instant. */
    const val WINDOW_MINUTES = 1440

    enum class Reason { OK, STATION_NOT_FOUND, NO_DIRECT_TRAINS, WRONG_DATE, SAME_STATION }

    data class Report(
        val from: StationHit?,
        val to: StationHit?,
        val hits: List<TrainHit>,
        val reason: Reason,
        /** trains that connect the pair but not on this date — drives the "runs only on Mon/Wed" hint */
        val notRunningOnDate: Int = 0,
        val notRunningHint: String? = null,
        val queryMillis: Long = 0,
    ) {
        val isEmpty: Boolean get() = hits.isEmpty()
    }

    /**
     * Pure window filter over raw schedule triples.
     *
     * @param targetEpochDay IST epoch-day of the searched instant
     * @param targetMinuteOfDay minute-of-day (0..1439) of the searched instant
     * @return one [TrainHit] per run, nearest-in-time first, ties broken by boarding order
     */
    fun runsBetween(
        pairs: List<PairStop>,
        targetEpochDay: Long,
        targetMinuteOfDay: Int,
        fromCode: String,
        toCode: String,
        limit: Int = 400,
    ): Pair<List<TrainHit>, Skips> {
        val target = targetEpochDay * RailTime.MINUTES_PER_DAY + targetMinuteOfDay
        val hits = ArrayList<TrainHit>(pairs.size)
        var skippedWindow = 0
        var skippedNotRunning = 0
        val notRunningNames = ArrayList<String>(3)

        for (p in pairs) {
            if (p.alightMinute <= p.boardMinute || p.boardMinute < 0) continue
            // Origin date that puts this train's boarding on the target calendar day …
            val baseOrigin = targetEpochDay -
                Math.floorDiv(p.boardMinute.toLong(), RailTime.MINUTES_PER_DAY.toLong())
            // … and its neighbours, which is exactly what the ±24 h spill needs: a run that boarded
            // at 23:50 yesterday is a legitimate answer to a 00:10-today search.
            var best: TrainHit? = null
            var bestScore = Long.MAX_VALUE
            for (delta in -1L..1L) {
                val origin = baseOrigin + delta
                val boardEpoch = origin * RailTime.MINUTES_PER_DAY + p.boardMinute
                if (boardEpoch < target - WINDOW_MINUTES || boardEpoch > target + WINDOW_MINUTES) {
                    skippedWindow++
                    continue
                }
                val weekday = RailTime.istWeekdayOfEpochDay(origin)
                if (!RailTime.runsOn(p.runDays, weekday)) {
                    skippedNotRunning++
                    // One mention per train: the hint is shown as prose, and "12951, 12951, 12951 run
                    // only on Mon" is what counting each candidate day separately produced.
                    if (notRunningNames.size < 3 && p.trainNo !in notRunningNames) {
                        notRunningNames += p.trainNo
                    }
                    continue
                }
                // The closest run wins, and on an exact tie (a train that leaves at the searched minute
                // *or* the same minute tomorrow) the one still to come wins: a boardable train beats one
                // that left sixty seconds ago, and the day chips and the live screen both key off this
                // origin date, so picking the wrong one is visible in two places at once.
                val raw = kotlin.math.abs(boardEpoch - target)
                val score = if (boardEpoch >= target) raw * 2 else raw * 2 + 1
                if (score < bestScore) {
                    bestScore = score
                    best = TrainHit(
                        trainNo = p.trainNo, trainName = p.trainName, trainType = p.trainType,
                        originDateEpochDay = origin, boardMinute = p.boardMinute,
                        alightMinute = p.alightMinute, fromCode = fromCode, toCode = toCode,
                        fromSeq = p.fromSeq, toSeq = p.toSeq,
                        distanceKm = (p.toKm - p.fromKm).coerceAtLeast(0), runDays = p.runDays,
                    )
                }
            }
            best?.let { hits += it }
        }

        // Deduplicate by train number: a long-distance train can satisfy the window for two
        // consecutive origin dates, and showing "12951" twice in a 48 h search is a bug, not a detail.
        val best = LinkedHashMap<String, TrainHit>()
        val ordered = hits.sortedWith(
            compareBy<TrainHit> { kotlin.math.abs(it.boardEpochMinute - target).toLong() }
                .thenBy { it.boardMinute }
                .thenBy { it.trainNo }
        )
        for (h in ordered) {
            if (best.size >= limit) break
            best.putIfAbsent(h.trainNo, h)
        }
        // Two different questions, and the list above answers only the first. Choosing *which instance of a
        // train number* to show is proximity - "the run that left a minute ago is still tonight's answer" is
        // a rule with a test of its own, and it stays. Choosing the *order the passenger reads* is intent:
        // sorting the display by the same proximity is what put a train that departed 41 minutes ago above
        // one leaving in nine, in a screenshot, on the screen whose whole job is "what can I still catch".
        val display = best.values.sortedWith(
            compareByDescending<TrainHit> { it.boardEpochMinute >= target }
                .thenBy { kotlin.math.abs(it.boardEpochMinute - target) }
                .thenBy { it.boardMinute }
                .thenBy { it.trainNo }
        )
        return display to Skips(skippedWindow, skippedNotRunning, notRunningNames.toList())
    }

    data class Skips(val outOfWindow: Int, val wrongDate: Int, val wrongDateTrains: List<String>)

    /**
     * End-to-end search used by the UI: resolve both fields, run the window filter, and produce a
     * [Report] that always has something useful to say. A blank list with no explanation is the
     * most common complaint against this class of app, so the reason codes are first-class output.
     */
    fun search(
        queries: RefQueries,
        fromText: String,
        toText: String,
        targetEpochDay: Long,
        targetMinuteOfDay: Int,
        restrictToTrainNo: String? = null,
    ): Report {
        val t0 = System.currentTimeMillis()
        val from = queries.resolveStations(fromText).firstOrNull()
        val to = queries.resolveStations(toText).firstOrNull()
        if (from == null || to == null) {
            return Report(from, to, emptyList(), Reason.STATION_NOT_FOUND,
                queryMillis = System.currentTimeMillis() - t0)
        }
        if (from.code == to.code) {
            return Report(from, to, emptyList(), Reason.SAME_STATION,
                queryMillis = System.currentTimeMillis() - t0)
        }
        val pairs = queries.pairStops(from.code, to.code)
        if (pairs.isEmpty()) {
            return Report(from, to, emptyList(), Reason.NO_DIRECT_TRAINS,
                queryMillis = System.currentTimeMillis() - t0)
        }
        val filtered = if (restrictToTrainNo.isNullOrBlank()) pairs
        else pairs.filter { it.trainNo == restrictToTrainNo.trim() }
        val (hits, skips) = runsBetween(
            filtered, targetEpochDay, targetMinuteOfDay, from.code, to.code
        )
        val hint = if (hits.isEmpty() && skips.wrongDate > 0) runningDaysHint(queries, skips) else null
        return Report(
            from = from, to = to, hits = hits,
            reason = if (hits.isEmpty()) Reason.WRONG_DATE else Reason.OK,
            notRunningOnDate = skips.wrongDate, notRunningHint = hint,
            queryMillis = System.currentTimeMillis() - t0,
        )
    }

    /** "12951 runs only on Sun, Tue, Thu" — the difference between a bug report and a shrug. */
    private fun runningDaysHint(queries: RefQueries, skips: Skips): String? {
        val first = skips.wrongDateTrains.firstOrNull() ?: return null
        val summary = queries.findTrains(first).firstOrNull() ?: return null
        val days = summary.runDays ?: return null
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val on = names.filterIndexed { i, _ -> (days shr ((i + 1) % 7)) and 1 == 1 }
        if (on.size == 7 || on.isEmpty()) return null
        return "$first runs only on ${on.joinToString(", ")}"
    }

    /**
     * Days around the target on which the pair actually has a train, for the date chips under the
     * search fields. Computed from the same schedule rows, so it cannot disagree with the list.
     */
    fun availability(pairs: List<PairStop>, centreEpochDay: Long, back: Int, forward: Int): List<DayCount> {
        val acc = HashMap<Long, Int>()
        for (day in centreEpochDay - back..centreEpochDay + forward) {
            // Midday probe: a 00:00 probe would drop every train that boards in the evening, which
            // would make the date chips disagree with the list the user sees.
            val (hits, _) = runsBetween(pairs, day, 12 * 60, "", "")
            if (hits.isNotEmpty()) acc[day] = hits.size
        }
        return (centreEpochDay - back..centreEpochDay + forward).map { DayCount(it, acc[it] ?: 0) }
    }

    data class DayCount(val epochDay: Long, val trains: Int)
}
