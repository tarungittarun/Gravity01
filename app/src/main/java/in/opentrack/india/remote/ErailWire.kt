package `in`.opentrack.india.remote

import `in`.opentrack.india.core.RailTime

/**
 * Parser for the public Indian Railways enquiry wire format (erail-style `getTrains.aspx`).
 *
 * Chosen because it is the only *stable machine interface* in this problem space that needs no key:
 * the payload is a flat, `~`-delimited record stream that these enquiry services have emitted for
 * over a decade, as opposed to their HTML (which changes weekly) or IRCTC's session-bound JSON
 * (which needs a login, a CSRF header and a server-side anti-bot dance — i.e. exactly the
 * fragile-proxy dependency this project is not allowed to have).
 *
 * Framing:
 *   `~~~~~~~~`  separates logical sections (header, schedule, live table)
 *   `~^`        separates records inside a section
 *   `~`         separates fields inside a record
 *   empty fields are dropped by the servers, so **field indices are not stable**.
 *
 * That last fact is the whole design: we classify each field by its *shape* (station code, HH:MM,
 * integer delay, platform, status word) instead of trusting a column number, and allow a positional
 * [columnHint] override so a future format change can be fixed from Settings without shipping an
 * APK. A parser that throws on an unexpected column would blank the live screen; this one degrades
 * to "we understood these 3 stops" and says so.
 */
object ErailWire {

    private val CODE_RE = Regex("^[A-Z][A-Z0-9]{1,5}$")
    private val TIME_RE = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
    private val SIGNED_DELAY_RE = Regex("^[+-]\\d{1,3}$")
    private val SECTION = Regex("~~~~~~~~")

    data class WireStop(
        val seq: Int,
        val code: String?,
        val name: String?,
        val schedArr: Int?,
        val schedDep: Int?,
        val actArr: Int?,
        val actDep: Int?,
        val delayArr: Int?,
        val delayDep: Int?,
        val platform: String?,
        val statusWord: String?,
        /** how many fields we could not classify — surfaced, so a format change is visible */
        val unknownFields: Int,
    )

    data class Header(
        val trainNo: String?,
        val trainName: String?,
        val srcCode: String?,
        val dstCode: String?,
        val trainType: String?,
        val totalKm: Int?,
        val note: String?,
        /** the "current position" sentence the service puts in the header, if present */
        val positionLine: String?,
    )

    data class Parsed(
        val header: Header?,
        val stops: List<WireStop>,
        val unclassifiedRows: Int,
        /** when the payload's rows carry *reported* times at all — see [hasLiveColumns] */
        val feedStampMillis: Long? = null,
    ) {
        val isEmpty: Boolean get() = header == null && stops.isEmpty()

        /**
         * False means the enquiry service answered, and answered about *this* train, but published no
         * running-time column for it: no arrival minute, no departure minute, no signed delay anywhere.
         * That is the normal state of this endpoint, not an error — and it is precisely the thing the
         * screen used to hide by printing the timetable in the "live" column. The provider reports it, so
         * the traveller reads "this source has no per-stop running times" instead of a table of numbers
         * that look like a report.
         */
        val hasLiveColumns: Boolean
            get() = stops.any {
                it.actArr != null || it.actDep != null || it.delayArr != null || it.delayDep != null
            }
    }

    fun splitRecords(text: String): List<List<String>> =
        text.split(SECTION)
            .flatMap { it.split("~^") }
            .map { row -> row.split("~").map(String::trim).filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }

    fun parse(text: String, columnHint: String? = null): Parsed {
        val rows = splitRecords(text)
        var header: Header? = null
        val stops = ArrayList<WireStop>(rows.size)
        var unclassified = 0
        for ((index, fields) in rows.withIndex()) {
            val asStop = stopFrom(fields, index, columnHint)
            if (asStop != null && asStop.code != null) stops += asStop
            else if (header == null) header = headerFrom(fields, text)
            else if (fields.size >= 3) unclassified++
        }
        return Parsed(header, stops, unclassified, feedStampMillis(rows))
    }

    /**
     * The instant the provider says the payload was generated. The enquiry service writes a stamp into
     * the header record of every answer — `2026-10-11-7-56-55`, i.e. 07:56:55 IST on 11 October 2026 —
     * and that is the only number on this whole problem that answers "how fresh is the *data*", as
     * opposed to "how long ago did we touch the network". It is scanned across every record rather than
     * read from a fixed column for the same reason the rest of this parser is shape-based: the servers
     * drop empty fields, so positions move.
     */
    fun feedStampMillis(rows: List<List<String>>): Long? {
        for (row in rows) {
            for (candidate in row) {
                val f = candidate.trim()
                if (STAMP_RE.matches(f)) return stampToMillis(f)
            }
        }
        return null
    }

    fun feedStampMillis(text: String): Long? = feedStampMillis(splitRecords(text))

