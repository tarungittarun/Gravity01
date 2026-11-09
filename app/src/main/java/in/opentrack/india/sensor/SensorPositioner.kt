package `in`.opentrack.india.sensor

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import `in`.opentrack.india.core.Geo
import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.model.TrainRoute
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Uses the device's own radio/GPS as a *second opinion* about where the train is.
 *
 * Two properties matter here and both come from the case study:
 *  - it must be optional. The app never blocks on a permission prompt; if the user declines, live
 *    tracking continues from the network path alone. Nothing in the product requires location.
 *  - it must be a measurement, not a tracker. One fix, in a bounded window, no history, no upload,
 *    no background use — so `ACCESS_COARSE_LOCATION` cannot become "the app is watching me".
 *
 * The fix is used for exactly two things: detecting that the live feed has gone stale while the user
 * is visibly moving along the route ([PositionEngine.GpsEvidence]), and answering "how far from my
 * boarding station am I" on the results screen.
 */
class SensorPositioner(private val context: Context) {

    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Float,
        val speedKmh: Float,
        val movedMeters: Float,
        val atMillis: Long,
    )

    @Volatile
    private var lastFix: Fix? = null

    fun latest(): Fix? = lastFix

    /**
     * Blocking single-shot fix, at most [timeoutMs]. Call on an IO dispatcher.
     *
     * Order of attempts: (1) any recent cached system fix — free, and on a train the phone usually has
     * one from the network stack; (2) a live GPS read with a listener fallback. We deliberately use
     * the platform LocationManager rather than Fused Location Provider: FLP needs Play Services,
     * which is a third-party runtime dependency on a huge slice of Indian devices, and the accuracy
     * difference is irrelevant when the question is "which station is nearest".
     */
    @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun singleFix(timeoutMs: Long = 4_000): Fix? {
        lastFix?.let { if (System.currentTimeMillis() - it.atMillis < timeoutMs) return it }
        val mgr = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER)
        for (name in providers) {
            val best: Location? = runCatching { mgr.getLastKnownLocation(name) }.getOrNull()
            if (best != null && isFresh(best)) return adopt(best, null)
        }
        val latch = CountDownLatch(1)
        var captured: Location? = null
        var listener: android.location.LocationListener? = null
        try {
            val minTime = maxOf(500L, timeoutMs / 4)
            listener = android.location.LocationListener { loc ->
                if (captured == null || loc.accuracy < (captured?.accuracy ?: Float.MAX_VALUE)) {
                    captured = loc
                    latch.countDown()
                }
            }
            val provider = providers.firstOrNull { runCatching { mgr.isProviderEnabled(it) }.getOrDefault(false) }
                ?: return lastFix
            runCatching { mgr.requestLocationUpdates(provider, minTime, 0f, listener, Looper.getMainLooper()) }
                .onFailure { return null }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { listener?.let { mgr.removeUpdates(it) } }
        }
        val loc = captured ?: return lastFix
        return adopt(loc, lastFix)
    }

    private fun isFresh(loc: Location): Boolean =
        System.currentTimeMillis() - loc.time < FRESH_MS

    private fun adopt(loc: Location, previous: Fix?): Fix {
        val moved = previous?.let {
            (Geo.kmBetween(it.lat, it.lon, loc.latitude, loc.longitude) * 1000).toFloat()
        } ?: 0f
        val fix = Fix(
            lat = loc.latitude, lon = loc.longitude,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE,
            speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else -1f,
            movedMeters = moved, atMillis = System.currentTimeMillis(),
        )
        lastFix = fix
        return fix
    }

    /**
     * Convert a fix into the evidence [PositionEngine] wants: the nearest *scheduled* stop of this
     * train and how far we are from it. Restricting the candidate set to the train's own route is
     * what makes this safe to trust — a station 200 m away that this train never touches must not be
     * able to move the marker.
     */
    fun evidence(fix: Fix, route: TrainRoute): PositionEngine.GpsEvidence? {
        var bestSeq = -1
        var bestKm = Float.MAX_VALUE
        var bestCode: String? = null
        for (s in route.stops) {
            val lat = s.lat ?: continue
            val lon = s.lon ?: continue
            val km = Geo.kmBetween(fix.lat, fix.lon, lat, lon).toFloat()
            if (km < bestKm) { bestKm = km; bestSeq = s.seq; bestCode = s.code }
        }
        if (bestSeq < 0) return null
        // A fix 60 km from the nearest scheduled stop says "you are not on this train", not "the
        // train is here" — refuse to influence the marker in that case.
        if (bestKm > MAX_INFLUENCE_KM || fix.accuracyM > MAX_ACCURACY_M) return null
        return PositionEngine.GpsEvidence(
            nearestStationCode = bestCode, nearestStationSeq = bestSeq,
            distanceKm = bestKm, groundSpeedKmh = fix.speedKmh, accuracyM = fix.accuracyM,
        )
    }

    /** True when the device is moving at train-like speed: the "you are on the train" heuristic. */
    fun looksLikeOnBoard(fix: Fix): Boolean = fix.speedKmh in 8f..160f || abs(fix.movedMeters) > 250f

    companion object {
        const val FRESH_MS = 90_000L
        const val MAX_ACCURACY_M = 250f
        const val MAX_INFLUENCE_KM = 45f
    }
}
