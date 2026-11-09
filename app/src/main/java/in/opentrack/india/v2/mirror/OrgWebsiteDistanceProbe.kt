package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.model.TrainRoute
import java.util.Locale

/** R139 POC: inspect the SAME .org.in HTML already fetched for website past-card times.
 * No extra request and no train-position claim. A static route km is NOT a website live km.
 * Only an explicit visible 'N km to CODE' can be considered, and only after the website
 * report has passed the existing train/run/page-clock gates. Even a match is diagnostic
 * until an actual same-site capture demonstrates that the label tracks a moving train. */
object OrgWebsiteDistanceProbe {
    data class Candidate(val fromCode: String, val nextCode: String, val remainingKm: Double,
                         val legKm: Double, val fraction: Double)
    data class Finding(val candidate: Candidate?, val reason: String)

    private val distance = Regex("(?i)(?<![\\w.\\-])(\\d{1,3}(?:\\.\\d{1,2})?)\\s*km\\s+to\\s+([A-Z][A-Z0-9]{1,5})\\b")

    fun inspect(body: String, report: OrgWebsiteStatus.Report?, route: TrainRoute,
                mapRoute: TrainRoute?): Finding {
        if (report == null) return Finding(null, "no run-linked past-card report; no distance admitted")
        // SEO scripts can mention a completely different train, destination or route.
        val visible = body.replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?s)<[^>]*>"), " ").replace(Regex("\\s+"), " ")
            .take(120_000)
        val matches = distance.findAll(visible).toList()
        if (matches.isEmpty()) return Finding(null,
            "no explicit km-to-next-station label in the visible website reply")
        if (matches.size != 1) return Finding(null, "ambiguous website distance labels")
        val match = matches[0]
        val claimedNext = match.groupValues[2].uppercase(Locale.ROOT)
        // Both km values MUST come from the same validated route axis. Map intermediates
        // can have a different absolute km axis than the official halt timetable.
        val axis = mapRoute?.takeIf { RouteBetween.withVerifiedHalts(it, route) != null }
            ?: route
        val i = axis.stops.indexOfFirst { it.code.equals(report.anchorCode, true) }
        val from = axis.stops.getOrNull(i)
        val to = axis.stops.getOrNull(i + 1)
        if (i < 0 || from == null || to == null ||
            !to.code.equals(claimedNext, true) || from.schedKm < 0 ||
            to.schedKm <= from.schedKm) return Finding(null,
            "website next code is not the immediate next point on a validated km axis")
        val remaining = match.groupValues[1].toDoubleOrNull()
            ?: return Finding(null, "invalid website distance number")
        val leg = (to.schedKm - from.schedKm).toDouble()
        if (remaining !in 0.0..leg) return Finding(null,
            "website remaining km exceeds the mapped segment; no location")
        return Finding(Candidate(from.code, to.code, remaining, leg,
            (leg - remaining) / leg),
            "explicit visible same-website distance claim; unverified, not a live GPS fix")
    }
}
