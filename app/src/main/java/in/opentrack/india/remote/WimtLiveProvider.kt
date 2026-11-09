package `in`.opentrack.india.remote

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Live provider querying Where Is My Train's active live status API (whereismytrain.in).
 *
 * Provides real-time stop-by-stop actual arrival and departure minutes, delays, platform numbers,
 * current station anchor, and train position along the route.
 */
object WimtLiveProvider {

    const val SOURCE_NAME = "Where Is My Train"

    data class Result(
        val snapshot: LiveSnapshot?,
        val detail: String,
        val stationLines: Map<String, String> = emptyMap(),
        val rawJson: String? = null,
    )

    fun fetch(
        trainNo: String,
        route: TrainRoute,
        originEpochDay: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ): Result {
        if (!trainNo.matches(Regex("[0-9]{4,5}"))) {
            return Result(null, "Invalid train number")
        }
        val dayStr = SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date(originEpochDay * 86_400_000L))

        val url = "https://whereismytrain.in/cache/live_status?train_no=$trainNo&date=$dayStr&lang=en"

        val response = try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 7000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode != 200) {
                    return Result(null, "WIMT server returned HTTP ${conn.responseCode}")
                }
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return Result(null, "Network connection to WIMT failed (${e.javaClass.simpleName})")
        }

        return parse(response, trainNo, route, originEpochDay, nowMillis)
    }

    fun parse(
        body: String,
        trainNo: String,
        route: TrainRoute,
        originEpochDay: Long,
        nowMillis: Long,
    ): Result {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return Result(null, "Invalid JSON from WIMT")
        }

        val rows = root.optJSONArray("days_schedule")
            ?: return Result(null, "No schedule in WIMT response")

        val curStnCode = root.optString("curStn").uppercase(Locale.ENGLISH)
        val updateIso = root.optString("lastUpdateIsoDate")
        val updateMillis = parseIsoMillis(updateIso) ?: nowMillis

        val perStop = mutableMapOf<String, LiveStopFields>()
        val lines = mutableMapOf<String, String>()
        var curStnDeparted = false
        var lastDepartedRouteCode: String? = null
        var lastArrivedRouteCode: String? = null
        var lastDelay = 0

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val code = row.optString("station_code").uppercase(Locale.ENGLISH)
            if (code.isBlank()) continue

            // WIMT marks unreached future halts with source: "eta".
            // Their actual_arrival_time/actual_departure_time fields contain predicted ETAs,
            // NOT observed arrival or departure events.
            val isEta = row.optString("source").equals("eta", ignoreCase = true)
            val departed = row.optBoolean("departed", false)
            val isCurStn = code.equals(curStnCode, true)
            if (isCurStn && departed) {
                curStnDeparted = true
            }

            val stopOnRoute = route.stops.firstOrNull { it.code.equals(code, true) }

            // Arrival and departure times: only parse as actuals if NOT an ETA forecast
            val rawArr = if (!isEta) row.optString("actual_arrival_time").takeIf { it.isNotBlank() } else null
            val rawDep = if (!isEta) row.optString("actual_departure_time").takeIf { it.isNotBlank() } else null

            val actArrHm = RailTime.parseHm(rawArr)
            val actDepHm = RailTime.parseHm(rawDep)

            val arrMin = RailTime.alignClock(actArrHm, stopOnRoute?.arrivalMin)
            val depMin = RailTime.alignClock(actDepHm, stopOnRoute?.departureMin)

            val delayArr = (row.opt("delay_in_arrival") as? Number)?.toInt()
            val delayDep = (row.opt("delay_in_departure") as? Number)?.toInt()

            val pf = row.optString("platform").takeIf { it.isNotBlank() && it != "null" }
                ?: row.optJSONArray("platform_info")?.optJSONObject(0)?.optString("platform_number")

            if (stopOnRoute != null) {
                if (departed || rawDep != null) {
                    lastDepartedRouteCode = code
                    if (delayDep != null) lastDelay = delayDep
                    else if (delayArr != null) lastDelay = delayArr
                } else if (!isEta && rawArr != null) {
                    lastArrivedRouteCode = code
                    if (delayArr != null) lastDelay = delayArr
                }
            }

            val fields = LiveStopFields(
                actualArrivalMin = arrMin,
                actualDepartureMin = depMin,
                delayArrivalMin = delayArr,
                delayDepartureMin = delayDep,
                platform = pf,
                departed = departed,
            )
            perStop[code] = fields

            val lineParts = mutableListOf<String>()
            arrMin?.let { lineParts.add("arr ${RailTime.formatRailMinuteBare(it)}") }
            depMin?.let { lineParts.add("dep ${RailTime.formatRailMinuteBare(it)}") }
            val delayForLine = delayDep ?: delayArr
            delayForLine?.let { if (it != 0) lineParts.add("${if (it > 0) "+$it" else "$it"}m") }
            pf?.let { lineParts.add("PF $it") }
            if (lineParts.isNotEmpty()) {
                lines[code] = "$code · " + lineParts.joinToString(" · ")
            }
        }

        // Anchor determination on commercial route:
        // When curStn is an intermediate crossing or non-commercial halt (not in route.stops),
        // anchor resolves to the preceding commercial halt on route with departed=true.
        val curStnOnRoute = route.stops.firstOrNull { it.code.equals(curStnCode, true) }
        val (anchorStop, isDeparted) = when {
            curStnOnRoute != null -> curStnOnRoute to curStnDeparted
            lastDepartedRouteCode != null -> (route.stops.firstOrNull { it.code.equals(lastDepartedRouteCode, true) }
                ?: route.stops.first()) to true
            lastArrivedRouteCode != null -> (route.stops.firstOrNull { it.code.equals(lastArrivedRouteCode, true) }
                ?: route.stops.first()) to false
            else -> (route.stops.firstOrNull() ?: route.stops.first()) to false
        }

        val anchorCode = anchorStop.code
        val anchorSeq = anchorStop.seq

        val event = when {
            isDeparted -> StopStatus.DEPARTED
            perStop[anchorCode]?.actualArrivalMin != null || curStnCode == anchorCode -> StopStatus.ARRIVED
            else -> StopStatus.UNKNOWN
        }

        val eventMin = if (isDeparted) {
            perStop[anchorCode]?.actualDepartureMin ?: perStop[anchorCode]?.actualArrivalMin
        } else {
            perStop[anchorCode]?.actualArrivalMin ?: perStop[anchorCode]?.actualDepartureMin
        }

        val snapshot = LiveSnapshot(
            trainNo = trainNo,
            journeyEpochDay = originEpochDay,
            atCode = anchorCode,
            atSeq = anchorSeq,
            atName = anchorStop.name,
            event = event,
            eventMinute = eventMin,
            scheduledMinuteAtEvent = anchorStop.departureMin ?: anchorStop.arrivalMin,
            nextCode = route.stops.firstOrNull { it.seq > anchorSeq }?.code,
            expectedNextMinute = null,
            delayMinutes = lastDelay,
            platform = perStop[anchorCode]?.platform,
            perStop = perStop,
            fetchedAtMillis = nowMillis,
            sourceKind = SourceKind.PROVIDER,
            feedStampMillis = updateMillis,
            rawNote = "Where Is My Train · updated ${((nowMillis - updateMillis).coerceAtLeast(0) / 60_000)}m ago",
        )

        val detail = "at $anchorCode (${if (isDeparted) "departed" else "arrived"}), " +
            "delay ${if (lastDelay >= 0) "+$lastDelay" else "$lastDelay"}m, ${perStop.count { it.value.actualArrivalMin != null || it.value.actualDepartureMin != null }} actual stops"
        return Result(snapshot, detail, lines, body)
    }

    private fun parseIsoMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            val clean = if (iso.length > 19) iso.substring(0, 19) else iso
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply {
                timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            }.parse(clean)?.time
        } catch (e: Exception) {
            null
        }
    }
}
