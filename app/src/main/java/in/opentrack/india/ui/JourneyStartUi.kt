package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime

/** Timetable-only notice; a future scheduled departure is NOT proof of actual train state. */
object JourneyStartUi {
    fun scheduledStart(originDay: Long, departureMinute: Int?, nowMillis: Long,
                       hasAnyReportedStop: Boolean): Int? {
        if (hasAnyReportedStop || departureMinute == null ||
            originDay < RailTime.istEpochDay(nowMillis)) return null
        val departureAt = RailTime.railMinuteToEpochMillis(originDay, departureMinute)
        return departureMinute.takeIf { departureAt > nowMillis }
    }
}
