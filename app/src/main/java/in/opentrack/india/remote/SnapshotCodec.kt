package `in`.opentrack.india.remote

import `in`.opentrack.india.core.MiniJson
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus

/**
 * One snapshot format, three uses:
 *   (a) the on-disk cache that keeps the live screen alive through a tunnel or a dead provider;
 *   (b) the wire format an alternate/self-hosted endpoint can serve when the public feed changes;
 *   (c) the "last known position" record the UI labels OFFLINE · last known.
 *
 * One representation for all three is deliberate: the fallback path is parsed by the same code as the
 * override provider, so the degraded mode cannot rot unnoticed between releases.
 *
 * Key names follow the IR/NTES vocabulary (`trainNumber`, `lastUpdatedActual`, `currentLocation`)
 * rather than inventing our own, so an adapter for a different IR-derived REST API is a mapping
 * exercise, not a re-model. [decode] therefore accepts several spellings of the same fact and takes
 * times as either `"23:55"` or `1435`.
 */
object SnapshotCodec {

    fun encode(s: LiveSnapshot): String {
        val top = ArrayList<Pair<String, String?>>(20)
        top += "trainNumber" to s.trainNo
        top += "journeyDate" to s.journeyEpochDay?.let { RailTime.formatIsoDate(it) }
        // Minutes are written as plain integers, not "HH:MM". This is a machine record (cache file,
        // offline record), and a human-formatted clock string is ambiguous for the runs that matter
        // most: 1500 is 01:00 on day 2, and rendering it as "01:00 (+1d)" loses the day on the way back.
        top += "lastReportedAt" to s.eventMinute?.toString()
        top += "scheduledAtEvent" to s.scheduledMinuteAtEvent?.toString()
        top += "currentStationCode" to s.atCode
        top += "currentStationName" to s.atName
        top += "currentSeq" to s.atSeq.toString()
        top += "status" to s.event.name
        top += "nextStationCode" to s.nextCode
        top += "expectedArrival" to s.expectedNextMinute?.toString()
        top += "delayMinutes" to s.delayMinutes?.toString()
        top += "platform" to s.platform
        top += "fetchedAt" to s.fetchedAtMillis.toString()
        top += "feedStamp" to s.feedStampMillis?.let { (it / 1000L).toString() }
        top += "source" to s.sourceKind.name
        top += "note" to s.rawNote
        val stops = s.perStop.entries.joinToString(",") { (code, f) ->
            fields(
                "stationCode" to code,
                "actualArrival" to f.actualArrivalMin?.toString(),
                "actualDeparture" to f.actualDepartureMin?.toString(),
                "delayArrival" to f.delayArrivalMin?.toString(),
                "delayDeparture" to f.delayDepartureMin?.toString(),
                "platform" to f.platform,
                "cancelled" to if (f.cancelled) "true" else null,
            )
        }
        // The stops array is the only non-string value, so it is appended as raw JSON rather than
        // giving the encoder a second type to reason about.
        val parts = ArrayList<String>(top.size + 1)
        for ((k, v) in top) if (v != null) parts += "\"" + k + "\":\"" + escape(v) + "\""
        parts += "\"stops\":[" + stops + "]"
        return parts.joinToString(prefix = "{", postfix = "}", separator = ",")
    }

    /**
     * One object of the `stops` array. Null values are omitted rather than written as `null`, because
     * `decode` reads an absent key and an explicit null identically, and a sparse object is what the
     * feeds themselves send — the file stays readable when a provider confirmed only two of fifteen
     * stops.
     */
    private fun fields(vararg pairs: Pair<String, String?>): String {
        val parts = ArrayList<String>(pairs.size)
        for ((k, v) in pairs) {
            if (v != null) parts += "\"" + escape(k) + "\":\"" + escape(v) + "\""
        }
        return parts.joinToString(prefix = "{", postfix = "}", separator = ",")
    }

