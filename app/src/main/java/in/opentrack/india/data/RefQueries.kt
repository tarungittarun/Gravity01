package `in`.opentrack.india.data

import android.database.Cursor
import `in`.opentrack.india.core.Text
import `in`.opentrack.india.model.PairStop
import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.StationHit
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.model.TrainSummary

/**
 * Every read against the bundled timetable, in one file, so the index claims in
 * docs/ARCHITECTURE.md can be audited against the real statements and there is exactly one place
 * that knows the refdb schema.
 *
 * Two rules shape this class:
 *  1. **No business logic here.** The ±24 h window and the "does it run that day" decision live in
 *     [in.opentrack.india.domain.TrainFinder] as pure functions over the rows returned below, which
 *     is what lets them be unit-tested without a device or an emulator.
 *  2. **No N+1.** The pair query is a single statement joining `station_trains` twice plus `trains`
 *     and `stops`; the route query is three statements total regardless of train length. On a 2 GB
 *     RAM Android 8 phone a per-train loop over 300 candidates is what makes a search feel broken.
 */
class RefQueries(private val ref: RefDb) {

    // ---------------------------------------------------------------- stations

    /**
     * Free-text station resolution for the two search fields.
     *
     * Preference order is decided in SQL: an exact code beats a name-prefix, which beats an
     * initials match, which beats a substring — then popularity (`rank`, = number of trains that
     * halt there). Without that, typing "mas" would rank "Masit" above "MGR Chennai Central".
     */
    fun resolveStations(raw: String, limit: Int = 12): List<StationHit> {
        val q = raw.trim().uppercase()
        if (q.length < 2) return emptyList()
        val like = "%" + Text.escapeLike(q) + "%"
        val prefix = Text.escapeLike(q) + "%"
        return ref.query(
            """
            SELECT code, name, state, zone, lat, lon, rank FROM stations
             WHERE code = ? OR name LIKE ? OR search_token LIKE ? OR initials = ?
             ORDER BY CASE WHEN code = ? THEN 0
                           WHEN search_token LIKE ? THEN 1
                           WHEN initials = ? THEN 2
                           ELSE 3 END,
                      rank DESC, name ASC
             LIMIT ?
            """.trimIndent(),
            arrayOf(q, like, prefix, q, q, prefix, q, limit.toString())
        ) { c -> c.mapStations() }
    }

    fun station(code: String): StationHit? = ref.query(
        "SELECT code, name, state, zone, lat, lon, rank FROM stations WHERE code = ?",
        arrayOf(code.uppercase())
    ) { c -> if (c.moveToNext()) c.mapStation() else null }

    /**
     * Stations near a coordinate, used only for "which station am I at" convenience and for the
     * GPS-derived fallback position. A box filter first (indexable, and the table is 9k rows) then
     * the squared-degree distance — no geodesic math in SQL, because the ranking only has to be
     * right at city scale.
     */
    fun nearestStations(lat: Double, lon: Double, limit: Int = 4): List<StationHit> = ref.query(
        """
        SELECT code, name, state, zone, lat, lon, rank FROM stations
         WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
         ORDER BY ((lat - ?) * (lat - ?) + (lon - ?) * (lon - ?)) ASC, rank DESC
         LIMIT ?
        """.trimIndent(),
        arrayOf(
            (lat - DEGREE_BOX).toString(), (lat + DEGREE_BOX).toString(),
            (lon - DEGREE_BOX).toString(), (lon + DEGREE_BOX).toString(),
            lat.toString(), lat.toString(), lon.toString(), lon.toString(), limit.toString()
        )
    ) { c -> c.mapStations() }

    /**autocomplete for "nearest major station" — same box, but only stations that matter. */
    fun nearestJunctions(lat: Double, lon: Double, minRank: Int, limit: Int = 3): List<StationHit> =
        ref.query(
            """
            SELECT code, name, state, zone, lat, lon, rank FROM stations
             WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND rank >= ?
             ORDER BY ((lat - ?) * (lat - ?) + (lon - ?) * (lon - ?)) ASC, rank DESC
             LIMIT ?
            """.trimIndent(),
            arrayOf(
                (lat - JUNCTION_BOX).toString(), (lat + JUNCTION_BOX).toString(),
                (lon - JUNCTION_BOX).toString(), (lon + JUNCTION_BOX).toString(), minRank.toString(),
                lat.toString(), lat.toString(), lon.toString(), lon.toString(), limit.toString()
            )
        ) { c -> c.mapStations() }

