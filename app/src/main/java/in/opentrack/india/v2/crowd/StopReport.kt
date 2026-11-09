package `in`.opentrack.india.v2.crowd

/**
 * One observation from one traveller, on one train, at one halt. This is the WIMT-proven architecture
 * (ADR-006, accepted on the owner's standing authorization to change architecture): the witnesses are
 * ON the train, already past every firewall that blocks datacentres. The schema is flat and tiny on
 * purpose - it rides a keyless public relay as one JSON line, and a five-year-old phone parses it
 * without a JSON library (hand-rolled both ways, tested round-trip).
 *
 * Honesty guards travel WITH the data, not beside it: a report never claims officialness - the UI
 * names it "reported by travellers" with the witness count and age; the timetable column stays.
 */
data class StopReport(
    val trainNo: String,
    val stationCode: String,
    val kind: Kind,
    val railMinute: Int,
    val reportedAtMillis: Long,
) {
    enum class Kind { ARRIVED, DEPARTED }

    /** Flat JSON, hand-escaped exactly like the parser expects it. No library, no surprises. */
    fun toJson(): String {
        val esc = Regex("[\"\\\\]")
        fun e(s: String) = s.replace(esc) { m -> "\\" + m.value }
        return buildString {
            append("{\"trainNo\":\"").append(e(trainNo)).append('"')
            append(",\"stationCode\":\"").append(e(stationCode)).append('"')
            append(",\"kind\":\"").append(if (kind == Kind.DEPARTED) "DEPARTED" else "ARRIVED").append('"')
            append(",\"railMinute\":").append(railMinute)
            append(",\"reportedAt\":").append(reportedAtMillis)
            append("}")
        }
    }

    companion object {
        /** Reads back exactly what [toJson] writes; anything else is null, never a half-report. */
        fun fromJson(raw: String): StopReport? {
            fun field(key: String): String? {
                val needle = "\"$key\":"
                val i = raw.indexOf(needle)
                if (i < 0) return null
                val rest = raw.substring(i + needle.length).trimStart()
                if (rest.startsWith("\"")) {
                    val end = raw.indexOf('"', i + needle.length + 1)
                    if (end < 0) return null
                    return raw.substring(i + needle.length + 1, end)
                }
                val end = rest.takeWhile { it.isDigit() }.ifEmpty { return null }
                return end
            }
            val trainNo = field("trainNo") ?: return null
            val stationCode = field("stationCode") ?: return null
            val kind = when (field("kind")) {
                "DEPARTED" -> Kind.DEPARTED
                "ARRIVED" -> Kind.ARRIVED
                else -> return null
            }
            val railMinute = field("railMinute")?.toIntOrNull() ?: return null
            val reportedAt = field("reportedAt")?.toLongOrNull() ?: return null
            if (trainNo.isEmpty() || stationCode.isEmpty()) return null
            if (railMinute !in 0..1439) return null
            return StopReport(trainNo, stationCode, kind, railMinute, reportedAt)
        }
    }
}
