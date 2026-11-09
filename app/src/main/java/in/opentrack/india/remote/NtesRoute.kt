package `in`.opentrack.india.remote

import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute

/** Rebuild a conflicting device route ONLY from a dated, run-matched official STNS table.
 * The caller must run matchesRun first; this object does not authenticate an arbitrary JSON array. */
object NtesRoute {
    fun fromStatus(parsed: NtesWire.Parsed, device: TrainRoute): TrainRoute? {
        val rows = parsed.rows
        val codes = rows.mapNotNull { it.code?.takeIf { code -> code.isNotBlank() } }
        if (rows.size < 3 || codes.size != rows.size || codes.distinct().size != codes.size) return null
        val deviceByCode = device.stops.associateBy { it.code.uppercase() }
        var cursor = -1
        fun fold(clock: Int?): Int? {
            if (clock == null) return null
            var minute = clock
            if (clock < 1440) while (minute + 120 < cursor) minute += 1440
            if (cursor >= 0 && minute < cursor - 120) return null
            cursor = maxOf(cursor, minute)
            return minute
        }
        val stops = rows.mapIndexed { i, r ->
            val code = r.code!!
            val arr = fold(r.schedArr)
            val dep = fold(r.schedDep)
            val km = r.km?.takeIf { it >= 0 } ?: deviceByCode[code]?.schedKm ?: -1
            StopRow(
                seq = i + 1, code = code, name = r.name?.takeIf { name -> name.isNotBlank() }
                    ?: deviceByCode[code]?.name ?: code,
                state = deviceByCode[code]?.state, lat = deviceByCode[code]?.lat,
                lon = deviceByCode[code]?.lon, arrivalMin = arr, departureMin = dep,
                journeyDay = ((dep ?: arr ?: cursor).coerceAtLeast(0) / 1440),
                schedKm = km, isHalt = true,
            )
        }
        if (stops.first().departureMin == null ||
            (stops.last().arrivalMin == null && stops.last().departureMin == null) ||
            stops.count { it.arrivalMin != null || it.departureMin != null } < 3) return null
        val segments = stops.zipWithNext { a, b ->
            SegmentRow(a.seq, a.code, b.code,
                if (a.schedKm >= 0 && b.schedKm >= a.schedKm) b.schedKm - a.schedKm else 0,
                a.departureMin?.let { d -> b.arrivalMin?.let { (it - d).coerceAtLeast(0) } },
            )
        }
        return TrainRoute(
            trainNo = device.trainNo,
            trainName = parsed.trainName?.takeIf { name -> name.isNotBlank() }
                ?: device.trainName.takeIf {
                    device.srcCode.equals(stops.first().code, true) &&
                        device.dstCode.equals(stops.last().code, true)
                } ?: "Train ${device.trainNo}",
            srcCode = stops.first().code, srcName = stops.first().name,
            dstCode = stops.last().code, dstName = stops.last().name,
            totalKm = stops.last().schedKm.coerceAtLeast(0),
            totalMin = (stops.last().arrivalMin ?: stops.last().departureMin)?.let {
                it - (stops.first().departureMin ?: 0)
            },
            runDays = device.runDays, returnNo = device.returnNo,
            stops = stops, segments = segments,
        )
    }
}
