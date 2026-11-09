package `in`.opentrack.india.v2.mirror

/**
 * The SECOND witness for actual arr/dep: confirmtkt.com's running-status page carries per-halt actual
 * minutes too ("Delay by N min" rows), captured for 20651 on 2026-10-12 while the train ran. It has no
 * station CODES - only names ("Tumakuru" where our timetable says "Tumkur") - so the parser aligns by
 * NAME, walking the route in order and never matching backwards, with a three-letter-prefix fallback
 * for spelling drift. Display and cross-check only: the position anchor comes from the primary mirror,
 * because this page blends actuals (passed halts) and estimates (ahead) in the same column.
 */
object ConfirmTktMirror {

    const val SOURCE = "confirmtkt"
    const val BASE = "https://www.confirmtkt.com/train-running-status/"

    data class Fetched(
        val lines: Map<String, String>,
        val halts: Int,
        val ok: Boolean,
        /** The same walk as structured minutes, so the table's Act/Est columns can carry this witness. */
        val stops: List<RunningStatusMirror.Stop> = emptyList(),
        val pageDay: String? = null,
    )

    /** The wire the phone uses: same (body, why) pair discipline as the primary mirror. */
    fun fetchOrWhy(
        trainNo: String,
        stops: List<Pair<String, String>>,
        get: (String) -> Pair<String?, String> = ::plainGet,
    ): Fetched {
        val (body, why) = get(BASE + trainNo)
        if (body == null) return Fetched(emptyMap(), 0, false).also { }
        val text = RunningStatusMirror.stripTags(body)
        val lines = parse(text, stops)
        return Fetched(lines, lines.size, lines.isNotEmpty(), stopsOf(text, stops), pageDayOf(text))
    }

    /** ConfirmTkt prints "Day 112-Oct" after tag stripping (Day 1 + 12-Oct). No year on
     * this page: compare the month/day with the requested run, never with fetch time. */
    fun pageDayOf(text: String): String? =
        Regex("""Day\s*1\s*(\d{1,2}-[A-Za-z]{3})\b""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)

    private val TIME = Regex("""\b(\d{1,2}):(\d{2})\b""")
    private val DELAY = Regex("""Delay by (\d{1,3}) min""")

    private fun norm(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Walk the route in order; each halt's block runs from its name to the next halt's name. Two times
     * = actual arr + dep; a single time = the origin's departure or the terminus's arrival (position in
     * the route decides). Rows with no time render nothing.
     */
    /** One halt's walk result: the page's minutes and its own delay verdict, raw. */
    internal data class Row(val code: String, val arr: Int?, val dep: Int?, val delayMin: Int?)

    /**
     * The walk both consumers share: per route halt, the minutes this page carries, in route order.
     * Display and cross-check only - this source blends actuals and estimates in one column, so
     * nothing here may anchor a position.
     */
    internal fun extract(text: String, stops: List<Pair<String, String>>): List<Row> {
        val rows = mutableListOf<Row>()
        var from = 0
        for ((idx, pair) in stops.withIndex()) {
            val (code, name) = pair
            val key = norm(name)
            var at = findName(text, key, from)
            if (at < 0) {
                // prefix fallback: "tumakuru" vs "tumkur" drift
                at = findName(text, key.take(3), from)
            }
            if (at < 0) continue
            val nextAt = if (idx + 1 < stops.size) {
                val nKey = norm(stops[idx + 1].second)
                val direct = findName(text, nKey, at + key.length)
                (if (direct >= 0) direct else findName(text, nKey.take(3), at + key.length))
                    .takeIf { it >= 0 } ?: (at + 700)
            } else {
                (at + 700).coerceAtMost(text.length)
            }
            val block = text.substring(at, nextAt.coerceAtMost(text.length))
            val times = TIME.findAll(block).toList()
                .map { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
                .filter { it in 0..1439 }
            val delay = DELAY.find(block)?.groupValues?.get(1)?.toIntOrNull()
            if (times.isNotEmpty()) {
                val arr = when {
                    times.size >= 2 -> times[0]
                    idx == 0 -> null // the origin's single minute is a departure
                    else -> times[0]
                }
                val dep = when {
                    times.size >= 2 -> times[1]
                    idx == 0 -> times[0]
                    else -> null
                }
                if (arr != null || dep != null) rows += Row(code, arr, dep, delay)
            }
            from = at + key.length.coerceAtLeast(3)
        }
        return rows
    }

    fun parse(text: String, stops: List<Pair<String, String>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (r in extract(text, stops)) {
            val parts = mutableListOf<String>()
            r.arr?.let { parts += "arr ${fmt(it)}" }
            r.dep?.let { parts += "dep ${fmt(it)}" }
            if (parts.isEmpty()) continue
            val tail = if (r.delayMin != null && r.delayMin > 2) "$SOURCE (${r.delayMin} min late)"
            else "$SOURCE (on time)"
            out[r.code] = "${r.code} " + parts.joinToString(" · ") + " — via $tail"
        }
        return out
    }

    /** The same walk as structured stops; the mirror-side schedule is left null (the Sch column is ours). */
    fun stopsOf(text: String, stops: List<Pair<String, String>>): List<RunningStatusMirror.Stop> =
        extract(text, stops).map { RunningStatusMirror.Stop(it.code, null, null, it.arr, it.dep) }

    private fun findName(text: String, key: String, fromIdx: Int): Int {
        if (key.isEmpty()) return -1
        var i = text.indexOf(key, fromIdx, ignoreCase = true)
        return i
    }

    private fun fmt(minute: Int): String {
        val m = ((minute % 1440) + 1440) % 1440
        return "${m / 60}:" + "%02d".format(m % 60)
    }

    /** Same wire discipline as the primary mirror: one GET, 8 s, (body, why). */
    fun plainGet(url: String): Pair<String?, String> = RunningStatusMirror.plainGet(url)
}
