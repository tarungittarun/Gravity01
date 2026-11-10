package `in`.opentrack.india.remote

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
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
 * current station anchor (including non-commercial intermediate passing stations), and train position along the route.
 */
object WimtLiveProvider {

    const val SOURCE_NAME = "Where Is My Train"

    data class WimtIntermediate(
        val code: String,
        val name: String,
        val precedingHalt: String,
        val followingHalt: String,
        val mapKm: Int = -1,
        val scheduledMinute: Int? = null,
        val actualMinute: Int? = null,
        val delayMin: Int? = null,
        val eventMinute: Int? = null,
    )

    data class WimtScheduleHalt(
        val code: String,
        val schArrMin: Int? = null,
        val schDepMin: Int? = null,
        val distanceKm: Int? = null,
        val platform: String? = null,
    )

    data class Result(
        val snapshot: LiveSnapshot?,
        val detail: String,
        val stationLines: Map<String, String> = emptyMap(),
        val rawJson: String? = null,
        val intermediate: WimtIntermediate? = null,
        val intermediateStops: List<StopRow> = emptyList(),
        val scheduleHalts: Map<String, WimtScheduleHalt> = emptyMap(),
    )

    fun fetch(
        trainNo: String,
        route: TrainRoute,
        originEpochDay: Long,
        nowMillis: Long = System.currentTimeMillis(),
        stationNameLookup: ((String) -> String?)? = null,
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

        return parse(response, trainNo, route, originEpochDay, nowMillis, stationNameLookup)
    }