    // ---------------------------------------------------------------- trains

    /** "Train number or name" entry mode. */
    fun findTrains(raw: String, limit: Int = 40): List<TrainSummary> {
        val q = raw.trim()
        if (q.isEmpty()) return emptyList()
        val digits = q.filter { it.isDigit() }
        val exactNo = if (digits.length == 5) digits else q.uppercase()
        val numPrefix = digits.ifEmpty { q }.uppercase() + "%"
        val nameLike = "%" + Text.escapeLike(q.uppercase()) + "%"
        return ref.query(
            """
            SELECT train_no, name, src_code, dst_code, train_type, total_km, total_min,
                   run_days, num_halts, return_no
              FROM trains
             WHERE train_no = ? OR train_no LIKE ? OR search_token LIKE ?
             ORDER BY CASE WHEN train_no = ? THEN 0
                           WHEN train_no LIKE ? THEN 1
                           ELSE 2 END,
                      train_no ASC
             LIMIT ?
            """.trimIndent(),
            arrayOf(exactNo, numPrefix, nameLike, exactNo, numPrefix, limit.toString())
        ) { c ->
            buildList {
                while (c.moveToNext()) add(
                    TrainSummary(
                        no = c.getString(0), name = c.getString(1), src = c.getString(2),
                        dst = c.getString(3), type = if (c.isNull(4)) null else c.getString(4),
                        totalKm = if (c.isNull(5)) 0 else c.getInt(5),
                        totalMin = if (c.isNull(6)) null else c.getInt(6),
                        runDays = if (c.isNull(7)) null else c.getInt(7),
                        halts = c.getInt(8),
                        returnNo = if (c.isNull(9)) null else c.getString(9),
                    )
                )
            }
        }
    }

