package `in`.opentrack.india.v2.source

import `in`.opentrack.india.v2.contract.PairListing
import `in`.opentrack.india.v2.contract.Provenance
import `in`.opentrack.india.v2.contract.SourceKind
import `in`.opentrack.india.v2.contract.TrainIdentity

/**
 * The erail.in pair reply, parsed ONLY against the captured payload of 2026-10-12 01:31 IST
 * (`app/src/test/resources/captures/erail-pair-SMET-MYS-2026-10-12.txt`, byte-exact from the CI probe's
 * commit comment). Every field index below exists because that capture shows it; nothing here is inferred
 * from a key-name guess - that is how v1 shipped a wrong timetable with confidence (4.5, lesson 15).
 *
 * Shape of one record, anchored on `^<number>` (offsets from the anchor):
 * ```
 * ^16228~TLGP MYS EXP~Talguppa~TLGP~Mysore Jn~MYS~Shivamogga Town~SMET~Mysore Jn~MYS~23.00~08.20~09.20~1111111~...
 *    0        1            2     3       4      5         6          7       8      9    10    11    12     13
 * ```
 * 0 number, 1 name, 2/3 origin name/code, 4/5 terminal name/code, 6/7 boarding name/code (the searched-from
 * station), 8/9 alighting name/code, 10 departure at the boarding station, 11 arrival at the alighting
 * station, 12 total journey duration, 13 run-days mask. The pre-first-record header carries the searched
 * pair codes and the feed's own data stamp (in this capture `2026-8-6-21-30-34`) - the stamp is the DATA's
 * vintage, not our fetch time, and travels in the listing note rather than being dressed up as freshness.
 */
class ErailPairParser(
    private val sourceName: String = "erail",
) : PairParser {

    override fun parse(body: String, fetchedAtMillis: Long): PairParse {
        if (body.isBlank()) return PairParse.Unreadable("")
        val headerEnd = body.indexOf('^')
        val header = if (headerEnd >= 0) body.take(headerEnd) else body
        val headerFields = header.split('~')
        val fromCode = headerFields.getOrNull(1)?.trim()?.uppercase().orEmpty()
        val toCode = headerFields.getOrNull(3)?.trim()?.uppercase().orEmpty()
        val stamp = STAMP.find(header)?.value

        val validHeader = isStationCode(fromCode) && isStationCode(toCode)
        val rows = mutableListOf<PairListing.TrainAtStation>()
        var skipped = 0

        var segStart = headerEnd
        while (segStart >= 0) {
            val next = body.indexOf('^', segStart + 1)
            val seg = if (next >= 0) body.substring(segStart, next) else body.substring(segStart)
            val f = seg.split('~')
            val number = f.getOrNull(0)?.removePrefix("^")?.trim().orEmpty()
            val name = f.getOrNull(1)?.trim().orEmpty()
            val from = f.getOrNull(3)?.trim()?.uppercase().orEmpty()
            val to = f.getOrNull(5)?.trim()?.uppercase().orEmpty()
            if (!isTrainNumber(number) || name.isEmpty() || !isStationCode(from) || !isStationCode(to)) {
                // The train exists even when our field map misses - but a half-read record is not a fact.
                // It is counted and the count is shown in the note (2.3), never silently dropped.
                if (number.isNotEmpty() || name.isNotEmpty()) skipped++
            } else {
                val boardMin = erailTime(f.getOrNull(10))
                val alightMin = erailTime(f.getOrNull(11))
                rows.add(
                    PairListing.TrainAtStation(
                        identity = TrainIdentity(
                            number = number,
                            name = name,
                            fromCode = from,
                            toCode = to,
                            provenance = Provenance(SourceKind.LIVE_PAIR, sourceName, fetchedAtMillis),
                        ),
                        // No times parsed is fine: nullable stays nullable into the UI, which renders the dash.
                        boardMin = boardMin,
                        alightMin = alightMin,
                        boardDayOffset = 0,
                        // Arrival past midnight relative to boarding: due 23.00, arriving 08.20 is tomorrow -
                        // the wrap rule the delay arithmetic owes the passenger.
                        alightDayOffset = if (boardMin != null && alightMin != null && alightMin <= boardMin) 1 else 0,
                    )
                )
            }
            segStart = next
        }

        if (!validHeader && rows.isEmpty()) return PairParse.Unreadable(body.take(40))
        if (rows.isEmpty()) {
            // The feed listed record(s) we could not read: that is OUR blindness, and presenting it as
            // "nothing runs" would be the 2.3 violation - answered-empty and could-not-read stay distinct.
            if (skipped > 0) return PairParse.Unreadable(body.take(40))
            return PairParse.NothingRunning
        }

        val note = buildList {
            stamp?.let { add("erail data stamp $it (the feed's own date, not our fetch time)") }
            if (skipped > 0) add("$skipped record(s) not readable - shown count excludes them")
        }.joinToString(" · ")

        return PairParse.Rows(
            PairListing(
                fromCode = fromCode.ifEmpty { rows.first().identity.fromCode },
                toCode = toCode.ifEmpty { rows.first().identity.toCode },
                trains = rows,
                provenance = Provenance(SourceKind.LIVE_PAIR, sourceName, fetchedAtMillis),
                note = note.ifEmpty { null },
            )
        )
    }

    /** `23.00` -> 1380. Hours 0-23, minutes 0-59; anything else is not a time and stays null. */
    private fun erailTime(raw: String?): Int? {
        val m = TIME.matchEntire(raw?.trim() ?: return null) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    companion object {
        private val STAMP = Regex("""\d{4}-\d{1,2}-\d{1,2}-\d{1,2}-\d{1,2}-\d{1,2}""")
        private val TIME = Regex("""(\d{1,2})\.(\d{2})""")

        /** Codes are 3-5 alphanumerics with at least one letter; a pure number is never a station. */
        internal fun isStationCode(s: String): Boolean =
            s.length in 3..5 && s.all { it.isLetterOrDigit() } && s.any { it.isLetter() }

        private fun isTrainNumber(s: String): Boolean = s.matches(Regex("""\d{4,5}"""))
    }
}