    /**
     * JSON string escaping for the values this codec writes.
     *
     * Control characters are *escaped*, never dropped: a note that came back from the feed with a
     * newline in it must round-trip, because this same function writes the on-disk cache and the
     * offline record. Silently deleting the character is the kind of "harmless" loss that makes a
     * re-read snapshot differ from the one the UI already drew.
     */
    private fun escape(v: String): String {
        val sb = StringBuilder(v.length + 8)
        for (c in v) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c >= ' ') sb.append(c)
        }
        return sb.toString()
    }

    /** Null on any structural problem, which makes the caller fall through to the next provider. */
    /**
     * [fallbackTrainNo] is for a hand-configured endpoint that reports the position but does not echo the
     * train number back: the caller already knows which train it asked for, and the alternative — refusing
     * the snapshot because a field we do not need is missing — would make Settings' escape hatch useless.
     * Identity is still checked by the caller against the value it ends up with.
     */
    fun decode(text: String, fallbackTrainNo: String? = null): LiveSnapshot? {
        val root = MiniJson.obj(text) ?: return null
        with(MiniJson) {
            val data = root.map("data") ?: root
            val cur = data.map("currentLocation") ?: data.map("current") ?: data
            val trainNo = data.str("trainNumber") ?: cur.str("trainNumber")
                ?: data.str("number") ?: fallbackTrainNo ?: return null
            val perStop = LinkedHashMap<String, LiveStopFields>()
            for (raw in data.list("stops") ?: data.list("route") ?: emptyList()) {
                @Suppress("UNCHECKED_CAST")
                val o = raw as? Map<String, Any?> ?: continue
                val code = str2(o["stationCode"])?.uppercase() ?: continue
                perStop[code] = LiveStopFields(
                    actualArrivalMin = time(o["actualArrival"]),
                    actualDepartureMin = time(o["actualDeparture"]),
                    delayArrivalMin = num(o["delayArrival"]) ?: num(o["delay"]),
                    delayDepartureMin = num(o["delayDeparture"]),
                    platform = str2(o["platform"]),
                    cancelled = o["cancelled"] == true || o["cancelled"] == "true",
                )
            }
            val statusRaw = str2(cur["status"])?.uppercase()
            return LiveSnapshot(
                trainNo = trainNo,
                journeyEpochDay = str2(data["journeyDate"])?.let { RailTime.epochDayOf(it) }
                    ?: str2(data["startDate"])?.let { RailTime.epochDayOf(it) }
                    ?: num(data["journeyDate"])?.toLong(),
                // Both spellings are read because this codec has two jobs: `decode` parses a *third
                // party's* field names, and it also re-reads what `encode` wrote for the on-disk cache
                // and the offline record. Accepting only one set silently loses the anchor on reload,
                // which looks exactly like "the feed went quiet".
                atCode = (str2(cur["stationCode"]) ?: str2(cur["currentStationCode"]))?.uppercase(),
                atSeq = num(cur["sequence"]) ?: num(cur["currentSeq"]) ?: 0,
                atName = str2(cur["stationName"]) ?: str2(cur["currentStationName"]),
                event = when {
                    statusRaw == null && perStop.isNotEmpty() -> StopStatus.DEPARTED
                    statusRaw?.contains("DEPART") == true -> StopStatus.DEPARTED
                    statusRaw?.contains("ARRIV") == true || statusRaw?.contains("HALT") == true ->
                        StopStatus.ARRIVED
                    statusRaw?.contains("CANCEL") == true -> StopStatus.CANCELLED_HERE
                    statusRaw?.contains("APPROACH") == true -> StopStatus.APPROACHING
                    else -> StopStatus.UNKNOWN
                },
                eventMinute = time(cur["actualDeparture"]) ?: time(cur["actualArrival"])
                    ?: time(cur["lastReportedAt"]) ?: time(data["lastUpdatedActual"]),
                scheduledMinuteAtEvent = time(cur["scheduledDeparture"]) ?: time(cur["scheduledArrival"])
                    ?: time(data["scheduledAtEvent"]),
                nextCode = str2(cur["nextStation"])?.uppercase() ?: str2(data["nextStationCode"])?.uppercase(),
                expectedNextMinute = time(data["expectedArrival"]) ?: time(cur["expectedArrival"]),
                platform = str2(cur["platform"]) ?: str2(data["platform"]),
                delayMinutes = num(data["delayMinutes"]) ?: num(cur["delayMinutes"]) ?: num(data["delay"]),
                perStop = perStop,
                fetchedAtMillis = (num(data["fetchedAt"])?.toLong()?.let { it * 1000L }
                    ?: runCatching { (data["fetchedAt"] as? String)?.toLong() ?: 0L }.getOrDefault(0L))
                    .takeIf { it > 1_000_000_000_000L } ?: System.currentTimeMillis(),
                sourceKind = runCatching { SourceKind.valueOf(str2(data["source"]) ?: "") }
                    .getOrDefault(SourceKind.PROVIDER),
                // Absent on a file written by an older build: no stamp, so the header says "updated n min
                // ago" (the age of the fetch) rather than claiming the feed is current.
                feedStampMillis = num(data["feedStamp"])?.toLong()?.takeIf { it > 0L }?.let { it * 1000L },
                rawNote = str2(data["note"]) ?: str2(data["message"]),
            )
        }
    }

    /**
     * Accepts `"23:55"`, `"23:55:00"`, `742`, `"742"`, `742.0` (JSON numbers arrive as doubles), and
     * HHMM text such as `"17:05"`-less `1705` *only* when it is too big to be a minute count.
     *
     * The disambiguation is deliberate and one-directional: a bare number in 0..2880 is read as *rail
     * minutes*, because that is the unit this codec writes and the only unit wide enough for a two-day
     * run. Reading `1500` as HHMM instead would put the marker on the wrong day after one cache reload.
     */
    private fun time(v: Any?): Int? {
        val text = when (v) {
            null -> return null
            is Number -> v.toString().removeSuffix(".0")
            is String -> v.trim()
            else -> return null
        }
        val digits = text.substringBefore('.')
        if (digits.isNotEmpty() && digits.all { it.isDigit() } && digits.length in 1..4) {
            val asMinutes = digits.toInt()
            if (asMinutes in 0..2880) return asMinutes
        }
        return RailTime.parseHm(text)
    }

    private fun num(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().removePrefix("+").toIntOrNull()
        else -> null
    }

    private fun str2(v: Any?): String? = (v as? String)?.takeIf { it.isNotBlank() }
        ?: v?.takeIf { it is Number || it is Boolean }?.toString()
}
