package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.RailTime
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Manual, read-only look at an UNDOCUMENTED Where is My Train web-cache response. These are
 * unverified historical notes. A strictly dated web point may be shown as a separate,
 * explicitly uncorroborated *candidate*, never as certified Act, phone-app GPS or an ETA. In the captured
 * 20689 response even future rows had fields named actual_* with source=eta; a departed row's
 * updated_at could precede its claimed departure. Both must be excluded separately.
 *
 * Do not silently promote this experimental endpoint into the background refresh path.
 */
object WimtCandidate {
    const val SOURCE = "WIMT web cache (candidate)"
    /** Only a claimed web-cache location. The field is not a GPS/cell measurement and its root
     * timestamp is a cache timestamp, NOT the time the train passed this point. */
    data class WebPoint(
        val code: String, val routeKm: Double, val eventAtMillis: Long,
        val cacheAtMillis: Long, val precedingHalt: String, val followingHalt: String,
    )
    data class Result(val lines: Map<String, String>, val detail: String,
                      val webPoint: WebPoint? = null)

    fun fetch(
        number: String, epochDay: Long, from: String, to: String,
        knownCodes: Set<String>, nowMillis: Long = System.currentTimeMillis(),
        knownKms: Map<String, Int> = emptyMap(),
        get: (String) -> Pair<String?, String> = ::getOnce,
    ): Result {
        if (!number.matches(Regex("[0-9]{5}")) ||
            !from.matches(Regex("[A-Z]{2,5}")) || !to.matches(Regex("[A-Z]{2,5}")))
            return Result(emptyMap(), "invalid train or route identity")
        val day = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(epochDay * 86_400_000L))
        // This is an undocumented public web-cache comparison, not a genuine phone-app
        // session. Do not pass copied appVersion/sb_version identifiers, or an assumed
        // from_day; ask only by run date and verify the returned route independently.
        val parameters = mapOf("date" to day, "train_no" to number, "lang" to "en")
        val url = "https://whereismytrain.in/cache/live_status?" + parameters.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val (body, why) = get(url)
        if (body == null) return Result(emptyMap(), "no response ($why)")
        return parse(body, epochDay, from, to, nowMillis, knownCodes, knownKms)
    }

    fun parse(body: String, epochDay: Long, from: String, to: String, nowMillis: Long,
              knownCodes: Set<String> = emptySet(), knownKms: Map<String, Int> = emptyMap()): Result {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result(emptyMap(), "unreadable JSON; no minutes accepted")
        val expectedDate = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(epochDay * 86_400_000L))
        if (root.optString("start_date") != expectedDate ||
            !root.optString("source_station").equals(from, true) ||
            !root.optString("destination_station").equals(to, true))
            return Result(emptyMap(), "run date or route mismatched; no minutes accepted")
        val rows = root.optJSONArray("days_schedule")
            ?: return Result(emptyMap(), "no dated station rows")
        val lines = linkedMapOf<String, String>()
        var rejectedEta = 0
        var rejectedAhead = 0
        var latestObservation = 0L
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val code = row.optString("station_code").uppercase(Locale.ENGLISH)
            if (!code.matches(Regex("[A-Z]{2,5}")) || !seen.add(code) ||
                (knownCodes.isNotEmpty() && code !in knownCodes)) continue
            if (row.optString("source").equals("eta", true)) {
                rejectedEta++
                continue
            }
            // A location or a time string isn't an event. Demand the source's departed flag
            // AND a distinct update timestamp that is later than each proposed event.
            if (row.opt("departed") != true) continue
            val updated = updatedMillis(row.optString("updated_at")) ?: continue
            if (updated > nowMillis + 120_000L || updated < epochDay * 86_400_000L -
                RailTime.IST_OFFSET_MILLIS) continue
            fun event(key: String): String? {
                val seconds = row.optLong(key, 0L)
                val millis = seconds * 1000L
                if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000L ||
                    RailTime.istEpochDay(millis) !in epochDay..(epochDay + 3) ||
                    millis > nowMillis + 120_000L || millis > updated + 30_000L) {
                    if (seconds > 0) rejectedAhead++
                    return null
                }
                latestObservation = maxOf(latestObservation, updated)
                val minute = RailTime.istMinuteOfDay(millis)
                return RailTime.formatRailMinuteBare(minute)
            }
            val arr = event("actual_arrival_tm")
            val rawArr = row.optLong("actual_arrival_tm", 0L)
            val rawDep = row.optLong("actual_departure_tm", 0L)
            // The 2 Nov 20689 cache gave BDVT an arrival at 13:35 and an
            // "actual departure" of 12:52 (its schedule). Even on a row marked
            // departed, that cannot be a reported departure after the arrival.
            val dep = if (rawArr > 0 && rawDep > 0 && rawDep < rawArr) {
                rejectedAhead++
                null
            } else event("actual_departure_tm")
            if (arr != null || dep != null) {
                lines[code] = "$code · " + listOfNotNull(arr?.let { "arrival $it" },
                    dep?.let { "departure $it" }).joinToString(" · ") +
                    " · WIMT web note (unverified, not Act)"
            }
        }
        val age = if (latestObservation == 0L) "no admissible observed-time candidates"
            else "last event update ${((nowMillis - latestObservation).coerceAtLeast(0L) / 60_000L)} min ago"
        val webPoint = positionCandidate(root, rows, epochDay, nowMillis, knownKms)
        val pointNote = if (webPoint == null) "no fresh, route-bracketed web position event"
            else "web point ${webPoint.code} at ${RailTime.formatClock(webPoint.eventAtMillis)} IST " +
                "(${(nowMillis - webPoint.eventAtMillis) / 60_000L} min old); not GPS or the WIMT phone app"
        return Result(lines, "${rows.length()} rows; ${lines.size} candidate stops; " +
            "$rejectedEta ETA rows and $rejectedAhead contradictory/pre-update/future cells excluded; $age. " +
            "$pointNote. Undocumented web cache; no future ETA inferred", webPoint)
    }

    /** A root timestamp is minted on every HTTP answer. Only the dated CURRENT point's
     * own event minute may make a position candidate, and it must be between two halts
     * whose order and distances the separately checked static schedule confirms. The
     * web point remains a claim by this cache, not verified cell/GPS or a stop actual. */
    private fun positionCandidate(root: JSONObject, rows: org.json.JSONArray, day: Long,
                                  now: Long, knownKms: Map<String, Int>): WebPoint? {
        if (knownKms.size < 2) return null
        val stamp = updatedMillis(root.optString("lastUpdateIsoDate")) ?: return null
        if (now - stamp !in 0L..120_000L) return null
        val code = root.optString("curStn").uppercase(Locale.ENGLISH)
        if (!code.matches(Regex("[A-Z0-9]{1,6}"))) return null
        val km = (root.opt("distance") as? Number)?.toDouble() ?: return null
        if (!(km >= 0.0 && km < 100_000.0)) return null
        val current = (0 until rows.length()).firstOrNull { i ->
            val row = rows.optJSONObject(i) ?: return@firstOrNull false
            row.optString("station_code").equals(code, true) &&
                row.opt("curStn") == true &&
                !row.optString("source").equals("eta", true)
        } ?: return null
        val row = rows.optJSONObject(current) ?: return null
        // An `actual_departure_tm` can be the *scheduled* time even BEFORE the arrival
        // on non-stops (BLKR on 16227). Never use it as the observation clock.
        val eventSeconds = row.optLong("actual_arrival_tm", 0L)
        if (eventSeconds <= 0 || eventSeconds > Long.MAX_VALUE / 1000L) return null
        val event = eventSeconds * 1000L
        val departedAt = row.optLong("actual_departure_tm", 0L)
        // In the observed BLKR capture this field was 48 minutes BEFORE arrival.
        // Even a freshly minted cache clock must not rehabilitate a contradictory row.
        if (departedAt > 0 && (departedAt < eventSeconds ||
                departedAt > (now + 30_000L) / 1000L)) return null
        val updateRaw = row.optString("updated_at")
        if (updateRaw.isNotBlank()) {
            val updated = updatedMillis(updateRaw) ?: return null
            if (event > updated + 30_000L || updated > now + 120_000L) return null
        }
        if (RailTime.istEpochDay(event) !in day..day + 3 || now - event !in 0L..600_000L ||
            stamp + 120_000L < event) return null
        val prev = if ((knownKms[code] ?: -1) >= 0) current else
            (current - 1 downTo 0).firstOrNull { i ->
                (knownKms[rows.optJSONObject(i)?.optString("station_code")
                    ?.uppercase(Locale.ENGLISH) ?: ""] ?: -1) >= 0
            } ?: return null
        val next = (current + 1 until rows.length()).firstOrNull { i ->
            (knownKms[rows.optJSONObject(i)?.optString("station_code")?.uppercase(Locale.ENGLISH) ?: ""] ?: -1) >= 0
        } ?: return null
        val before = rows.optJSONObject(prev)?.optString("station_code")?.uppercase(Locale.ENGLISH) ?: return null
        val after = rows.optJSONObject(next)?.optString("station_code")?.uppercase(Locale.ENGLISH) ?: return null
        val a = knownKms[before] ?: return null
        val b = knownKms[after] ?: return null
        if (a < 0 || b <= a || km !in a.toDouble()..b.toDouble()) return null
        return WebPoint(code, km, event, stamp, before, after)
    }

    private fun updatedMillis(raw: String): Long? {
        if (!raw.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d+[+]05:30"))) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                isLenient = false
            }.parse(raw.take(19))?.time
        }.getOrNull()
    }

    private fun getOnce(url: String): Pair<String?, String> = try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OpenTrack India source research/1.0")
        }
        try {
            if (connection.responseCode != 200) null to "HTTP ${connection.responseCode}"
            else {
                val bytes = connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (out.size() <= 150_000) {
                        val n = stream.read(chunk)
                        if (n < 0) break
                        out.write(chunk, 0, n)
                    }
                    out.toByteArray()
                }
                if (bytes.size > 150_000) null to "response exceeded 150 kB"
                else String(bytes, Charsets.UTF_8) to "ok"
            }
        } finally { connection.disconnect() }
    } catch (e: Exception) {
        null to (e.javaClass.simpleName + ": " + (e.message ?: "").take(90))
    }
}
