package `in`.opentrack.india.remote

import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.HttpBody
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Alternate request FORM found in kilotrain's published implementation, not a proven live feed.
 * Warm the official /mntes/ page for cookies, then try queryType 6 on the same official host.
 * This is attempted only after the shipped encrypted request fails. Neither a 200 nor a parsed
 * STNS array is enough to assert current-run actuals: [matchesRun] gates any promotion. */
internal object NtesMobileFallback {
    fun fetch(
        trainNo: String,
        runDay: Long,
        endpoint: String = NtesWire.BASE_URL,
        open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): Http.Result {
        if (trainNo.length !in 4..6 || !trainNo.all(Char::isDigit))
            return Http.Result.Failed(Http.Result.Kind.HTTP, "invalid train number")
        val url = URL(endpoint)
        if (url.protocol != "https" && url.host != "127.0.0.1")
            return Http.Result.Failed(Http.Result.Kind.HTTP, "insecure mobile endpoint refused")
        val warm = URL(url.protocol, url.host, url.port, "/mntes/")
        val cookie = runCatching {
            val c = open(warm)
            try {
                c.connectTimeout = 2_500
                c.readTimeout = 2_500
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", NtesWire.USER_AGENT)
                val status = c.responseCode
                Diary.get()?.log("NTES", "mobile warmup HTTP $status")
                c.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }
                    .flatMap { it.value.orEmpty() }
                    .map { it.substringBefore(';').trim() }
                    .filter { it.contains('=') }
                    .joinToString("; ")
            } finally { c.disconnect() }
        }.getOrElse {
            Diary.get()?.log("NTES", "mobile warmup failed: ${it.javaClass.simpleName}")
            ""
        }
        val body = """{"queryType":"6","trainNo":"$trainNo","startDate":"${NtesWire.dateForWire(runDay)}","stationCode":"","lang":"en"}"""
        return try {
            val c = open(url)
            try {
                c.connectTimeout = 4_000
                c.readTimeout = 4_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setRequestProperty("User-Agent", NtesWire.USER_AGENT)
                c.setRequestProperty("Referer", warm.toString())
                c.setRequestProperty("Connection", "close")
                if (cookie.isNotBlank()) c.setRequestProperty("Cookie", cookie)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = c.responseCode
                if (status !in 200..299) {
                    Diary.get()?.log("NTES", "mobile JSON HTTP $status")
                    Http.Result.Failed(Http.Result.Kind.HTTP, "mobile JSON HTTP $status")
                } else {
                    val reply = HttpBody.read(c.inputStream, c.contentEncoding)
                    Diary.get()?.log("NTES", "mobile JSON HTTP $status body=${reply.length}B")
                    if (reply.isBlank()) Http.Result.Failed(Http.Result.Kind.EMPTY, "mobile JSON blank")
                    else Http.Result.Ok(reply, fromCache = false, ageMillis = 0)
                }
            } finally { c.disconnect() }
        } catch (e: Exception) {
            Diary.get()?.log("NTES", "mobile JSON failed: ${e.javaClass.simpleName}: ${e.message}")
            Http.Result.Failed(Http.Result.Kind.HTTP, "mobile JSON ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** A static timetable has a weaker evidence requirement than actuals, but must still prove
     * train number (if published), origin day and the passenger's selected span. Never use this
     * predicate to authenticate ETA/ETD as observed minutes. */
    fun matchesScheduleRun(
        doc: JSONObject, trainNo: String, runDay: Long, fromCode: String, toCode: String,
    ): Boolean {
        val rows = doc.optJSONArray("STNS") ?: return false
        if (rows.length() < 3 || rows.length() > 1000) return false
        val codes = (0 until rows.length()).map { i ->
            rows.optJSONObject(i)?.optString("SC")?.trim()?.uppercase().orEmpty()
        }
        if (codes.any { !it.matches(Regex("[A-Z0-9]{1,6}")) } ||
            codes.distinct().size != codes.size) return false
        val first = codes.indexOf(fromCode.uppercase())
        if (first < 0 || codes.indexOf(toCode.uppercase()) <= first) return false
        val src = doc.optString("SRC").trim()
        if (src.isNotBlank() && !src.equals(codes.first(), true)) return false
        val explicitNo = listOf("TrainNo", "trainNo", "TrainNumber", "TRAIN_NO")
            .map { doc.optString(it).trim() }.firstOrNull { it.isNotEmpty() }
        if (explicitNo != null && explicitNo != trainNo) return false
        val expected = NtesWire.dateForWire(runDay)
        val topDate = listOf("StartDate", "startDate", "RunDate", "runDate", "JourneyDate")
            .map { doc.optString(it).trim() }.firstOrNull { it.isNotEmpty() }
        if (topDate != null && !topDate.equals(expected, true)) return false
        val firstDeparture = rows.optJSONObject(0)?.optString("STD").orEmpty()
        return if (CELL_DATE.find(firstDeparture) != null) dateInCell(firstDeparture, expected)
        else topDate != null
    }

    /** Match the train AND the run in the decrypted STNS reply. The official encrypted
     * ShowFullRunJson payload on the owner's R53 phone has STNS but apparently no top-level
     * StartDate: it answered 34,957 bytes in 0.4 s and our invented mandatory field dropped it.
     * A date stamped on a changed, delay-bearing ETA/ETD cell is an alternate form of run evidence.
     * A date on STA/STD alone is only the timetable and is never sufficient. */
    fun matchesRun(
        doc: JSONObject, trainNo: String, runDay: Long,
        fromCode: String? = null, toCode: String? = null,
    ): Boolean {
        val rows = doc.optJSONArray("STNS") ?: return false
        val codes = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("SC") }
            .map { it.uppercase() }
        val src = doc.optString("SRC").trim()
        if (src.matches(Regex("[A-Za-z]{2,5}")) && codes.isNotEmpty() &&
            !src.equals(codes.first(), true)) return false
        val explicitNo = listOf("TrainNo", "trainNo", "TrainNumber", "TRAIN_NO")
            .map { doc.optString(it).trim() }.firstOrNull { it.isNotEmpty() }
        if (explicitNo != null && explicitNo != trainNo) return false
        if (explicitNo == null) {
            // The actual 16227 ShowFullRunJson capture has NO train-number field. It is a reply
            // to a train-number + origin-date POST; require its multi-stop route to contain the
            // two stations the traveller selected, in order, rather than guess from the old DB.
            val from = fromCode?.uppercase() ?: return false
            val to = toCode?.uppercase() ?: return false
            val first = codes.indexOf(from)
            if (codes.distinct().size < 3 || first < 0 || codes.indexOf(to) <= first) return false
        }
        val expected = NtesWire.dateForWire(runDay)
        val topDate = listOf("StartDate", "startDate", "RunDate", "runDate", "JourneyDate")
            .map { doc.optString(it).trim() }.firstOrNull { it.isNotBlank() }
        if (topDate != null && !topDate.equals(expected, ignoreCase = true)) return false
        val updateStamp = listOf("LUPDT", "LASTUPD", "LAST_UPDATE", "lastUpdate")
            .map { doc.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        // A run starting before midnight can still be reporting just after midnight.
        val currentUpdateDated = dateInCell(updateStamp, expected) ||
            dateInCell(updateStamp, NtesWire.dateForWire(runDay + 1))
        if (topDate == null && !currentUpdateDated) return false
        // LUPDT may be the following calendar day for TWO adjacent runs. Anchor it to
        // the origin's scheduled departure date, not merely a recent update date.
        if (topDate == null && !dateInCell(rows.optJSONObject(0)?.optString("STD").orEmpty(),
                expected)) return false
        // A timetable-only answer with a fresh header is still not an observation.
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            for ((timeKey, scheduleKey, delayKey) in listOf(
                Triple("ETA", "STA", "DARR"), Triple("ETD", "STD", "DDEP"),
            )) {
                val cell = r.optString(timeKey).trim()
                val sch = r.optString(scheduleKey).trim()
                val delay = r.optString(delayKey).trim()
                if (delay.isEmpty() || !delay.any(Char::isDigit)) continue
                val cellDated = CELL_DATE.find(cell) != null
                val sameRunCell = dateInCell(cell, expected) ||
                    dateInCell(cell, NtesWire.dateForWire(runDay + 1))
                if ((!cellDated || sameRunCell) && (sameRunCell || currentUpdateDated || topDate != null) &&
                    clockInCell(cell) != null && clockInCell(cell) != clockInCell(sch)) return true
            }
        }
        return false
    }

    private val CELL_DATE = Regex("""\b(\d{1,2}-[A-Za-z]{3})(?:-(\d{4}))?\b""")
    private val CELL_CLOCK = Regex("""\b\d{1,2}:\d{2}\b""")

    private fun clockInCell(cell: String): String? = CELL_CLOCK.find(cell)?.value

    private fun dateInCell(cell: String, expected: String): Boolean {
        val m = CELL_DATE.find(cell) ?: return false
        val short = expected.substringBeforeLast('-')
        val year = expected.takeLast(4)
        return m.groupValues[1].equals(short, true) &&
            (m.groupValues[2].isEmpty() || m.groupValues[2] == year)
    }

    /** The real mobile payload stamps updates as "23:39 13-Oct" (IST, no year). */
    fun updateMillis(doc: JSONObject, runDay: Long): Long? {
        val raw = doc.optString("LUPDT").trim()
        val clock = clockInCell(raw) ?: return null
        val hour = clock.substringBefore(':').toIntOrNull() ?: return null
        val minute = clock.substringAfter(':').toIntOrNull() ?: return null
        if (hour > 23 || minute > 59) return null
        val offset = (0L..2L).firstOrNull { dateInCell(raw, NtesWire.dateForWire(runDay + it)) }
            ?: return null
        return RailTime.railMinuteToEpochMillis(runDay + offset, hour * 60 + minute)
    }

    /** ETA/ETD from STNS are NOT synonymous with actuals. Only a run-matched, past,
     * schedule-plausible deviation for a halt on our route can enter Act. The rest remain unknown;
     * in particular no future estimate may anchor position or become an actual. */
    fun trustedRows(
        parsed: NtesWire.Parsed, route: TrainRoute, runDay: Long, nowMillis: Long,
    ): NtesWire.Parsed {
        val evidenceMillis = parsed.feedStampMillis?.coerceAtMost(nowMillis) ?: nowMillis
        val nowRail = ((RailTime.istEpochDay(evidenceMillis) - runDay) * 1440 +
            RailTime.istMinuteOfDay(evidenceMillis)).toInt()
        val origin = route.stops.firstOrNull()?.departureMin ?: Int.MAX_VALUE
        val byCode = route.stops.associateBy { it.code.uppercase() }
        return parsed.copy(rows = parsed.rows.map { row ->
            val stop = byCode[row.code] ?: return@map row.copy(
                actArr = null, actDep = null, delayArr = null, delayDep = null, departed = null)
            fun keep(clock: Int?, schedule: Int?): Int? {
                if (clock == null || schedule == null) return null
                val aligned = RailTime.alignClock(clock, schedule) ?: return null
                return aligned.takeIf { it - schedule in -10..180 && aligned <= nowRail + 2 &&
                    aligned >= origin - 10 }
            }
            // ETA/ETD can be predictions. An associated published stop delay is required
            // before a changed time is even eligible for the past-stop clock checks.
            val arr = if (row.delayArr != null) keep(row.actArr, stop.arrivalMin) else null
            val dep = if (row.delayDep != null) keep(row.actDep, stop.departureMin) else null
            row.copy(actArr = arr, actDep = dep,
                delayArr = row.delayArr.takeIf { arr != null },
                delayDep = row.delayDep.takeIf { dep != null },
                departed = if (dep != null) true else null)
        })
    }
}
