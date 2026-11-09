package `in`.opentrack.india.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance. The only geometry the app needs; no Play Services, no map SDK. */
object Geo {

    private const val R_KM = 6371.0088

    fun kmBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p = Math.PI / 180.0
        val dLat = (lat2 - lat1) * p
        val dLon = (lon2 - lon1) * p
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1 * p) * cos(lat2 * p) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R_KM * asin(min(1.0, sqrt(a)))
    }
}
