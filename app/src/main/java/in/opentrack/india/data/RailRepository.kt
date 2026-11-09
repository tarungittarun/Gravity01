package `in`.opentrack.india.data

import android.content.Context
import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.core.BrowserFetch
import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.domain.TrainFinder
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.remote.ErailProvider
import `in`.opentrack.india.remote.ProviderChain
import `in`.opentrack.india.remote.OfficialScheduleCache
import `in`.opentrack.india.remote.SnapshotStore
import `in`.opentrack.india.sensor.SensorPositioner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import `in`.opentrack.india.model.StationHit
import `in`.opentrack.india.model.TrainSummary

/**
 * The only object the UI talks to. It exists to enforce three invariants that are easy to break when
 * a screen reaches for data directly:
 *
 *  1. **Offline first, always.** Search and timetable come from the on-device SQLite and are served
 *     with no network call at all; the network is only ever asked for the live delta.
 *  2. **Degradation is data, not an exception.** Every live read returns a [Live] whose [SourceKind]
 *     says how much of it is real, so a screen can render "position as of 04:12, feed unreachable"
 *     instead of a spinner or a crash.
 *  3. **One clock.** The rail-minute axis is computed here from the run's origin date, so no view has
 *     to know that stops.arrival_min is minutes from origin midnight.
 */
