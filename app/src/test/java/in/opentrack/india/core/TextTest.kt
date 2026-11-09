package `in`.opentrack.india.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Station/train name matching. The upstream dump spells names inconsistently ("mumbai central",
 * "MUMBAI CENTRAL", "Mumbai Central - Terminus"), and the search box is the only place a user can
 * recover from that, so normalisation is load-bearing rather than cosmetic.
 */
class TextTest {

    @Test
    fun normaliseCollapsesCasePunctuationAndSpacing() {
        assertEquals("MUMBAI CENTRAL", Text.normalise("  mumbai   central  "))
        assertEquals("MUMBAI CENTRAL", Text.normalise("Mumbai_Central"))
        assertEquals("NEW DELHI", Text.normalise("New-Delhi"))
        assertEquals("SBC", Text.normalise("sbc\t"))
        assertEquals("", Text.normalise(null))
    }

    @Test
    fun sameTreatsBlankAsMissing() {
        assertTrue(Text.same("mas", "MAS"))
        assertFalse(Text.same("mas", "msc"))
        // A missing value is never "equal" to a blank one: callers use this to decide whether two
        // feed rows are the same station, and null means "the feed did not say".
        assertFalse(Text.same(null, ""))
        assertTrue(Text.same("", "  "))
    }

    @Test
    fun escapeLikeProtectsTheWildcard() {
        // Without this, a query of "%" returns the whole station table and "%" looks like a bug.
        assertEquals("\\%", Text.escapeLike("%"))
        assertEquals("a\\%b", Text.escapeLike("a%b"))
        assertEquals("a\\_b", Text.escapeLike("a_b"))
        // A backslash must double up first, or the escapes above would re-escape themselves.
        assertEquals("\\\\", Text.escapeLike("\\"))
    }

    @Test
    fun tokenMatchRequiresEveryQueryTokenToPrefixATargetToken() {
        assertTrue(Text.tokenMatch("CHENNAI CENT", "MASS CHENNAI CENTRAL"))
        assertTrue(Text.tokenMatch("cent chennai", "Chennai Central"))
        assertFalse(Text.tokenMatch("delhi", "Chennai Central"))
        // Single letters are noise: "a" must not match half of India.
        assertFalse(Text.tokenMatch("a", "AHMEDABAD"))
    }

    @Test
    fun editDistanceIsCappedForSpeed() {
        assertEquals(0, Text.editDistance("MAS", "MAS"))
        // A transposition costs two in plain Levenshtein, which is exactly what we want here: "MSA" is
        // a fat-fingered code, and 2 is the widest distance the resolver still accepts.
        assertEquals(2, Text.editDistance("MAS", "MSA"))
        assertEquals(1, Text.editDistance("MAS", "MS"))
        assertTrue(Text.editDistance("MAS", "ABC") > 2)
        // The cap exists because the query path calls this per candidate row: anything past it is
        // reported as "too far" without finishing the matrix.
        assertEquals(3, Text.editDistance("MAS", "XYZQQW", cap = 2))
    }
    @Test
    fun countsGroupWithoutDependingOnTheDeviceLocale() {
        // `%,d` would use the device locale and can print a non-breaking or a thin space; the counts in
        // this app (trains, stations, stops) are printed beside Indian Railways' own figures, so they use
        // the same plain comma grouping.
        assertEquals("0", Text.group(0))
        assertEquals("999", Text.group(999))
        assertEquals("1,000", Text.group(1000))
        assertEquals("8,990", Text.group(8990))
        assertEquals("141,908", Text.group(141908))
        assertEquals("14,680,064", Text.group(14680064))
    }

}
