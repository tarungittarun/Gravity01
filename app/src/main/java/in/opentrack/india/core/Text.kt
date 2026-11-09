package `in`.opentrack.india.core

/** Text normalisation shared by the search box and the live feed's name matching. */
object Text {

    /** Collapse to comparable form: ASCII-fold, upper, punctuation to single spaces. */
    fun normalise(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder(raw.length)
        var lastSpace = true
        for (ch in java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKD)) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            val c = when {
                ch in 'a'..'z' -> ch - 32
                ch in 'A'..'Z' || ch in '0'..'9' -> ch
                else -> ' '
            }
            if (c == ' ') {
                if (!lastSpace) sb.append(c)
                lastSpace = true
            } else {
                sb.append(c)
                lastSpace = false
            }
        }
        return sb.toString().trim()
    }

    /** Escape the LIKE wildcards so a user typing "100%" cannot match everything. */
    fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Fuzzy-ish containment used for station/train matching where the user's memory of the name is
     * approximate: every token of [query] must appear as a prefix of some token of [target].
     * "chennai central" matches "MGR CHENNAI CENTRAL"; "mas" matches via the initials path instead.
     */
    fun tokenMatch(query: String, target: String): Boolean {
        val q = normalise(query).split(' ').filter { it.length >= 2 }
        if (q.isEmpty()) return false
        val t = normalise(target).split(' ')
        return q.all { needle -> t.any { it.startsWith(needle) || needle.startsWith(it) && it.length >= 3 } }
    }

    /** Case/diacritic-insensitive equality. */
    fun same(a: String?, b: String?): Boolean =
        a != null && b != null && normalise(a) == normalise(b)

    /** Levenshtein distance, capped: used only to accept a 1-2 char typo in a station name. */
    fun editDistance(a: String, b: String, cap: Int = 2): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > cap) return cap + 1
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
    /**
     * `141908` -> `1 41 908`? No - plain western grouping, `141,908`, which is what Indian Railways
     * itself prints on its own boards and forms. Kept here rather than using `%,d` so the separator does
     * not depend on the device locale.
     */
    fun group(v: Int): String {
        val s = v.toString()
        if (s.length <= 3) return s
        val out = StringBuilder(s.length + 2)
        var i = 0
        while (i < s.length) {
            if (i > 0) out.append(',')
            val take = minOf(3, s.length - i)
            if (i == 0 && s.length % 3 != 0) {
                val first = s.length % 3
                out.append(s, 0, first); i = first; continue
            }
            out.append(s, i, i + take); i += take
        }
        return out.toString()
    }

}
