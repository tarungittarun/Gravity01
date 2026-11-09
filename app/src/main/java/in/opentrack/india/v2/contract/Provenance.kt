package `in`.opentrack.india.v2.contract

/**
 * The v2 data contract: every displayed fact is a value plus the answer to "how do you know, and when".
 *
 * This file exists because of how v1 actually failed. It did not crash, and its layout was not wrong - it
 * showed a train number that no longer belonged to that train, a time four months (in truth a couple of
 * years) out of date, and a "Next stop in 21 min" computed from it, all in the same confident typeface a
 * live report uses. A passenger cannot tell those apart, so the type system has to: in v2 nothing reaches a
 * screen without a [Provenance], and the wording that proves it comes from exactly one function.
 *
 * Pure JVM on purpose - no Android imports, no SQLite, no HTTP - so `./gradlew test` is a real gate for it
 * and every rule below can be argued about with a failing test rather than a screenshot.
 */
enum class SourceKind(val live: Boolean) {
    /** A per-stop actual from a live feed. The only kind that may ever be described as "running status". */
    LIVE_STOPS(true),

    /** The live list of trains between two stations - the question v1 could not ask, and "trains are missing" is the answer it guessed. */
    LIVE_PAIR(true),

    /** A live answer to "what train is this number today", which is what a renumbering actually changes. */
    LIVE_IDENTITY(true),

    /** Something fetched earlier and kept. Shown, never advertised as current. */
    CACHE(false),

    /** The read-only timetable baked into the APK. Its age is a property of the asset, not of today. */
    OFFLINE_ASSET(false),
}

/**
 * Provenance of one fact. [fetchedAtMillis] is when *this device* got it; `0` means nobody said, which is
 * treated as the least trustworthy state rather than as "now" - the mistake that made a build timestamp read
 * like a data vintage.
 */
data class Provenance(
    val kind: SourceKind,
    val source: String,
    val fetchedAtMillis: Long,
) {
    fun ageMillis(nowMillis: Long): Long = (nowMillis - fetchedAtMillis).coerceAtLeast(0L)

    fun isFresh(nowMillis: Long): Boolean =
        fetchedAtMillis > 0L && ageMillis(nowMillis) <= Freshness.ttlMillis(kind)

    /** Fresh enough to act on, whatever the caller's opinion. */
    fun isAuthoritative(nowMillis: Long): Boolean = kind.live && isFresh(nowMillis)

    /**
     * The suffix a row must carry. One function, because v1 had several and at least one of them lied.
     * The strings here are the only acceptable vocabulary: "timetable" for baked-in data, "via <source>" for
     * fresh live data, and an explicit age the moment live data has gone past its shelf life.
     */
    fun label(nowMillis: Long): String = when {
        kind == SourceKind.OFFLINE_ASSET -> "timetable"
        fetchedAtMillis <= 0L -> "no fetch time - treat as stale"
        !kind.live -> "cached ${ageText(nowMillis)} ago"
        !isFresh(nowMillis) -> "live data ${ageText(nowMillis)} old"
        else -> "via $source"
    }

    private fun ageText(nowMillis: Long): String {
        val minutes = ageMillis(nowMillis) / 60_000L
        return when {
            minutes < 1 -> "moments"
            minutes < 60 -> "$minutes min"
            minutes < 60 * 24 -> "${minutes / 60} h"
            else -> "${minutes / (60 * 24)} d"
        }
    }
}

/**
 * Shelf life per kind, in one place, so no screen can invent its own idea of "current".
 *
 * The numbers are the railway's, not ours: NTES-class feeds recompute running status every few minutes, and
 * a timetable revision happens roughly quarterly - which is precisely the window in which v1's asset sat
 * unchanged while looking authoritative.
 */
object Freshness {
    const val LIVE_STOPS_MS = 5L * 60_000L
    const val LIVE_PAIR_MS = 10L * 60_000L
    const val LIVE_IDENTITY_MS = 24L * 3_600_000L
    const val CACHE_MS = 6L * 3_600_000L
    const val ASSET_MS = 90L * 24L * 3_600_000L

    fun ttlMillis(kind: SourceKind): Long = when (kind) {
        SourceKind.LIVE_STOPS -> LIVE_STOPS_MS
        SourceKind.LIVE_PAIR -> LIVE_PAIR_MS
        SourceKind.LIVE_IDENTITY -> LIVE_IDENTITY_MS
        SourceKind.CACHE -> CACHE_MS
        SourceKind.OFFLINE_ASSET -> ASSET_MS
    }
}
