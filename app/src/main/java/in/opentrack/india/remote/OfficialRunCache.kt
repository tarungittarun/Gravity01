package `in`.opentrack.india.remote

import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONArray
import org.json.JSONObject

/** Private, per-run last-known official table. Never an additional live source.
 * Schedule and actuals must travel together: replaying STNS actuals on an unrelated bundled
 * route was precisely the bug seen on 16227. No PNR, GPS, cookies or passenger data is stored. */
internal object OfficialRunCache {
    fun encode(snapshot: LiveSnapshot): String {
        val route = requireNotNull(snapshot.reportedRoute)
        val stops = JSONArray()
        for (s in route.stops) stops.put(JSONObject().apply {
            put("code", s.code); put("name", s.name); put("seq", s.seq)
            s.arrivalMin?.let { put("arr", it) }
            s.departureMin?.let { put("dep", it) }
            put("km", s.schedKm); put("halt", s.isHalt)
        })
        return JSONObject().apply {
            put("schema", 1)
            put("train", snapshot.trainNo)
            put("day", snapshot.journeyEpochDay)
            put("saved", snapshot.fetchedAtMillis)
            snapshot.feedStampMillis?.let { put("stamp", it) }
            put("name", route.trainName)
            put("stops", stops)
            put("snapshot", JSONObject(SnapshotCodec.encode(snapshot)))
            put("savedClocks", JSONArray(snapshot.savedOfficialClocks.sorted()))
        }.toString()
    }

    fun decode(text: String, train: String, day: Long, from: String, to: String,
               nowMillis: Long): LiveSnapshot? = runCatching {
        val root = JSONObject(text)
        if (root.optInt("schema") != 1 || root.optString("train") != train ||
            root.optLong("day", -1) != day) return null
        val saved = root.optLong("saved", 0L)
        if (saved <= 0 || saved > nowMillis + 120_000L || nowMillis - saved > MAX_AGE_MS) return null
        val array = root.optJSONArray("stops") ?: return null
        if (array.length() < 3 || array.length() > 1000) return null
        val stops = (0 until array.length()).map { i ->
            val o = array.optJSONObject(i) ?: return null
            val code = o.getString("code")
            StopRow(
                seq = i + 1, code = code, name = o.optString("name").ifBlank { code },
                state = null, lat = null, lon = null,
                arrivalMin = o.optInt("arr").takeIf { o.has("arr") },
                departureMin = o.optInt("dep").takeIf { o.has("dep") },
                journeyDay = (o.optInt("dep").takeIf { o.has("dep") }
                    ?: o.optInt("arr").takeIf { o.has("arr") } ?: 0) / 1440,
                schedKm = o.optInt("km", -1), isHalt = (o.opt("halt") as? Boolean ?: true),
            )
        }
        val codes = stops.map { it.code.uppercase() }
        if (// Real NTES routes include one-letter station codes (e.g. Y on 16227). Rejecting
        // those made a freshly saved 29-halt official run unreadable on the next timeout.
        codes.any { !it.matches(Regex("[A-Z0-9]{1,6}")) } || codes.distinct().size != codes.size ||
            stops.first().departureMin == null || stops.last().arrivalMin == null) return null
        val a = codes.indexOf(from.uppercase())
        if (a < 0 || codes.indexOf(to.uppercase()) <= a) return null
        val segments = stops.zipWithNext { x, y ->
            SegmentRow(x.seq, x.code, y.code,
                if (x.schedKm >= 0 && y.schedKm >= x.schedKm) y.schedKm - x.schedKm else 0,
                x.departureMin?.let { d -> y.arrivalMin?.let { (it - d).coerceAtLeast(0) } })
        }
        val route = TrainRoute(train, root.optString("name").ifBlank { "Train $train" },
            stops.first().code, stops.first().name, stops.last().code, stops.last().name,
            stops.last().schedKm.coerceAtLeast(0),
            stops.last().arrivalMin?.let { it - (stops.first().departureMin ?: 0) },
            null, null, stops, segments)
        val snap = SnapshotCodec.decode((root.optJSONObject("snapshot") ?: return null).toString()) ?: return null
        if (snap.trainNo != train || snap.journeyEpochDay != day || snap.perStop.keys.none { it in codes })
            return null
        val stamp = root.optLong("stamp", 0L).takeIf { it > 0L }
        if (stamp != null && (stamp > nowMillis + 120_000L || stamp < saved - MAX_AGE_MS)) return null
        val savedClocks = root.optJSONArray("savedClocks")?.let { cells ->
            (0 until cells.length()).mapNotNull { cells.optString(it).takeIf { key ->
                key.matches(Regex("[A-Z0-9]{1,6}:(arr|dep)"))
            } }.toSet()
        }.orEmpty()
        snap.copy(sourceKind = SourceKind.CACHED, fetchedAtMillis = saved,
            feedStampMillis = stamp, reportedRoute = route, savedOfficialClocks = savedClocks,
            rawNote = "Last known official NTES report; not a current observation")
    }.getOrNull()