class RailRepository private constructor(
    private val context: Context,
    private val ref: RefDb,
    private val http: Http,
    private val store: SnapshotStore,
    val prefs: Prefs,
) {

    val queries = RefQueries(ref)
    private val sensors = SensorPositioner(context)

    data class Live(
        val route: TrainRoute,
        val answer: PositionEngine.Answer,
        val tried: List<String>,
        val originEpochDay: Long,
        /** why each live source that declined did so, in the sources' own sentences */
        val notes: List<String> = emptyList(),
        /** Current request carried a separately validated official static schedule. */
        val officialScheduleVerified: Boolean = false,
    ) {
        val provenance: SourceKind get() = answer.position.provenance
    }

    suspend fun search(fromText: String, toText: String, epochDay: Long, minuteOfDay: Int) =
        withContext(Dispatchers.IO) {
            TrainFinder.search(queries, fromText, toText, epochDay, minuteOfDay)
        }

    suspend fun route(trainNo: String): TrainRoute? = withContext(Dispatchers.IO) {
        queries.route(trainNo)
    }

    /**
     * Trains whose number or name matches a fragment, for the type-ahead in "train number" mode. Same
     * shape as [resolveStations]: an IO-dispatched read of the on-device master, never on the UI thread.
     */
    suspend fun findTrains(raw: String): List<TrainSummary> = withContext(Dispatchers.IO) {
        runCatching { queries.findTrains(raw) }.getOrDefault(emptyList())
    }

    suspend fun resolveStations(text: String) = withContext(Dispatchers.IO) {
        queries.resolveStations(text)
    }

    /** User-requested, independent erail status probe. Never falls back to NTES or a file. */
    suspend fun probeErail(trainNo: String, route: TrainRoute, day: Long): Live? =
        withContext(Dispatchers.IO) {
            val provider = ErailProvider(http, ErailProvider.DEFAULT_BASE,
                prefs.columnHint.ifBlank { null }, null)
            val snap = provider.fetch(trainNo, route, day)
                ?.takeIf { it.trainNo == trainNo && it.journeyEpochDay == day && it.sourceKind.live }
                ?: return@withContext null
            // The list endpoint's old date stamp cannot verify a different origin run.
            val stamp = snap.feedStampMillis ?: return@withContext null
            val now = System.currentTimeMillis()
            if (RailTime.istEpochDay(stamp) !in day..(day + 1) ||
                now - stamp !in 0..PositionEngine.STALE_MS) return@withContext null
            val feedRail = ((RailTime.istEpochDay(stamp) - day) * 1440 +
                RailTime.istMinuteOfDay(stamp)).toInt()
            val byCode = route.stops.associateBy { it.code.uppercase() }
            val safe = snap.perStop.mapNotNull { (code, f) ->
                val stop = byCode[code.uppercase()] ?: return@mapNotNull null
                fun past(raw: Int?, sch: Int?): Int? {
                    val aligned = RailTime.alignClock(raw, sch) ?: return null
                    return aligned.takeIf { sch != null && it != sch &&
                        it - sch in -10..180 && it <= feedRail + 2 }
                }
                val arr = past(f.actualArrivalMin, stop.arrivalMin)
                val dep = past(f.actualDepartureMin, stop.departureMin)
                if (arr == null && dep == null) null else code to f.copy(
                    actualArrivalMin = arr, actualDepartureMin = dep,
                    delayArrivalMin = f.delayArrivalMin.takeIf { arr != null },
                    delayDepartureMin = f.delayDepartureMin.takeIf { dep != null })
            }.toMap()
            if (safe.isEmpty()) return@withContext null
            val anchorStop = route.stops.lastOrNull { it.code in safe } ?: return@withContext null
            val anchor = safe[anchorStop.code] ?: return@withContext null
            val dep = anchor.actualDepartureMin
            val checked = snap.copy(perStop = safe, atCode = anchorStop.code,
                event = if (dep != null) `in`.opentrack.india.model.StopStatus.DEPARTED
                    else `in`.opentrack.india.model.StopStatus.ARRIVED,
                eventMinute = dep ?: anchor.actualArrivalMin,
                scheduledMinuteAtEvent = if (dep != null) anchorStop.departureMin else anchorStop.arrivalMin,
                delayMinutes = (dep ?: anchor.actualArrivalMin)?.let { actual ->
                    (anchorStop.departureMin.takeIf { dep != null } ?: anchorStop.arrivalMin)
                        ?.let { actual - it } },
                nextCode = route.stopAtSeq(anchorStop.seq + 1)?.code, expectedNextMinute = null)
            val minute = ((RailTime.istEpochDay(now) - day) * 1440 +
                RailTime.istMinuteOfDay(now)).toInt()
            val answer = PositionEngine.locate(PositionEngine.Request(route, checked, minute, now))
            Live(route, answer, listOf("erail=manual-hit"), day)
        }

    /** The previous dated official SCHEDULE can paint before any network request, including a train
     * absent from the bundled database. It has no observed minutes and cannot locate a train. */
    suspend fun savedSchedule(trainNo: String, from: String, to: String, day: Long): TrainRoute? =
        withContext(Dispatchers.IO) {
            val raw = context.getSharedPreferences("official_schedules", Context.MODE_PRIVATE)
                .getString("$trainNo:$day", null)
            OfficialScheduleCache.decode(raw, trainNo, day, from, to, System.currentTimeMillis())
        }

    /** Instant last-known official run for a tunnel: a paired route + observations, never live. */
    suspend fun lastOfficial(trainNo: String, from: String, to: String, day: Long): Live? =
        withContext(Dispatchers.IO) {
            val snap = store.loadOfficial(trainNo, day, from, to) ?: return@withContext null
            val route = snap.reportedRoute ?: return@withContext null
            val now = System.currentTimeMillis()
            val rail = ((RailTime.istEpochDay(now) - day) * 1440 +
                RailTime.istMinuteOfDay(now)).toInt()
            Live(route, PositionEngine.locate(PositionEngine.Request(route, snap, rail, now)),
                listOf("NTES=last-known"), day,
                listOf("Showing a saved NTES report while checking for a newer one; " +
                    "position since that report is unknown."))
        }

    /**
     * Live view for one run: schedule from the device, live delta from the chain, position derived by
     * [PositionEngine] with the previously shown position as input (that is what makes rule 3 —
     * never move backwards — enforceable).
     */
    suspend fun live(
        trainNo: String, fromCode: String, toCode: String, originEpochDay: Long,
        previous: PositionEngine.TrainPositionInput? = null,
        /** Live-list identity's two endpoint times when the bundled DB lacks this number. */
        fallbackRoute: TrainRoute? = null,
    ): Live? = withContext(Dispatchers.IO) {
        // A run-checked 29-halt official schedule outranks the bundled 11-halt route.
        // Otherwise a transient NTES timeout silently reverts 16227 MYS→TLGP to SBC→SMET.
        val route = savedSchedule(trainNo, fromCode, toCode, originEpochDay)
            ?: queries.route(trainNo)
            ?: fallbackRoute?.takeIf { it.trainNo == trainNo && it.stops.size >= 2 }
            ?: return@withContext null
        val nowMillis = System.currentTimeMillis()
        // rail-minute "now": minutes from the origin date's midnight, in IST
        val nowRail = ((RailTime.istEpochDay(nowMillis) - originEpochDay) * RailTime.MINUTES_PER_DAY
            + RailTime.istMinuteOfDay(nowMillis)).toInt()
        val chain = ProviderChain.build(
            http, store,
            overrideUrl = prefs.overrideUrl.ifBlank { null },
            columnHint = prefs.columnHint.ifBlank { null },
            fromCode = fromCode, toCode = toCode,
        )
        val chainT0 = System.currentTimeMillis()
        val outcome = chain.fetch(trainNo, route, originEpochDay)
        Diary.get()?.log("CHAIN", "in " + (System.currentTimeMillis() - chainT0) + "ms: " +
            (outcome.notes.take(4).joinToString(" | ") { it.take(140) }))
        val snapshot = if (outcome.snapshot == null || outcome.snapshot.sourceKind == SourceKind.CACHED)
            store.loadOfficial(trainNo, originEpochDay, fromCode, toCode) ?: outcome.snapshot
        else outcome.snapshot
        // Persist the STATIC schedule independently of the actuals. A live-only number must not
        // collapse back to two endpoints next time the user opens it, even when no actual was given.
        outcome.scheduleRoute?.let { scheduled ->
            context.getSharedPreferences("official_schedules", Context.MODE_PRIVATE).edit()
                .putString("$trainNo:$originEpochDay", OfficialScheduleCache.encode(
                    scheduled, originEpochDay, System.currentTimeMillis())).apply()
        }
        val effectiveRoute = snapshot?.reportedRoute ?: outcome.scheduleRoute ?: route
        if (effectiveRoute !== route) Diary.get()?.log("ROUTE") {
            "official NTES schedule replaces device ${route.srcCode}->${route.dstCode} " +
                "(${route.stops.size} stops) with ${effectiveRoute.srcCode}->${effectiveRoute.dstCode} " +
                "(${effectiveRoute.stops.size} stops)"
        }
        val gps = if (prefs.useLocation && hasLocationPermission()) {
            sensors.singleFix()?.let { sensors.evidence(it, effectiveRoute) }
        } else null
        val answer = PositionEngine.locate(
            PositionEngine.Request(
                route = effectiveRoute,
                snapshot = snapshot,
                nowRailMinute = nowRail,
                nowMillis = nowMillis,
                previous = previous?.position?.takeIf { effectiveRoute === route },
                gps = gps,
            )
        )
        Live(effectiveRoute, answer, outcome.tried, originEpochDay, outcome.notes,
            officialScheduleVerified = outcome.scheduleRoute != null)
    }

    fun hasLocationPermission(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun nearestStationsToUser(limit: Int = 3): List<StationHit> {
        val fix = sensors.latest() ?: return emptyList()
        return queries.nearestStations(fix.lat, fix.lon, limit)
    }

    fun sensor(): SensorPositioner = sensors

    fun dataVersion(): String = queries.dataVersion()

    /**
     * Everything this app can fetch again. The browser engine's cookie jar is included because it is now part
     * of the live-feed path: a session the far end handed out can be the reason a request is refused, and a
     * user clearing the cache is asking for a clean slate, not for the app to keep one in another directory.
     */
    fun clearCaches() {
        store.clear()
        BrowserFetch.clearCookies()
        http.clearCache()
        prefs.clearCaches()
    }

    companion object {
        @Volatile
        private var shared: RailRepository? = null

        /**
         * Single instance on purpose: the extracted DB handle and the HTTP cache are process-wide
         * resources, and opening the read-only database per screen would re-run SQLite's header check
         * on a 14 MB file, which is measurable on low-end hardware.
         */
        fun get(context: Context, ref: RefDb): RailRepository = shared ?: synchronized(this) {
            shared ?: RailRepository(
                context = context.applicationContext,
                ref = ref,
                http = Http(context.applicationContext),
                store = SnapshotStore(context.applicationContext),
                prefs = Prefs(context.applicationContext),
            ).also { shared = it }
        }
    }
}
