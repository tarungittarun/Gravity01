package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime

/** A static distance BETWEEN fixed map stations, never remaining kilometres to a moving train.
 * An event minute dates only the last observed station. Refreshing a page cannot redate it. */
object MarkerDistanceLabel {
    fun next(name: String?, routeGapKm: Int?, eventMinute: Int?, stale: Boolean): String {
        val place = name?.let { RowText.of(it) } ?: "next station"
        val distance = routeGapKm?.takeIf { it > 0 }?.let { "$it km route gap to $place" }
            ?: "Next: $place · distance unavailable"
        val event = eventMinute?.let { " · as on ${RailTime.formatRailMinuteBare(it)}" }.orEmpty()
        return (if (stale) "Last stop reported$event · $distance"
            else "$distance$event") + " · position unknown"
    }
}
