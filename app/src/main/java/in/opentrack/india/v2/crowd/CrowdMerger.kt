package `in`.opentrack.india.v2.crowd

/**
 * Turns raw traveller reports into one honest line per stop: the median observed minute, how many
 * reports agree, and how fresh the newest is. The median, not the mean - one liar cannot move it,
 * which is ADR-006's abuse guard in arithmetic form.
 *
 * The freshness window is the honesty contract with the reader: a report older than [WINDOW_MILLIS]
 * is not "data about now" and is dropped; a stop with zero fresh reports renders as "no traveller
 * reports yet" - never as the timetable dressed up, and never as a blank.
 */
object CrowdMerger {

    data class StopActual(
        val stationCode: String,
        val kind: StopReport.Kind,
        val medianRailMinute: Int,
        val reports: Int,
        val newestAgeMillis: Long,
    )

    data class Merged(
        val stops: List<StopActual>,
        val droppedStale: Int,
    )

    fun merge(reports: List<StopReport>, nowMillis: Long): Merged {
        val fresh = reports.filter { nowMillis - it.reportedAtMillis in 0..WINDOW_MILLIS }
        val stale = reports.size - fresh.size
        val grouped = fresh.groupBy { it.stationCode to it.kind }
        val stops = grouped.mapNotNull { (key, group) ->
            val (station, kind) = key
            val minutes = group.map { it.railMinute }.sorted()
            val median = minutes[minutes.size / 2]
            val newest = group.maxOf { it.reportedAtMillis }
            StopActual(
                stationCode = station,
                kind = kind,
                medianRailMinute = median,
                reports = group.size,
                newestAgeMillis = nowMillis - newest,
            )
        }.sortedWith(compareBy({ it.stationCode }, { it.kind }))
        return Merged(stops, droppedStale = stale)
    }

    /**
     * The sentence the screen renders, verbatim - so the wording is tested, not improvised in a
     * layout. "Traveller" names the source; the count and age name its weight; zero is a fact.
     */
    fun sentence(actual: StopActual?, stationCode: String): String = when (actual) {
        null -> "no traveller reports for $stationCode yet"
        else -> {
            val kindWord = if (actual.kind == StopReport.Kind.DEPARTED) "departed" else "arrived"
            val ageMin = actual.newestAgeMillis / 60_000
            "$stationCode $kindWord ${actual.medianRailMinute / 60}:" +
                "%02d".format(actual.medianRailMinute % 60) +
                " — reported by travellers (${actual.reports} report" +
                (if (actual.reports == 1) "" else "s") + ", $ageMin min ago)"
        }
    }

    /**
     * One line per stop of the route being shown, so the reader column can carry a sentence even where
     * nobody has reported: "departed" is preferred over "arrived" (the stronger fact), and a stop with
     * nothing fresh renders [sentence]'s null form - never blank, never the timetable dressed up.
     */
    fun lines(merged: Merged, stationCodes: List<String>): Map<String, String> {
        val byCode = merged.stops.groupBy { it.stationCode }
        val out = LinkedHashMap<String, String>()
        for (code in stationCodes) {
            val actuals = byCode[code.uppercase()].orEmpty()
            val chosen = actuals.firstOrNull { it.kind == StopReport.Kind.DEPARTED }
                ?: actuals.firstOrNull { it.kind == StopReport.Kind.ARRIVED }
            out[code] = sentence(chosen, code)
        }
        return out
    }

    /** A report older than this is history, not a live actual. */
    const val WINDOW_MILLIS: Long = 15 * 60_000L
}
