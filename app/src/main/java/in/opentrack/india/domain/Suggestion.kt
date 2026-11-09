package `in`.opentrack.india.domain

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.StationHit
import `in`.opentrack.india.model.TrainSummary

/**
 * One row of the type-ahead, in either search mode.
 *
 * `value` is what the search will actually run on — a 3-letter station code or a 5-digit train number —
 * while `primary` is what the field shows. Keeping them separate is what makes picking a suggestion
 * exact: "New Delhi" and "New Delhi Railway Station" are different codes, and re-parsing the name the
 * user just watched us choose would be a second chance to get it wrong.
 */
data class Suggestion(
    val kind: Kind,
    val value: String,
    val primary: String,
    val badge: String,
    val secondary: String,
) {
    enum class Kind { STATION, TRAIN }

    companion object {
        fun ofStation(s: StationHit): Suggestion {
            val bits = ArrayList<String>(3)
            s.state?.takeIf { it.isNotBlank() }?.let { bits += it.lowercase().replaceFirstChar(Char::uppercase) }
            bits += if (s.rank == 1) "1 train halts" else "${s.rank} trains halt"
            // Smart cast is available here: `lat`/`lon` are plain vals in the same module.
            if (s.lat != null && s.lon != null) bits += "%.2f°N %.2f°E".format(s.lat, s.lon)
            return Suggestion(
                kind = Kind.STATION, value = s.code, primary = TitleCase.of(s.name),
                badge = s.code, secondary = bits.joinToString(" · "),
            )
        }

        fun ofTrain(t: TrainSummary): Suggestion {
            val bits = ArrayList<String>(3)
            bits += "${t.src} → ${t.dst}"
            if (t.totalKm > 0) bits += "${t.totalKm} km"
            t.totalMin?.let { bits += RailTime.formatDuration(it) }
            bits += "${t.halts} halts"
            return Suggestion(
                kind = Kind.TRAIN, value = t.no, primary = TitleCase.of(t.name),
                badge = t.no, secondary = bits.joinToString(" · "),
            )
        }

    }
}


/**
 * The upstream dump writes every station and train name in caps, which is fine on a station
 * board and unreadable in a list of seven. Title-casing here (rather than in the ETL) keeps the
 * bytes we ship identical to the source, so a name can still be matched by a user who typed
 * what they saw on the platform - and one function decides how it looks.
 */
object TitleCase {
    // Words that should stay lowercase mid-name. Note what is *not* here: "exp", "spl" and "jn" were, and
    // left a train called "…SHIVAMOGGA TOWN EXP" rendering as "…Town exp" — a lowercase abbreviation at the
    // end of a name reads as a truncation, not a type. Three-letter types come out as "Exp"/"Spl" the way a
    // timetable prints them.
    private val SMALL = setOf("of", "and", "the", "to", "at", "via", "for")

    // Words that *look* like codes and are not. The consonant rule below is what keeps "SBC", "NED" and "SF"
    // (the only two-letter tokens in these names, measured over the shipped train set: 257 "SF", 45 "AC")
    // uppercase — but "jn" and "spl" have no vowels either, and "Between Mid JN and DST JN" or "Malegaon SPL"
    // is a station suffix and a train type shouting in the middle of a sentence. So these two are named.
    private val WORDS = setOf("jn", "spl")

    fun of(raw: String, keep: Set<String> = emptySet()): String {
        val cleaned = raw.trim().replace('_', ' ').replace('-', '–').replace("  ", " ")
        if (cleaned.isEmpty()) return cleaned
        // Only a *shouted* name needs casing, so a name with a lowercase letter in it is left exactly as the
        // source wrote it. Measured over the shipped reference DB: every train name is upper case (4000 of
        // 4000 sampled), while a quarter of the station names are already written the way a person writes
        // them ("Badhal", "MID Jn"). The old guard read "already mixed case: leave it" but only skipped names
        // with *no* uppercase at all, so it re-cased the ones that had already been decided — "MID Jn" came
        // out "Mid Jn", and the table and the sentence above it disagreed about the same station again.
        val letters = cleaned.filter(Char::isLetter)
        if (letters.isEmpty() || letters.any { !it.isUpperCase() }) return cleaned
        return cleaned.lowercase().split(' ').mapIndexed { i, w ->
            val cased = when {
                // Station codes inside a name must stay codes. "SBC SMET Passenger" is the source's own
                // wording and "Sbc Smet Passenger" reads like a typo — in the one list where a traveller is
                // choosing between trains. Two rules keep the rest of the name a sentence: the caller says
                // which tokens are its codes, and a short token with no vowel is an abbreviation, not a word.
                // What deliberately has no rule is a *short word*: "Jn" is a junction, and a rule that
                // uppercases anything of two letters turned it into "JN" mid-sentence. A single "G"
                // ("Palem G Express") survives as a letter through the cap branch below.
                w.uppercase() in keep -> w.uppercase()
                // Compared on letters only, because the data writes "DELHI JN. -VARANASI SPECIAL" with the
                // abbreviation punctuated, and "Delhi JN." would otherwise keep the shout.
                WORDS.contains(w.filter(Char::isLetter)) -> cap(w)
                w.length in 2..4 && w.none { it in "aeiou" } -> w.uppercase()
                i > 0 && SMALL.contains(w) -> w
                else -> cap(w)
            }
            capAfterDash(cased)
        }.joinToString(" ")
    }

    /** First letter up, the rest as written: the sentence-case branch of [of]. */
    private fun cap(w: String): String = w.replaceFirstChar(Char::uppercase)

    /**
     * Indian train names are frequently two places tied with a dash ("Mumbai Central–New Delhi
     * Rajdhani"), and a lowercase half reads as a typo in a headline.
     */
    private fun capAfterDash(w: String): String = buildString(w.length) {
        var up = false
        for (c in w) {
            if (c == '–' || c == '-') { append(c); up = true; continue }
            append(if (up) c.uppercaseChar() else c)
            up = false
        }
    }
    }
