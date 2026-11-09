package `in`.opentrack.india.v2.mirror

/**
 * The mirror that answers while the officials refuse: runningstatus.in server-renders, per halt, the
 * SCHEDULED and the ACTUAL arrival/departure of today's run - the exact per-stop actuals this product
 * exists for, in plain HTML a keyless GET can read (captured 2026-10-12 for 20651: every passed halt
 * carried its actual minute). It is a second witness, never the official: every line it produces on
 * screen names it, and a mirror that answers nothing changes nothing.
 *
 * Parsing runs on tag-stripped TEXT, not on the DOM - the same discipline as the diary's page probes.
 * The shape the parser trusts, learned from the capture: each halt's text block starts at its
 * "(CODE)" marker; blocks carry four 12-hour times when the halt has actual arr+dep (sch arr, sch dep,
 * actual arr, actual dep), two when it is the source (dep only) or the terminus (arr only), and the
 * words "Update Awaited" mean the actual has not reached the mirror yet - those halts render nothing.
 * The station-near row between halts carries no "(CODE)" marker and a 24-hour clock, so it can never
 * contaminate a neighbour block; that property is asserted by the test.
 */
object RunningStatusMirror {

    const val SOURCE = "runningstatus.in"
    private const val BASE = "https://runningstatus.in/status/"

    private val CODE = Regex("""\(([A-Z]{2,5})\)""")
    private val TIME = Regex("""(\d{1,2}):(\d{2})\s+(AM|PM)\s+\d{1,2}-[A-Za-z]{3}""")

    data class Stop(
        val stationCode: String,
        val schArr: Int?,
        val schDep: Int?,
        val actArr: Int?,
        val actDep: Int?,
    )

    data class Actuals(val source: String, val stops: List<Stop>, val runEpochDay: Long? = null)

    /** The actuals, or the honest reason there are none - a silent null helps nobody debug. */
    data class Fetched(val actuals: Actuals?, val why: String)

    /**
     * The wire the phone uses: one plain GET, capped, no key, no session. The pair is (body, why) -
     * paste #19 showed DNS+TCP+TLS all healthy and the GET still "no answer", which is exactly the
     * gap a bare null leaves: the next paste must name the status or the exception, not the silence.
     */
    fun fetchOrWhy(trainNo: String, get: (String) -> Pair<String?, String> = ::plainGet): Fetched {
        val (body, why) = get(BASE + trainNo)
        if (body == null) return Fetched(null, why.ifBlank { "no answer" })
        val actuals = parse(stripTags(body)) ?: return Fetched(null, "answered but no halt rows parsed")
        return Fetched(actuals, "ok")
    }

    /** HTML to text: tags become one space, the handful of entities we meet become their characters. */
    fun stripTags(html: String): String = html
        .replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")

    fun parse(text: String): Actuals? {
        val marks = CODE.findAll(text).toList()
        if (marks.isEmpty()) return null
        val stops = mutableListOf<Stop>()
        for (i in marks.indices) {
            val code = marks[i].groupValues[1]
            val from = marks[i].range.last + 1
            val to = if (i + 1 < marks.size) marks[i + 1].range.first else text.length
            val block = text.substring(from, to)
            val stop = row(code, block) ?: continue
            stops += stop
        }
        if (stops.isEmpty()) return null
        // The page can serve yesterday's run even when today's train number is requested.
        // A run without a readable date is not safe to promote to today's actuals.
        val date = Regex("""\bDate\s+(\d{1,2}-[A-Za-z]{3}-\d{4})\b""")
            .find(text)?.groupValues?.get(1)
        val day = date?.let { token -> runCatching {
            val fmt = java.text.SimpleDateFormat("dd-MMM-yyyy", java.util.Locale.ENGLISH)
            fmt.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            fmt.isLenient = false
            `in`.opentrack.india.core.RailTime.istEpochDay(fmt.parse(token)!!.time)
        }.getOrNull() }
        return Actuals(SOURCE, stops, day)
    }