    fun parse(
        body: String,
        trainNo: String,
        route: TrainRoute,
        originEpochDay: Long,
        nowMillis: Long,
        stationNameLookup: ((String) -> String?)? = null,
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
        val intermediateStops = mutableListOf<StopRow>()
        val scheduleHalts = mutableMapOf<String, WimtScheduleHalt>()

        var curStnDeparted = false
        var curStnIdx = -1
        var lastDepartedRouteCode: String? = null
        var lastArrivedRouteCode: String? = null
        var lastDelay = 0

        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val code = row.optString("station_code").uppercase(Locale.ENGLISH)
            if (code.isBlank()) continue

            val isCur = code.equals(curStnCode, true) || row.optBoolean("curStn", false)
            if (isCur) {
                curStnIdx = i
            }

            // WIMT marks unreached future halts with source: "eta".
            val isEta = row.optString("source").equals("eta", ignoreCase = true)
            val departed = row.optBoolean("departed", false)
            if (isCur && departed) {
                curStnDeparted = true
            }

            val stopOnRoute = route.stops.firstOrNull { it.code.equals(code, true) }
            val stopsFlag = row.optBoolean("stops", true)

            // Arrival and departure times: only parse as actuals if NOT an ETA forecast
            val rawArr = if (!isEta) row.optString("actual_arrival_time").takeIf { it.isNotBlank() } else null
            val rawDep = if (!isEta) row.optString("actual_departure_time").takeIf { it.isNotBlank() } else null

            val actArrHm = RailTime.parseHm(rawArr)
            val actDepHm = RailTime.parseHm(rawDep)

            val arrMin = RailTime.alignClock(actArrHm, stopOnRoute?.arrivalMin)
            val depMin = RailTime.alignClock(actDepHm, stopOnRoute?.departureMin)

            val schArrHm = RailTime.parseHm(row.optString("sch_arrival_time").takeIf { it.isNotBlank() })
            val schDepHm = RailTime.parseHm(row.optString("sch_departure_time").takeIf { it.isNotBlank() })
            val distKm = (row.opt("distance") as? Number)?.toInt()
                ?: (row.opt("distance_from_source") as? Number)?.toInt()
            val pf = row.optString("platform").takeIf { it.isNotBlank() && it != "null" }
                ?: row.optJSONArray("platform_info")?.optJSONObject(0)?.optString("platform_number")
            scheduleHalts[code] = WimtScheduleHalt(
                code = code,
                schArrMin = schArrHm,
                schDepMin = schDepHm,
                distanceKm = distKm,
                platform = pf,
            )

            val rawDelayArr = (row.opt("delay_in_arrival") as? Number)?.toInt()
            val rawDelayDep = (row.opt("delay_in_departure") as? Number)?.toInt()

            val delayArr = rawDelayArr?.takeIf { it != 0 }
                ?: if (actArrHm != null && schArrHm != null) (actArrHm - schArrHm).coerceIn(-120, 1440) else rawDelayArr
            val delayDep = rawDelayDep?.takeIf { it != 0 }
                ?: if (actDepHm != null && schDepHm != null) (actDepHm - schDepHm).coerceIn(-120, 1440) else rawDelayDep

            if (stopOnRoute != null) {
                if (departed || rawDep != null) {
                    lastDepartedRouteCode = code
                    if (delayDep != null) lastDelay = delayDep
                    else if (delayArr != null) lastDelay = delayArr
                } else if (!isEta && rawArr != null) {
                    lastArrivedRouteCode = code
                    if (delayArr != null) lastDelay = delayArr
                }
            } else if (!stopsFlag || !route.stops.any { it.code.equals(code, true) }) {
                val rawName = row.optString("station_name").takeIf { it.isNotBlank() && it != "null" }
                val resolvedName = rawName ?: stationNameLookup?.invoke(code) ?: code
                val dist = (row.opt("distance") as? Number)?.toInt() ?: -1
                intermediateStops.add(StopRow(
                    seq = 0, code = code, name = resolvedName, state = null, lat = null, lon = null,
                    arrivalMin = schArrHm, departureMin = schDepHm, journeyDay = 1, schedKm = dist,
                    isHalt = false,
                ))
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

        // Check if curStn is an intermediate non-commercial passing halt
        val curStnOnRoute = route.stops.firstOrNull { it.code.equals(curStnCode, true) }
        val wimtIntermediate: WimtIntermediate? = if (curStnCode.isNotBlank() && curStnOnRoute == null && curStnIdx >= 0) {
            val curRow = rows.optJSONObject(curStnIdx)
            var precCode: String? = null
            for (j in curStnIdx - 1 downTo 0) {
                val c = rows.optJSONObject(j)?.optString("station_code")?.uppercase(Locale.ENGLISH) ?: continue
                if (route.stops.any { it.code.equals(c, true) }) {
                    precCode = c
                    break
                }
            }
            var follCode: String? = null
            for (j in curStnIdx + 1 until rows.length()) {
                val c = rows.optJSONObject(j)?.optString("station_code")?.uppercase(Locale.ENGLISH) ?: continue
                if (route.stops.any { it.code.equals(c, true) }) {
                    follCode = c
                    break
                }
            }
            val prec = precCode ?: lastDepartedRouteCode ?: route.stops.first().code
            val foll = follCode ?: route.stops.firstOrNull { it.seq > (route.stops.firstOrNull { s -> s.code.equals(prec, true) }?.seq ?: 1) }?.code ?: prec

            val actArrHm = RailTime.parseHm(curRow?.optString("actual_arrival_time"))
            val schArrHm = RailTime.parseHm(curRow?.optString("sch_arrival_time"))
            val actDepHm = RailTime.parseHm(curRow?.optString("actual_departure_time"))
            val schDepHm = RailTime.parseHm(curRow?.optString("sch_departure_time"))

            val curDelay = (curRow?.opt("delay_in_arrival") as? Number)?.toInt()?.takeIf { it != 0 }
                ?: (curRow?.opt("delay_in_departure") as? Number)?.toInt()?.takeIf { it != 0 }
                ?: if (actArrHm != null && schArrHm != null) (actArrHm - schArrHm).coerceIn(-120, 1440)
                else lastDelay

            val rawName = curRow?.optString("station_name")?.takeIf { it.isNotBlank() && it != "null" }
            val name = rawName ?: stationNameLookup?.invoke(curStnCode) ?: curStnCode
            val dist = (curRow?.opt("distance") as? Number)?.toInt() ?: -1

            WimtIntermediate(
                code = curStnCode,
                name = name,
                precedingHalt = prec,
                followingHalt = foll,
                mapKm = dist,
                scheduledMinute = schArrHm ?: schDepHm,
                actualMinute = actArrHm ?: actDepHm,
                delayMin = curDelay,
                eventMinute = actArrHm ?: actDepHm ?: schArrHm ?: schDepHm,
            )
        } else null

        val (anchorStop, isDeparted) = when {
            wimtIntermediate != null -> {
                (route.stops.firstOrNull { it.code.equals(wimtIntermediate.precedingHalt, true) }
                    ?: route.stops.first()) to true
            }
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
            wimtIntermediate != null -> StopStatus.DEPARTED
            isDeparted -> StopStatus.DEPARTED
            perStop[anchorCode]?.actualArrivalMin != null || curStnCode == anchorCode -> StopStatus.ARRIVED
            else -> StopStatus.UNKNOWN
        }

        val eventMin = if (wimtIntermediate != null) {
            wimtIntermediate.actualMinute ?: wimtIntermediate.eventMinute
        } else if (isDeparted) {
            perStop[anchorCode]?.actualDepartureMin ?: perStop[anchorCode]?.actualArrivalMin
        } else {
            perStop[anchorCode]?.actualArrivalMin ?: perStop[anchorCode]?.actualDepartureMin
        }

        val effectiveDelay = wimtIntermediate?.delayMin ?: lastDelay

        val snapshot = LiveSnapshot(
            trainNo = trainNo,
            journeyEpochDay = originEpochDay,
            atCode = anchorCode,
            atSeq = anchorSeq,
            atName = anchorStop.name,
            event = event,
            eventMinute = eventMin,
            scheduledMinuteAtEvent = anchorStop.departureMin ?: anchorStop.arrivalMin,
            nextCode = wimtIntermediate?.followingHalt ?: route.stops.firstOrNull { it.seq > anchorSeq }?.code,
            expectedNextMinute = null,
            delayMinutes = effectiveDelay,
            platform = perStop[anchorCode]?.platform,
            perStop = perStop,
            fetchedAtMillis = nowMillis,
            sourceKind = SourceKind.PROVIDER,
            feedStampMillis = updateMillis,
            rawNote = "Where Is My Train · updated ${((nowMillis - updateMillis).coerceAtLeast(0) / 60_000)}m ago",
        )

        val detail = if (wimtIntermediate != null) {
            "near ${wimtIntermediate.name} (passed ${anchorStop.name}), delay ${if (effectiveDelay >= 0) "+$effectiveDelay" else "$effectiveDelay"}m, ${perStop.count { it.value.actualArrivalMin != null || it.value.actualDepartureMin != null }} actual stops"
        } else {
            "at $anchorCode (${if (isDeparted) "departed" else "arrived"}), delay ${if (effectiveDelay >= 0) "+$effectiveDelay" else "$effectiveDelay"}m, ${perStop.count { it.value.actualArrivalMin != null || it.value.actualDepartureMin != null }} actual stops"
        }

        return Result(snapshot, detail, lines, body, wimtIntermediate, intermediateStops, scheduleHalts)
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
