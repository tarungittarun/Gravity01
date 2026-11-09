package `in`.opentrack.india.v2.domain

import `in`.opentrack.india.v2.contract.PairListing
import `in`.opentrack.india.v2.contract.Freshness

/**
 * TTL cache for live pair listings, keyed by station pair.
 *
 * A cache read is NEVER live (brief 7.5): everything that comes out of here carries the [Entry]'s
 * fetched-at time, and the caller renders it with the cached-age wording, not "via <source>". The clock
 * is injected because a cache that checks freshness against the wall clock cannot be tested - the same
 * reason freshness compares against a feed stamp, not System.now.
 *
 * Pure JVM, fixed capacity (a passenger visits a handful of pairs; 16 is generous), no eviction
 * cleverness: oldest-fetched out.
 */
class PairCache(
    private val capacity: Int = 16,
    private val ttlMillis: Long = Freshness.LIVE_PAIR_MS,
) {
    data class Entry(val listing: PairListing, val fetchedAtMillis: Long) {
        fun ageMillis(nowMillis: Long): Long = (nowMillis - fetchedAtMillis).coerceAtLeast(0L)
        /** TTL is a parameter, not an outer-scope lookup: a nested class cannot see the cache's
         *  property, and the compiler - not a local gate - is what catches that (runs 3b7a14d+). */
        fun isFresh(nowMillis: Long, ttlMillis: Long): Boolean = ageMillis(nowMillis) <= ttlMillis
    }

    private val map = LinkedHashMap<String, Entry>(capacity)

    fun key(fromCode: String, toCode: String) = "${fromCode.uppercase()}->${toCode.uppercase()}"

    fun put(listing: PairListing, fetchedAtMillis: Long) {
        if (listing.trains.isEmpty()) return // an empty answer is not a fact worth caching (2.3)
        while (map.size >= capacity) {
            map.remove(map.keys.first())
        }
        map[key(listing.fromCode, listing.toCode)] = Entry(listing, fetchedAtMillis)
    }

    /** Fresh entry or null. A stale entry is readable via [peek] but is never answered as if current. */
    fun get(fromCode: String, toCode: String, nowMillis: Long): Entry? =
        peek(fromCode, toCode)?.takeIf { it.isFresh(nowMillis, ttlMillis) }

    /** The entry however old - the caller owns the honesty about its age. */
    fun peek(fromCode: String, toCode: String): Entry? = map[key(fromCode, toCode)]

    fun clear() = map.clear()
}
