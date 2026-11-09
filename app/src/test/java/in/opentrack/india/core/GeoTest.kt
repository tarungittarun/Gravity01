package `in`.opentrack.india.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The great-circle helper behind "which station am I near" and the GPS plausibility check. It is tested
 * against a physical constant rather than another implementation of the same formula, because a wrong
 * radius here would mis-rank nearby stations on a 20 km scale.
 */
class GeoTest {

    @Test
    fun zeroDistanceAndSymmetry() {
        assertEquals(0.0, Geo.kmBetween(13.0827, 80.2707, 13.0827, 80.2707), 1e-9)
        val a = Geo.kmBetween(13.0827, 80.2707, 28.6139, 77.2090)
        val b = Geo.kmBetween(28.6139, 77.2090, 13.0827, 80.2707)
        assertEquals(a, b, 1e-9)
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111km() {
        val km = Geo.kmBetween(10.0, 78.0, 11.0, 78.0)
        assertEquals(111.19, km, 0.25)
    }

    @Test
    fun knownIndianPairs() {
        // Chennai Central -> New Delhi, station coordinates.
        val masToNdls = Geo.kmBetween(13.0827, 80.2707, 28.6139, 77.2090)
        assertEquals(1755.8, masToNdls, 5.0)
        // Mumbai Central -> New Delhi, the corridor the reference DB covers densely.
        val bctToNdls = Geo.kmBetween(18.9402, 72.8355, 28.6139, 77.2090)
        assertEquals(1163.8, bctToNdls, 5.0)
        // A degree of longitude shrinks with the cosine of the latitude; the cos(lat) term in the
        // formula is what makes "nearest station" correct east-west as well as north-south. Asserted
        // as the ratio rather than as a free-form "smaller" bound, because a missing cos() is a 6 %
        // error at 20°N and a vague inequality would let a 50 % error through.
        val eq = Geo.kmBetween(0.0, 0.0, 0.0, 1.0)
        val at20 = Geo.kmBetween(20.0, 0.0, 20.0, 1.0)
        assertEquals(eq * Math.cos(Math.toRadians(20.0)), at20, 1.0)
        // A degree of latitude is the same number everywhere, which is the other half of that check.
        assertEquals(eq, Geo.kmBetween(10.0, 78.0, 11.0, 78.0), 1.0)
    }
}