    /**
     * `YYYY-M-D-H-M-S` in IST to epoch millis. Deliberately not `SimpleDateFormat`: it is lenient by
     * default, so a misread field silently becomes a date in the wrong month and the header then
     * confidently prints an age computed from it. Here every part is range-checked and a bad one yields
     * null, which the UI renders as "no stamp from this source".
     */
    fun stampToMillis(raw: String): Long? {
        val m = STAMP_RE.matchEntire(raw.trim()) ?: return null
        val g = m.groupValues
        val year = g[1].toIntOrNull() ?: return null
        val month = g[2].toIntOrNull() ?: return null
        val day = g[3].toIntOrNull() ?: return null
        val hour = g[4].toIntOrNull() ?: return null
        val minute = g[5].toIntOrNull() ?: return null
        val second = g[6].toIntOrNull() ?: return null
        if (year < 2000 || year > 2100 || month !in 1..12 || day !in 1..31 ||
            hour > 23 || minute > 59 || second > 59
        ) return null
        val epochDay = runCatching { RailTime.daysFromCivil(year, month, day) }.getOrNull() ?: return null
        return epochDay * MILLIS_PER_DAY + hour * 3_600_000L + minute * 60_000L + second * 1_000L -
            RailTime.IST_OFFSET_MILLIS
    }

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Month and day are not zero-padded in this format, which is why this is not a `SimpleDateFormat`. */
    private val STAMP_RE = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})-(\\d{1,2})-(\\d{1,2})-(\\d{1,2})$")

    /**
     * A record is a stop when it contains a station-code-shaped field and at least one time.
     * Deliberately not "row length == N": the servers drop empty columns (no arrival at the source,
     * no platform at most stations), so a length check fails on exactly the interesting rows.
     */
    fun stopFrom(fields: List<String>, seq: Int, columnHint: String?): WireStop? {
        if (fields.size < 3) return null
        if (columnHint != null) return stopByHint(fields, seq, columnHint)

        var code: String? = null
        var name: String? = null
        val times = ArrayList<Int>(6)
        val signed = ArrayList<Int>(2)
        var platform: String? = null
        var status: String? = null
        var unknown = 0
        var lastTimePos = -1
        for ((pos, f) in fields.withIndex()) {
            when {
                // A sign is the only unambiguous marker of a delay in this format: a bare small integer
                // is just as likely to be a distance, a day number or a platform, and reading one of
                // those as "+1 min late" would be a fabricated fact rather than a parsed one.
                SIGNED_DELAY_RE.matches(f) -> {
                    val v = f.toInt()
                    if (v in -900..900 && signed.size < 2) signed += v
                }
                code == null && pos < 4 && CODE_RE.matches(f) -> code = f
                TIME_RE.matches(f) -> {
                    RailTime.parseHm(f)?.let { times += it }
                    lastTimePos = pos
                }
                status == null && STATUS_WORDS.any { w -> f.contains(w, true) } -> status = f.lowercase()
                // A bare "2" is a platform *only* after the times: these tables lead with a row index,
                // and claiming that as the platform would put a number on screen that the train never
                // had. A prefixed value ("PF 3", "Platform 1") is unambiguous anywhere in the row.
                platform == null && PLATFORM_RE.matches(f) &&
                    (f.any { it.isLetter() } || (lastTimePos >= 0 && pos > lastTimePos)) ->
                    platform = f.filter { it.isDigit() }
                name == null && f.length > 2 && f.any { it.isLetter() } -> name = f
                // Unclassified fields are counted instead of being forced into a slot, so a format
                // change surfaces in `unclassifiedRows` rather than as a wrong number on screen.
                else -> unknown++
            }
        }
        if (code == null && times.isEmpty()) return null
        // An identity row (train number, name, endpoints, distance) also carries a station code, so the
        // test above alone would read it as a halt: it has no times, but a truncated tail has none
        // either, so "no time" cannot be the disqualifier. What separates it is the leading integer — a
        // halt row starts with a sequence number, this row starts with the five-digit train number.
        // Classifying it as a stop would both invent a halt and hide the train's identity from Header.
        if (times.isEmpty() && fields.getOrNull(0)?.let { TRAIN_NO_RE.matches(it) } == true) return null
        val sched = times.getOrNull(0) to times.getOrNull(1)
        val actual = times.getOrNull(2) to times.getOrNull(3)
        // Delay: a signed column when the feed sent one, otherwise derived from actual minus scheduled.
        // The feed's own signed column is authority *including when it says 0*. The `takeIf { it != 0 }` that
        // used to sit here turned a reported "on time" into a null, which the branch below then "re-derived"
        // from the two clocks — and a punctual halt past midnight re-derives to ±23 h, because clocks are
        // day-blind. A report is kept; a derivation is still allowed to conclude "nothing to carry".
        var delayArr = signed.getOrNull(0)
        var delayDep = signed.getOrNull(1)
        // Copied to locals first: `first`/`second` are public properties of another module's `Pair`,
        // so the null test above does not license a smart cast at the arithmetic site.
        val schedArr = sched.first
        val schedDep = sched.second
        val actArr = actual.first
        val actDep = actual.second
        if (delayArr == null && actArr != null && schedArr != null) {
            delayArr = RailTime.clockDelta(actArr, schedArr)?.takeIf { it != 0 }
        }
        if (delayDep == null && actDep != null && schedDep != null) {
            delayDep = RailTime.clockDelta(actDep, schedDep)?.takeIf { it != 0 }
        }
        return WireStop(
            seq = seq, code = code, name = name,
            schedArr = schedArr, schedDep = schedDep,
            actArr = actArr, actDep = actDep,
            delayArr = delayArr, delayDep = delayDep,
            platform = platform, statusWord = status, unknownFields = unknown,
        )
    }

    /**
     * Settings-provided mapping. Either order is accepted — `1:code` (what the Settings hint and the
     * docs show, because reading a wire format is "column 1 holds the code") and `code:1` — since a
     * person fixing a broken live screen at 6 a.m. should not have to remember which way round the
     * pairs go. A segment that parses as neither is ignored rather than trusted.
     */
    private fun stopByHint(fields: List<String>, seq: Int, hint: String?): WireStop {
        val map = HashMap<String, Int>()
        hint?.split(',')?.forEach { part ->
            val kv = part.split(':')
            if (kv.size == 2) {
                val a = kv[0].trim().lowercase()
                val b = kv[1].trim()
                val (name, index) = if (a.all { it.isDigit() }) b.lowercase() to a.toIntOrNull()
                else a to b.toIntOrNull()
                if (name.isNotBlank() && index != null && index >= 0) map[name] = index
            }
        }
        fun at(key: String): String? = map[key]?.let { fields.getOrNull(it)?.trim() }
        fun min(key: String): Int? = at(key)?.let { RailTime.parseHm(it) }
        fun int(key: String): Int? = at(key)?.let { s -> Regex("(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull() }
        return WireStop(
            seq = int("seq") ?: seq,
            code = at("code")?.uppercase(),
            name = at("name"),
            schedArr = min("sa"), schedDep = min("sd"), actArr = min("aa"), actDep = min("ad"),
            delayArr = int("da"), delayDep = int("dd"), platform = at("pf"),
            statusWord = at("st")?.lowercase(),
            unknownFields = 0,
        )
    }

    private fun headerFrom(fields: List<String>, whole: String): Header? {
        if (fields.isEmpty()) return null
        val no = fields.firstOrNull { TRAIN_NO_RE.matches(it) }
        val name = fields.firstOrNull { it.length > 6 && it.any { c -> c.isLetter() } &&
            !CODE_RE.matches(it) && !TIME_RE.matches(it) }
        val codes = fields.filter { CODE_RE.matches(it) }
        // The position sentence is prose the service puts between the tables, and the payload arrives as
        // ONE line delimited by `~`/`~^`/`~~~~~~~~` — so `lineSequence()` would hand back the entire
        // body and match nothing. Split on the framing first, then look for the sentence.
        val position = whole.split(FRAGMENT_RE)
            .map { it.trim() }
            .firstOrNull { it.length in 12..300 && it.any { c -> c.isLetter() } &&
                POSITION_HINTS.any { h -> it.contains(h, true) } }
        if (no == null && name == null) return null
        return Header(
            trainNo = no, trainName = name?.uppercase(),
            srcCode = codes.getOrNull(0), dstCode = codes.lastOrNull()?.takeIf { codes.size > 1 },
            trainType = fields.firstOrNull { TYPE_WORDS.any { w -> it.equals(w, true) } }?.uppercase(),
            totalKm = fields.mapNotNull { s -> KM_RE.find(s)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
                .firstOrNull(),
            note = null, positionLine = position,
        )
    }

    /** Framing this format uses instead of newlines. */
    private val FRAGMENT_RE = Regex("[\\n~]+")

    /** Phrases that mark the "where is it now" sentence in the running-status payload. */
    private val POSITION_HINTS = listOf("current station", "last reported", "is running", "arrived at",
        "departed", "yet to arrive", "expected at", "located at", "position")

    private val TRAIN_NO_RE = Regex("^\\d{5}$")
    private val PLATFORM_RE = Regex("^(platform|pf)?\\s?\\d{1,2}$", RegexOption.IGNORE_CASE)
    private val KM_RE = Regex("(\\d[\\d,]{2,5})\\s*(km|kms|kilometre)", RegexOption.IGNORE_CASE)
    private val STATUS_WORDS = listOf("departed", "arrived", "arrival", "halt", "passed", "cancelled",
        "running", "approaching", "source", "destination", "yet to start", "terminated")
    private val TYPE_WORDS = listOf("RAJDHANI", "SHATABDI", "SINGLE", "DURONTO", "VANDE", "SUPERFAST",
        "MAIL", "EXPRESS", "PASSENGER", "MEMU", "DEMU", "SUVIDHA", "SPECIAL", "TEJAS", "HUMSAFAR")
}