    /** One halt block -> a Stop with actuals, or null when the mirror itself has none ("Update Awaited"). */
    internal fun row(code: String, block: String): Stop? {
        val times = TIME.findAll(block)
            .map { h12(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
            .toList()
        val awaited = block.contains("Update Awaited")
        val hasArr = block.contains("Arr:")
        val hasDep = block.contains("Dep:")
        return when {
            times.size >= 4 -> Stop(code, times[0], times[1], times[2], times[3])
            times.size == 2 && !awaited && hasDep && !hasArr -> Stop(code, null, times[0], null, times[1])
            times.size == 2 && !awaited && hasArr && !hasDep -> Stop(code, times[0], null, times[1], null)
            else -> null
        }
    }

    /** "03:10 PM" -> 910. 12 AM is hour 0, 12 PM is hour 12 - the one trap of the 12-hour clock. */
    internal fun h12(hour: String, minute: String, ampm: String): Int {
        var h = hour.toInt() % 12
        if (ampm == "PM") h += 12
        return h * 60 + minute.toInt()
    }

    private fun fmt(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "${m / 60}:" + "%02d".format(m % 60)
    }

    /**
     * The sentence the screen renders, verbatim, one per halt that HAS an actual: both minutes the
     * mirror carries, then the source by name, then the delay the arithmetic gives. The delay is
     * computed against the mirror's own schedule and wrapped to the same day (a train that crosses
     * midnight shows a 20-minute slip as 20, never 1420).
     */
    fun sentences(actuals: Actuals, routeCodes: Collection<String>): Map<String, String> {
        val want = routeCodes.map { it.uppercase() }.toSet()
        val out = LinkedHashMap<String, String>()
        for (s in actuals.stops) {
            if (s.stationCode !in want) continue
            val parts = mutableListOf<String>()
            s.actArr?.let { parts += "arr ${fmt(it)}" }
            s.actDep?.let { parts += "dep ${fmt(it)}" }
            if (parts.isEmpty()) continue
            val delayWord = when {
                s.actDep != null && s.schDep != null -> delayWord(s.actDep - s.schDep)
                s.actArr != null && s.schArr != null -> delayWord(s.actArr - s.schArr)
                else -> null
            }
            val tail = if (delayWord == null) SOURCE else "$SOURCE ($delayWord)"
            out[s.stationCode] = "${s.stationCode} " + parts.joinToString(" · ") + " — via $tail"
        }
        return out
    }

    /**
     * The last halt (in route order) whose DEPARTURE the mirror confirms - the strongest "the train is
     * PAST here" signal a second witness can give. The position engine had only the schedule clock; a
     * train running 24 late is NOT where that clock says, and paste #22 is the proof on screen.
     */
    /**
     * [nowRailMinute] drives the echo guard: the mirror fills not-yet-reached halts with their
     * SCHEDULE times dressed as actuals (paste #24: at 19:43 the page carried "actual" departures for
     * 20:05 and 20:35 - timestamps in the future, which no observation can be). A departure less than
     * 30 minutes ahead of the device clock is an estimate, and an estimate must never anchor position.
     * Returns the stop and how far ahead its (rejected) departure was, so the log can name the echoes.
     */
    fun lastConfirmed(
        actuals: Actuals, routeCodes: Collection<String>, nowRailMinute: Int,
    ): Pair<Stop, List<String>>? {
        val order = routeCodes.map { it.uppercase() }
        val echoes = mutableListOf<String>()
        val confirmed = actuals.stops.filter { stop ->
            val dep = stop.actDep ?: return@filter false
            if (stop.stationCode.uppercase() !in order) return@filter false
            val ahead = (dep - nowRailMinute + 1440) % 1440
            // No observation lives in the future: a departure the clock says is still ahead (inside a
            // 30-minute skew allowance) is an echo. This zone is deliberately narrow: "ahead" wraps for
            // past departures too (departed 10 minutes ago reads 1430), and the FRESHEST deviations must
            // survive. The clause below is what kills the far echoes like SRF's on-time "20:35".
            if (ahead in 0 until 30) {
                echoes += stop.stationCode
                return@filter false
            }
            // An "actual" IDENTICAL to the schedule is unprovable either way: the mirror copies the
            // schedule into halts the train has not reached, so on-time-pattern rows near the frontier
            // are echoes too - whatever their horizon. Only a DEVIATION from the schedule is an
            // observation, and only a deviation can anchor a lateness.
            stop.schDep != null && dep != stop.schDep
        }
        val last = confirmed.maxByOrNull { order.indexOf(it.stationCode.uppercase()) } ?: return null
        return last to echoes
    }

    /** Wrap-safe scheduled-vs-actual slip in minutes from the mirror's own pair; null if either is missing. */
    fun slipMinutes(stop: Stop): Int? {
        val a = stop.actDep ?: return null
        val s = stop.schDep ?: return null
        return (((a - s + 720) % 1440) + 1440) % 1440 - 720
    }

    private fun delayWord(raw: Int): String {
        val d = ((raw + 720) % 1440) - 720
        return when {
            d <= 2 -> "on time"
            else -> "$d min late"
        }
    }

    /** The production wire: one GET, 3.5 s patience, a naming UA, nothing remembered. (body, why). */
    fun plainGet(url: String): Pair<String?, String> = try {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 3_500
            readTimeout = 3_500
            setRequestProperty("User-Agent", "OpenTrackIndia/1.0 (live running status)")
        }
        val code = conn.responseCode
        if (code in 200..299) {
            Pair(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.take(400_000), "ok")
        } else {
            Pair(null, "HTTP $code")
        }
    } catch (e: Exception) {
        Pair(null, e.javaClass.simpleName + ": " + (e.message ?: ""))
    }
}
