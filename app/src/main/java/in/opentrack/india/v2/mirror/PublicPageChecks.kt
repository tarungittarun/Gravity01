package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.security.MessageDigest

/** Manual public-source comparisons. A freshly published station claim is NOT an observed
 * station event, a departure minute or GPS. The caller must keep these distinctions on screen. */
object PublicPageChecks {
    const val RAIL_YATRI = "RailYatri"
    const val IXIGO = "ixigo page"
    const val ORG = "whereismytrain.org.in (website)"
    val names = listOf(RAIL_YATRI, IXIGO, ORG)

    data class StationClaim(val code: String, val status: String, val updateAt: Long, val upstream: String)
    data class Result(
        val detail: String, val checkedAt: Long, val pageAnswered: Boolean,
        val stationClaim: StationClaim? = null,
        /** The other website's labelled, past-card station claims. Not independently verified Act. */
        val orgReport: OrgWebsiteStatus.Report? = null,
        /** A later page card that was excluded from Actual/position, with its reason. */
        val orgQualificationNote: String? = null,
        /** Page excerpts for comparison only. Never feed these into Act or position. */
        val lines: Map<String, String> = emptyMap(),
        /** The website's printed publication label; never a station-event timestamp. */
        val pageUpdateLabel: String? = null,
        /** Transport evidence, not event freshness. A short digest compares repeated bodies
         * without persisting HTML or cookies in the diagnostic diary. */
        val responseBytes: Int = 0,
        val responseDigest: String? = null,
        val fromHttpCache: Boolean = false,
        /** POC only: inspection of the SAME .org.in page for a visible next-point km label.
         * Diagnostic, never an admitted position or a second provider request. */
        val orgDistanceProbe: String? = null,
    )

    /** Explain a missing destination in a returned website timeline without treating its
     * publication clock (or any comparison excerpt) as a train arrival/position. */
    fun websiteGap(result: Result?, destination: String): String? {
        if (result?.pageAnswered != true || result.orgReport != null ||
            result.lines.isEmpty() || result.lines.keys.any { it.equals(destination, true) }) return null
        val lastDisplayed = result.lines.keys.last()
        val update = result.pageUpdateLabel?.let { "Page updated $it; " }.orEmpty()
        return "${update}website has no $destination arrival row. Last displayed: $lastDisplayed " +
            "(comparison only); position unknown. This is not the phone app."
    }

    fun url(name: String, number: String, originDay: Long, now: Long): String? {
        if (!number.matches(Regex("[0-9]{5}"))) return null
        return when (name) {
            RAIL_YATRI -> {
                val offset = RailTime.istEpochDay(now) - originDay
                if (offset !in 0L..2L) null else
                    "https://livestatus.railyatri.in/api/v3/train_eta_data/$number/$offset.json" +
                        "?start_day=$offset&user_id=-1&device_type_id=4&change_name=seo_train_name" +
                        "&lat=&lng=&claim_on_train=false"
            }
            IXIGO -> "https://www.ixigo.com/trains/$number/running-status"
            ORG -> "https://whereismytrain.org.in/?trainNo=$number"
            else -> null
        }
    }

    /** One request per explicit website check. A refusal is never a cue to retry; the
     * transport's separate IPv4 recovery still applies to a broken socket, not HTTP 403. */
    internal fun retriesFor(name: String): Int = 0

