package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate
import java.util.Locale

/** Green is an independently attributed stop report; amber is a usable but
 * unverified website claim. A successful HTTP request alone is neither. */
object SourceIndicator {
    enum class Tone { READY, CANDIDATE, UNAVAILABLE, PENDING, OLD }
    data class Indicator(val tone: Tone, val label: String)

    fun of(name: String, state: String, detail: String): Indicator = when {
        state == "live" -> Indicator(Tone.READY, "reported times")
        state == "candidate" && name == PublicPageChecks.ORG ->
            Indicator(Tone.CANDIDATE, "site stops · unverified")
        state == "candidate" && name == WimtCandidate.SOURCE ->
            Indicator(Tone.CANDIDATE, "site distance · not GPS")
        state == "candidate" -> Indicator(Tone.CANDIDATE, "unverified station claim")
        state == "comparison" && name == PublicPageChecks.ORG ->
            Indicator(Tone.CANDIDATE, "site past-card times · run date inferred")
        state == "comparison" -> Indicator(Tone.CANDIDATE, "comparison only · not ACTUAL")
        state == "last-known" -> Indicator(Tone.OLD, "saved · not new")
        detail.contains("not answered yet") || detail.contains("checking on first load") ||
            detail.contains("manual check available") -> Indicator(Tone.PENDING, "checking…")
        detail.contains("HTTP 403") -> Indicator(Tone.UNAVAILABLE, "site blocks request")
        detail.contains("Request failed", true) || detail.contains("timed out", true) ->
            Indicator(Tone.UNAVAILABLE, "connection failed")
        detail.contains("run day mismatched") -> Indicator(Tone.UNAVAILABLE, "wrong run day")
        detail.contains("not unambiguously active") -> Indicator(Tone.UNAVAILABLE, "run day unclear")
        detail.contains("update clock missing or over five minutes old") ->
            Indicator(Tone.UNAVAILABLE, "site update too old")
        detail.contains("endpoints do not match") -> Indicator(Tone.UNAVAILABLE, "wrong train or route")
        detail.contains("run is not active") -> Indicator(Tone.UNAVAILABLE, "train not running")
        else -> Indicator(Tone.UNAVAILABLE, "no usable stop report")
    }

    /** A short elapsed seconds figure, not the age of the website's last stop or page clock. */
    fun seconds(detail: String): String? = Regex("checked in (\\d+) ms").find(detail)
        ?.groupValues?.get(1)?.toLongOrNull()
        ?.let { String.format(Locale.ROOT, "%.1f s", it / 1000.0) }

    fun priority(tone: Tone): Int = when (tone) {
        Tone.READY -> 0
        Tone.CANDIDATE -> 1
        Tone.OLD -> 2
        Tone.PENDING -> 3
        Tone.UNAVAILABLE -> 4
    }
}