    /** Carry only ALREADY ACCEPTED official per-stop minutes when a later official
     * reply omits that cell. No interpolation, website minute, schedule or ETA is
     * admitted here. Never let a historical cell set the latest anchor/event age.
     * A changed official route or newer conflicting actual invalidates the old cell. */
    fun retainAccepted(fresh: LiveSnapshot, previous: LiveSnapshot?): LiveSnapshot {
        val currentRoute = fresh.reportedRoute ?: return fresh
        val oldRoute = previous?.reportedRoute ?: return fresh
        if (previous.sourceKind != SourceKind.CACHED ||
            previous.trainNo != fresh.trainNo || previous.journeyEpochDay == null ||
            previous.journeyEpochDay != fresh.journeyEpochDay ||
            previous.fetchedAtMillis > fresh.fetchedAtMillis ||
            fresh.fetchedAtMillis - previous.fetchedAtMillis > MAX_AGE_MS ||
            oldRoute.stops.map { Triple(it.code.uppercase(), it.arrivalMin, it.departureMin) } !=
            currentRoute.stops.map { Triple(it.code.uppercase(), it.arrivalMin, it.departureMin) } ||
            (previous.feedStampMillis != null && fresh.feedStampMillis != null &&
                previous.feedStampMillis > fresh.feedStampMillis)) return fresh
        val anchorIndex = currentRoute.stops.indexOfFirst { it.code.equals(fresh.atCode, true) }
        val event = fresh.eventMinute ?: return fresh
        if (anchorIndex < 0) return fresh
        val merged = fresh.perStop.toMutableMap()
        val saved = mutableSetOf<String>()
        for ((index, stop) in currentRoute.stops.withIndex()) {
            if (index > anchorIndex) break
            val code = stop.code.uppercase()
            val old = previous.perStop[code] ?: continue
            val now = merged[code]
            if (now?.cancelled == true || old.cancelled) continue
            fun retain(clock: Int?, scheduled: Int?): Int? = clock?.takeIf {
                scheduled != null && it != scheduled &&
                    it - scheduled in -10..180 && it <= event
            }
            val arr = if (now?.actualArrivalMin == null)
                retain(old.actualArrivalMin, stop.arrivalMin) else null
            val dep = if (now?.actualDepartureMin == null)
                retain(old.actualDepartureMin, stop.departureMin) else null
            if (arr == null && dep == null) continue
            if (arr != null) saved += "$code:arr"
            if (dep != null) saved += "$code:dep"
            merged[code] = (now ?: `in`.opentrack.india.model.LiveStopFields()).copy(
                actualArrivalMin = now?.actualArrivalMin ?: arr,
                actualDepartureMin = now?.actualDepartureMin ?: dep,
                delayArrivalMin = now?.delayArrivalMin ?: old.delayArrivalMin.takeIf { arr != null },
                delayDepartureMin = now?.delayDepartureMin ?: old.delayDepartureMin.takeIf { dep != null },
            )
        }
        return fresh.copy(perStop = merged, savedOfficialClocks = saved)
    }

    const val MAX_AGE_MS = 24 * 60 * 60_000L
}