    /** Identify as this app, not a Chrome session we do not have. Ordinary HTTP caching is
     * allowed; a page publication clock is still never an observed train-event clock. */
    internal fun headersFor(name: String): Map<String, String> = when (name) {
        // Standard English content negotiation for the site's English station
        // labels; this remains an honest TrackTrain GET, not a browser identity.
        ORG -> mapOf("Accept" to "text/html", "Accept-Language" to "en-IN,en;q=0.8")
        else -> mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) Chrome/120 Safari/537.36",
            "Accept" to if (name == RAIL_YATRI) "application/json" else "text/html",
            "Cache-Control" to "no-cache", "Pragma" to "no-cache")
    }

    fun fetch(http: Http, name: String, number: String, originDay: Long, now: Long,
              source: String? = null, destination: String? = null,
              knownCodes: Set<String> = emptySet(), route: TrainRoute? = null,
              mapRoute: TrainRoute? = null): Result =
        fetch(name, number, originDay, now, source, destination, knownCodes, route, mapRoute) { url ->
            http.get(url, timeoutMillis = 4_000, retries = retriesFor(name), budgetMillis = 4_000,
                headers = headersFor(name), allowCachedOnFailure = name != ORG,
                followRedirects = name != ORG)
        }

    /** Transport seam: a source choice always requests its own URL. An HTTP fallback cache may
     * show an old comparison, but cannot produce a fresh station claim. */
    internal fun fetch(name: String, number: String, originDay: Long, now: Long,
        source: String? = null, destination: String? = null,
        knownCodes: Set<String> = emptySet(), route: TrainRoute? = null,
        mapRoute: TrainRoute? = null, get: (String) -> Http.Result): Result {
        val url = url(name, number, originDay, now)
            ?: return Result("Run outside the available 0–2 day query window; no request made.", now, false)
        return when (val reply = get(url)) {
            is Http.Result.Failed -> Result("Request failed: ${reply.kind} · ${reply.message.take(90)}; no position.",
                System.currentTimeMillis(), false)
            is Http.Result.Ok -> {
                val checked = System.currentTimeMillis()
                val bytes = reply.body.toByteArray(Charsets.UTF_8)
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).take(8)
                    .joinToString("") { "%02x".format(it) }
                val answer = parse(name, reply.body, number, originDay, checked, source, destination,
                    knownCodes, route, mapRoute).copy(responseBytes = bytes.size,
                        responseDigest = digest, fromHttpCache = reply.fromCache)
                if (reply.fromCache) answer.copy(
                    detail = "Cached HTTP body (${reply.ageMillis / 60_000} min old); " +
                        answer.detail + " No fresh observation.", pageAnswered = false,
                    stationClaim = null, orgReport = null, orgQualificationNote = null,
                    orgDistanceProbe = "cached HTTP page; no same-page distance accepted")
                else answer
            }
        }
    }

    fun parse(name: String, body: String, number: String, originDay: Long, now: Long,
              source: String? = null, destination: String? = null,
              knownCodes: Set<String> = emptySet(), route: TrainRoute? = null,
              mapRoute: TrainRoute? = null): Result = when (name) {
        RAIL_YATRI -> railYatri(body, number, originDay, now, source, destination, knownCodes)
        IXIGO, ORG -> page(name, body, number, originDay, now, knownCodes, route, mapRoute)
        else -> Result("Unknown check; no position.", now, false)
    }

    private fun railYatri(body: String, number: String, originDay: Long, now: Long,
                          source: String?, destination: String?, knownCodes: Set<String>): Result {
        val doc = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result("RailYatri: response is not a status object; no position.", now, false)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(originDay * 86_400_000L))
        val returnedNumber = doc.optString("train_number")
        val returnedDate = doc.optString("train_start_date")
        val upstream = doc.optString("data_from").ifBlank { "unspecified" }
        val code = doc.optString("current_station_code").uppercase(Locale.ROOT)
        val status = when (doc.optString("status").uppercase(Locale.ROOT)) {
            "D" -> "departed"
            "A" -> "arrived"
            else -> doc.optString("status").take(18).ifBlank { "not supplied" }
        }
        val stamp = doc.optString("update_time")
        val stampMillis = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ENGLISH).apply { isLenient = false }
                .parse(stamp)?.time
        }.getOrNull()
        val age = stampMillis?.let { now - it }
        val identity = "returned $returnedNumber / origin $returnedDate (requested $number / $date)"
        val clock = if (stamp.isBlank()) "no update clock" else "update clock $stamp (NOT a station event time)"
        val claim = if (code.matches(Regex("[A-Z0-9]{1,6}"))) "$code $status" else "no station claim"
        val sameRoute = source != null && destination != null &&
            doc.optString("source").equals(source, true) &&
            doc.optString("destination").equals(destination, true)
        val verdict = when {
            !doc.optBoolean("success") -> "provider reports failure"
            returnedNumber != number || returnedDate != date -> "wrong train/origin run"
            age == null || age !in 0L..300_000L -> "clock absent, future or >5 min old"
            !sameRoute -> "route not verified against selected timetable"
            code !in knownCodes || status !in setOf("arrived", "departed") -> "no route-matched station/status claim"
            else -> "same-run station claim, not an observed event"
        }
        val stationClaim = if (verdict.startsWith("same-run"))
            StationClaim(code, status, stampMillis!!, upstream) else null
        return Result("RailYatri: $identity · upstream $upstream · root claims $claim · $clock; $verdict. " +
            "ETA/ETD and timetable rows are not Act; not onboard GPS.", now, true, stationClaim)
    }

    private fun page(name: String, body: String, number: String, originDay: Long, now: Long,
                     knownCodes: Set<String>, route: TrainRoute?, mapRoute: TrainRoute?): Result {
        // Only visible text. SEO/Next.js scripts can contain several runs and future ETA rows.
        val visible = RunningStatusMirror.stripTags(body
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " "))
            .replace(Regex("\\s+"), " ").trim().take(120_000)
        val title = if (name == IXIGO) "ixigo" else "whereismytrain.org.in"
        if (number !in body) return Result("$title: returned page did not identify train $number; no position.",
            now, false)
        val date = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(originDay * 86_400_000L))
        val selected = Regex("(?i)\\b(?:Today|Selected date|Running status for)\\s*[:–-]?\\s*" +
            "(\\d{1,2}[-/]\\d{1,2}[-/]\\d{4})").find(visible)?.groupValues?.get(1)
        val status = when {
            Regex("(?i)\\b(?:yet to start|hasn.t started)\\b").containsMatchIn(visible) -> "yet to start"
            Regex("(?i)\\b(?:reached destination|run complete)\\b").containsMatchIn(visible) -> "reached destination"
            else -> "no unambiguous run status in visible page"
        }
        val updated = Regex("(?i)\\bLast\\s+Updated\\s*:\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM)?)")
            .find(visible)?.groupValues?.get(1)?.trim()
        // The .org.in page actually returned a 29-stop timeline on the owner's phone. Show
        // what it printed beside static rows; do not call those clock tokens observed events.
        val timeline = if (name == ORG) visible.substringAfter("Journey Timeline", visible) else visible
        val matches = Regex("\\(([A-Z0-9]{1,6})\\)").findAll(timeline).take(300).toList()
        val lines = linkedMapOf<String, String>()
        for ((i, m) in matches.withIndex()) {
            val code = m.groupValues[1]
            if (code !in knownCodes || code in lines) continue
            val end = matches.getOrNull(i + 1)?.range?.first ?: timeline.length
            val excerpt = timeline.substring(m.range.last + 1, end.coerceAtMost(m.range.last + 141))
                .replace(Regex("\\s+"), " ").trim()
            if (!Regex("(?i)\\b(?:ARRIVAL|DEPARTURE)\\b").containsMatchIn(excerpt)) continue
            lines[code] = "Page shows $code: ${excerpt.take(115)} · NOT dated Act"
        }
        val returned = selected?.let { "page-selected $it" } ?: "page does not identify a selected origin date"
        val wrong = selected != null && selected.replace('/', '-') != date
        val org = if (name == ORG && route != null && !wrong)
            OrgWebsiteStatus.parse(body, number, originDay, now, route, mapRoute) else null
        val distanceProbe = if (name == ORG && route != null)
            OrgWebsiteDistanceProbe.inspect(body, org?.report, route, mapRoute) else null
        val distanceNote = distanceProbe?.let { finding ->
            finding.candidate?.let { p ->
                "site printed ${p.remainingKm} km to ${p.nextCode} after ${p.fromCode}; " +
                    "mapped ${p.legKm} km segment, ${(p.fraction * 100).toInt()}% schematic; " +
                    "unverified candidate, NOT shown as live position"
            } ?: finding.reason
        }
        val runLabel = if (org?.report != null && selected == null)
            "site has no date selector; mapped to the active selected run by timetable" else returned
        return Result("$title: train $number · $runLabel (requested origin $date) · $status" +
            (updated?.let { " · page update label $it" } ?: "") +
            "; " + (org?.reason ?: if (wrong) "wrong origin run" else "no event-level dated observation") +
            (org?.qualificationNote?.let { " · Unqualified later card: $it" } ?: "") +
            ". ${lines.size} displayed station excerpts for comparison" +
            (if (org?.report != null) "; source-marked past rows are website claims, NOT independently verified Act/GPS."
                else "; NOT Act or measured position.") +
            (if (name == ORG) " not Google's WIMT phone app." else "") +
            (distanceNote?.let { " Same-page distance probe: $it; no extra request." } ?: ""),
            now, true, orgReport = org?.report,
            orgQualificationNote = org?.qualificationNote, lines = lines,
            pageUpdateLabel = if (name == ORG) updated else null,
            orgDistanceProbe = distanceNote)
    }
}
