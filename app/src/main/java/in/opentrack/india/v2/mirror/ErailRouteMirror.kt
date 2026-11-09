package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.BrowserFetch
import `in`.opentrack.india.core.Http

/**
 * The FULL route, from the one page that answers when the officials refuse: erail's running-status
 * page server-renders the train's whole halt list as "Name - CODE" pairs (captured 2026-10-12 for
 * 16228: 27 halts TLGP→MYS, where the device's timetable carries only the middle 11). Parsed from
 * tag-stripped TEXT, pinned by the test against that capture; the list ends where the date selector
 * begins ("11-Oct"), and the nav junk after it never yields a pair.
 *
 * It carries NAMES and CODES only - never times. A halt the device timetable lacks renders with an
 * em-dash schedule, never an invented minute; the span is the point, and the table says nothing the
 * page did not.
 */
object ErailRouteMirror {

    const val SOURCE = "erail.in route page"

    data class Halt(val name: String, val code: String)

    data class Fetched(val halts: List<Halt>, val why: String)

    /** "Name - CODE" with a spaced dash; codes are 1-5 capitals ("Yeliyur - Y" is real). */
    private val DASH_CODE = Regex("""\s-\s([A-Z]{1,5})\b""")

    /** Nav junk the list never crosses: the first date token of the day selector. */
    private val DATE_TOKEN = Regex("""\d{1,2}-[A-Za-z]{3}\b""")

    private val NAV_WORDS = setOf(
        "spot", "train", "running", "status", "route", "live", "seats", "fare", "pnr",
    )

    fun parse(text: String): List<Halt> {
        val cut = DATE_TOKEN.find(text)?.range?.first ?: text.length
        val body = text.substring(0, cut)
        val marks = DASH_CODE.findAll(body).toList()
        val out = mutableListOf<Halt>()
        var prevEnd = 0
        for (m in marks) {
            var name = body.substring(prevEnd, m.range.first).trim()
            prevEnd = m.range.last + 1
            if (name.isEmpty()) continue
            // The page's head junk ("Spot Train 16228 TLGP MYS EXP Running status 16228 ... Route
            // Live Seats Fare Talguppa") rides in front of the FIRST name only; the nav row ends at
            // "Seats Fare", so cut there, falling back to dropping nav/number tokens.
            if (out.isEmpty()) {
                val afterNav = name.lastIndexOf("Seats Fare")
                name = (if (afterNav >= 0) name.substring(afterNav + "Seats Fare".length)
                else name.split(" ").dropWhile { firstWordIsNav(it) }.joinToString(" ")).trim()
            }
            // erail flags halts with a trailing "H" ("Anandapuram H - ANF"); the name is the rest.
            name = name.removeSuffix(" H").trim()
            if (name.isEmpty()) continue
            out += Halt(name, m.groupValues[1])
        }
        return out
    }

    private fun firstWordIsNav(word: String): Boolean {
        val w = word.lowercase().filter { it.isLetterOrDigit() }
        return w.isEmpty() || w in NAV_WORDS || w.all { it.isDigit() }
    }

    /**
     * The wire: the app's browser lane, the transport this device demonstrably gets answered on.
     * BrowserFetch serializes loads internally, so this queues behind (never fights) the mirror's
     * lane. One read; the caller caches the answer for a day.
     */
    fun fetchOrWhy(trainNo: String): Fetched {
        val page = BrowserFetch.pageText(
            "https://erail.in/train-running-status/$trainNo", 25_000,
            settleMillis = 1_200, textCap = 24_000,
        )
        val body = (page as? Http.Result.Ok)?.body
            ?: return Fetched(emptyList(), (page as Http.Result.Failed).message)
        val halts = parse(body)
        if (halts.size < 2) {
            return Fetched(emptyList(), "answered (${body.length}B) but no halt list parsed")
        }
        return Fetched(halts, "ok")
    }
}
