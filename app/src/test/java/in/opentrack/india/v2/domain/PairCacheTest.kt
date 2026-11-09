package `in`.opentrack.india.v2.domain

import `in`.opentrack.india.v2.contract.PairListing
import `in`.opentrack.india.v2.contract.Provenance
import `in`.opentrack.india.v2.contract.SourceKind
import `in`.opentrack.india.v2.contract.TrainIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairCacheTest {

    private val t0 = 1_760_000_000_000L

    // vararg FIRST: as positional `listing("16226")` used to bind the train number to `from`,
    // producing an empty listing the cache correctly refused - and the test NPE'd (898b196).
    private fun listing(vararg numbers: String, from: String = "SMET", to: String = "MYS") =
        PairListing(
            from, to,
            numbers.map { n ->
                PairListing.TrainAtStation(
                    TrainIdentity(n, "NAME $n", from, to, Provenance(SourceKind.LIVE_PAIR, "erail", t0))
                )
            },
            Provenance(SourceKind.LIVE_PAIR, "erail", t0),
        )

    @Test
    fun aFreshEntryComesBackAndAStaleOneDoesNot() {
        val cache = PairCache(ttlMillis = 10 * 60_000L)
        cache.put(listing("16226"), t0)
        assertEquals(listOf("16226"), cache.get("SMET", "MYS", t0 + 60_000L)!!.listing.numbers())
        assertNull("past the TTL a cache read is not an answer", cache.get("SMET", "MYS", t0 + 601_000L))
        // but the stale entry is readable for what it is: a cached thing with an age
        assertEquals(listOf("16226"), cache.peek("SMET", "MYS")!!.listing.numbers())
    }

    @Test
    fun anEmptyAnswerIsNeverCached() {
        // "nothing runs" from a moment ago must not become "nothing runs" half an hour later: an
        // empty answer is a now-fact, and caching it fabricates a fresh negative (2.3).
        val cache = PairCache()
        cache.put(listing(), t0)
        assertNull(cache.peek("SMET", "MYS"))
    }

    @Test
    fun theKeyIsCaseInsensitiveOnStationCodes() {
        val cache = PairCache()
        cache.put(listing("16226"), t0)
        assertEquals(listOf("16226"), cache.get("smet", "mys", t0 + 1_000L)!!.listing.numbers())
    }

    @Test
    fun oldestEntriesFallOffTheFixedCapacity() {
        val cache = PairCache(capacity = 2)
        cache.put(listing("11111", from = "A", to = "B"), t0)
        cache.put(listing("22222", from = "C", to = "D"), t0 + 1)
        cache.put(listing("33333", from = "E", to = "F"), t0 + 2)
        assertNull(cache.peek("A", "B"))
        assertEquals(listOf("33333"), cache.peek("E", "F")!!.listing.numbers())
    }
}
