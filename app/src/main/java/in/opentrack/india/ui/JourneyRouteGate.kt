package `in`.opentrack.india.ui

/** A number alone is not a route identity: offline editions can assign the
 * same number to another origin run. A provisional route must contain the
 * selected boarding and alighting stops in the passenger's direction. */
object JourneyRouteGate {
    fun covers(codes: List<String>, from: String, to: String): Boolean {
        if (from.isBlank() || to.isBlank()) return true // cannot disprove an unspecified end
        val start = codes.indexOfFirst { it.equals(from, true) }
        val end = codes.indexOfLast { it.equals(to, true) }
        return start >= 0 && end > start
    }
}