    /**
     * Every (train, boarding stop, alighting stop) triple where the train halts at both stations in
     * the right order. Date-window filtering is *not* done here — see [PairStop] and
     * [in.opentrack.india.domain.TrainFinder] for that.
     *
     * `fs`/`gs` are joined to pull cumulative distance, which is what makes "942 km, 17h 32m" show
     * up on a search result without a second round-trip per row.
     */
    fun pairStops(fromCode: String, toCode: String): List<PairStop> = ref.query(
        """
        SELECT f.train_no, t.name, t.train_type, t.run_days, t.src_code, t.dst_code,
               f.seq, COALESCE(f.departure_min, f.arrival_min),
               g.seq, COALESCE(g.arrival_min, g.departure_min),
               fs.sched_km, gs.sched_km
          FROM station_trains f
          JOIN station_trains g ON g.train_no = f.train_no
                               AND g.station_code = ?
                               AND g.seq > f.seq
          JOIN trains t         ON t.train_no = f.train_no
          JOIN stops fs         ON fs.train_no = f.train_no AND fs.seq = f.seq
          JOIN stops gs         ON gs.train_no = f.train_no AND gs.seq = g.seq
         WHERE f.station_code = ?
        """.trimIndent(), arrayOf(toCode, fromCode)
    ) { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    PairStop(
                        trainNo = c.getString(0), trainName = c.getString(1),
                        trainType = if (c.isNull(2)) null else c.getString(2),
                        runDays = if (c.isNull(3)) null else c.getInt(3),
                        srcCode = c.getString(4), dstCode = c.getString(5),
                        fromSeq = c.getInt(6), boardMinute = c.getInt(7),
                        toSeq = c.getInt(8), alightMinute = c.getInt(9),
                        fromKm = c.getInt(10), toKm = c.getInt(11),
                    )
                )
            }
        }
    }

    /** One row per train that passes a single station, for the "trains at this station" fallback. */
    fun trainsAtStation(code: String, limit: Int = 60): List<PairStop> = ref.query(
        """
        SELECT f.train_no, t.name, t.train_type, t.run_days, t.src_code, t.dst_code,
               f.seq, COALESCE(f.departure_min, f.arrival_min),
               f.seq, COALESCE(f.departure_min, f.arrival_min), fs.sched_km, fs.sched_km
          FROM station_trains f
          JOIN trains t ON t.train_no = f.train_no
          JOIN stops fs ON fs.train_no = f.train_no AND fs.seq = f.seq
         WHERE f.station_code = ?
         ORDER BY f.train_no, f.seq
         LIMIT ?
        """.trimIndent(), arrayOf(code, limit.toString())
    ) { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    PairStop(
                        c.getString(0), c.getString(1),
                        if (c.isNull(2)) null else c.getString(2),
                        if (c.isNull(3)) null else c.getInt(3),
                        c.getString(4), c.getString(5),
                        c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9),
                        c.getInt(10), c.getInt(11),
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------- route

    /** Full timetable + per-leg distances for a train. */
    fun route(trainNo: String): TrainRoute? = ref.use { db ->
        val header = db.rawQuery(
            """
            SELECT t.name, t.src_code, t.dst_code, t.total_km, t.total_min, t.run_days, t.return_no,
                   s1.name, s2.name
              FROM trains t
              LEFT JOIN stations s1 ON s1.code = t.src_code
              LEFT JOIN stations s2 ON s2.code = t.dst_code
             WHERE t.train_no = ?
            """.trimIndent(), arrayOf(trainNo)
        ).use { c ->
            if (!c.moveToNext()) return@use null
            RouteHeader(
                name = c.getString(0), src = c.getString(1), dst = c.getString(2),
                totalKm = if (c.isNull(3)) 0 else c.getInt(3),
                totalMin = if (c.isNull(4)) null else c.getInt(4),
                runDays = if (c.isNull(5)) null else c.getInt(5),
                returnNo = if (c.isNull(6)) null else c.getString(6),
                srcName = if (c.isNull(7)) c.getString(1) else c.getString(7),
                dstName = if (c.isNull(8)) c.getString(2) else c.getString(8),
            )
        } ?: return@use null

        val stops = db.rawQuery(
            """
            SELECT p.seq, p.station_code, st.name, st.state, st.lat, st.lon,
                   p.arrival_min, p.departure_min, p.journey_day, p.sched_km, p.is_halt
              FROM stops p LEFT JOIN stations st ON st.code = p.station_code
             WHERE p.train_no = ? ORDER BY p.seq ASC
            """.trimIndent(), arrayOf(trainNo)
        ).use { c -> buildList { while (c.moveToNext()) add(c.mapStop()) } }

        val segments = db.rawQuery(
            "SELECT seq, from_code, to_code, leg_km, leg_min FROM segments " +
                "WHERE train_no = ? ORDER BY seq ASC", arrayOf(trainNo)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    SegmentRow(
                        seq = c.getInt(0), fromCode = c.getString(1), toCode = c.getString(2),
                        legKm = c.getInt(3), legMin = if (c.isNull(4)) null else c.getInt(4),
                    )
                )
            }
        }

        TrainRoute(
            trainNo = trainNo, trainName = header.name, srcCode = header.src, srcName = header.srcName,
            dstCode = header.dst, dstName = header.dstName, totalKm = header.totalKm,
            totalMin = header.totalMin, runDays = header.runDays, returnNo = header.returnNo,
            stops = stops, segments = segments,
        )
    }

    /** The passenger's slice of the route only — used by the station list under a search result. */
    fun journeyStops(trainNo: String, fromSeq: Int, toSeq: Int): List<StopRow> = ref.query(
        """
        SELECT p.seq, p.station_code, st.name, st.state, st.lat, st.lon,
               p.arrival_min, p.departure_min, p.journey_day, p.sched_km, p.is_halt
          FROM stops p LEFT JOIN stations st ON st.code = p.station_code
         WHERE p.train_no = ? AND p.seq BETWEEN ? AND ? ORDER BY p.seq ASC
        """.trimIndent(), arrayOf(trainNo, fromSeq.toString(), toSeq.toString())
    ) { c -> buildList { while (c.moveToNext()) add(c.mapStop()) } }

    /**
     * The status-chip line: how many trains are on this device, and **how old the schedule really is**.
     *
     * This line used to print `built_at` - the moment the ETL repacked the asset - as if it were the
     * timetable's date, so a phone in October showed "timetable 11 Oct 2026, 22:27 IST" over an upstream
     * dataset last revised in June. A traveller who believes the chip is the *date of the data* will read a
     * renumbered train as a wrong train, and will not forgive the app for it; this product's one non-negotiable
     * rule is that nothing may look fresher than it is. So the chip now carries the upstream vintage - the
     * commit date of the source dumps, recorded at build time - and says plainly when that is old.
     */
    fun dataVersion(): String = ref.use { db ->
        val trains = db.rawQuery("SELECT COUNT(*) FROM trains", null).use { c ->
            if (c.moveToNext()) c.getInt(0) else 0
        }
        // Three states, because the middle one is the common one on a phone that has an asset built before
        // this gate existed: verified, contradicted, and never-checked. A dataset whose numbers predate the
        // 2022-23 renumbering is not "113 days old" - that framing borrowed authority from a git commit date
        // and was itself a lie - so when the gate says the data disagrees with reality, the date stops being
        // the headline and the doubt is.
        val vintage = db.metaString("source_vintage")
        when (db.metaString("data_trust")) {
            "verified" -> "${Text.group(trains)} trains · " + vintageLabel(vintage)
            null, "" -> "${Text.group(trains)} trains · " +
                (if (vintage.isNullOrBlank()) "timetable not checked against reality"
                 else "timetable not checked against reality (copy from $vintage)")
            else -> "${Text.group(trains)} trains · timetable UNVERIFIED: numbers/names disagree with the " +
                "live railway data of " + (vintage ?: "an unknown date") + " - verify before travelling"
        }
    }

    /**
     * The chip's second half. [vintage] is an ISO `yyyy-mm-dd` from the upstream clone; a missing or
     * unparseable one is reported as unknown rather than quietly replaced with today, which is the mistake
     * this replaces. Older than a quarter is *said*: Indian Railways revises timetables roughly that often,
     * and a renumbering is exactly what a passenger cannot detect on their own.
     */
    internal fun vintageLabel(vintage: String?): String {
        if (vintage.isNullOrBlank()) return "timetable date unknown - check a live source"
        val days = daysSince(vintage)
        return when {
            days == null -> "timetable data as of $vintage"
            days > 90 -> "timetable data as of $vintage · $days days old, may have changed"
            else -> "timetable data as of $vintage"
        }
    }

    private fun daysSince(iso: String): Int? = runCatching {
        val then = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso.trim())
        val ms = System.currentTimeMillis() - (then?.time ?: return null)
        (ms / 86_400_000L).toInt()
    }.getOrNull()

    // ---------------------------------------------------------------- mapping

    private data class RouteHeader(
        val name: String, val src: String, val dst: String, val totalKm: Int, val totalMin: Int?,
        val runDays: Int?, val returnNo: String?, val srcName: String, val dstName: String,
    )

    private fun Cursor.mapStations(): List<StationHit> =
        buildList { while (moveToNext()) add(mapStation()) }

    private fun Cursor.mapStation(): StationHit = StationHit(
        code = getString(0), name = getString(1),
        state = if (isNull(2)) null else getString(2),
        zone = if (isNull(3)) null else getString(3),
        lat = if (isNull(4)) null else getDouble(4),
        lon = if (isNull(5)) null else getDouble(5),
        rank = getInt(6),
    )

    private fun Cursor.mapStop(): StopRow = StopRow(
        seq = getInt(0), code = getString(1), name = if (isNull(2)) getString(1) else getString(2),
        state = if (isNull(3)) null else getString(3),
        lat = if (isNull(4)) null else getDouble(4), lon = if (isNull(5)) null else getDouble(5),
        arrivalMin = if (isNull(6)) null else getInt(6),
        departureMin = if (isNull(7)) null else getInt(7),
        journeyDay = getInt(8), schedKm = getInt(9), isHalt = getInt(10) != 0,
    )

    companion object {
        /** ≈2.4 km at Indian latitudes: covers "station is 1.5 km from my hotel". */
        private const val DEGREE_BOX = 0.022

        /** ≈17 km, for "which junction am I near" when the box above finds only halts. */
        private const val JUNCTION_BOX = 0.15
    }
}
