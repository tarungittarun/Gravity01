package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime

/** UI-only formatting. The underlying minutes retain their run-day offset for all gates and TalkBack. */
object RouteClockUi {
    fun clock(minute: Int?): String = minute?.let(RailTime::formatRailMinuteBare) ?: "—"

    /** One day marker under each day's departure, not `(+1d)` beside the clock.
     * At the final stop the arrival is the only clock; never display Day 1. */
    fun departureDay(departure: Int?, arrival: Int?): String? {
        val day = (departure ?: arrival)?.let(RailTime::dayOffsetOf) ?: return null
        return if (day > 0) "day${day + 1}" else null
    }

    /** Historical helper for detecting the first rollover row (not for row labels). */
    fun dayMarker(firstMinute: Int?, previousMinute: Int?): String? {
        val day = firstMinute?.let(RailTime::dayOffsetOf) ?: return null
        val prior = previousMinute?.let(RailTime::dayOffsetOf) ?: -1
        return if (day > 0 && day != prior) "day${day + 1}" else null
    }
}
