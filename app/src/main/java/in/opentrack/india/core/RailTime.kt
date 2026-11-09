package `in`.opentrack.india.core

/**
 * Time handling for the whole app.
 *
 * Why a custom kit instead of `java.time`: Indian Railways data is *minute-granular* and *day-offset
 * relative*, not timestamp-based. A stop is "01:40 on day 2 of the run from New Delhi", which
 * java.time models awkwardly (and `java.time` would additionally oblige us to enable core-library
 * desugaring, ~+1 MB in a budget where every 100 kB was argued over). So the app works in two
 * numeric types and keeps formatting here:
 *
 *  - `railMinute`  — minutes since *midnight of the train's origin date*. `journeyDay` is already
 *                    folded in, so a run that crosses midnight three times stays monotonic and
 *                    comparable with `<`. This is exactly what `stops.arrival_min` stores.
 *  - epoch millis  — wall clock, only used at the edges (reading "now", formatting a day header).
 *
 * All Indian Railways times are IST regardless of the device timezone; a traveller in Dubai must
 * still see "23:55 IST". So the device offset is applied only when converting *to/from* epoch.
 */
object RailTime {

    const val MINUTES_PER_DAY = 1440
    const val IST_OFFSET_SECONDS = 19800L          // UTC+05:30, no DST in India
    const val IST_OFFSET_MILLIS = IST_OFFSET_SECONDS * 1000

    fun parseHm(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val m = HM_RE.find(text.trim()) ?: return null
        val hh = m.groupValues[1].toIntOrNull() ?: return null
        val mm = m.groupValues[2].toIntOrNull() ?: return null
        if (mm !in 0..59) return null
        // "24:05" appears in a few feeds for next-day arrivals; normalise, don't reject.
        return if (hh > 23) (hh - 24) * 60 + mm else hh * 60 + mm
    }

    /**
     * "14:30" for 870; "04:00 (+1d)" for 1920.
     *
     * The day marker is *not* optional: a great many long-distance Indian trains board in the evening
     * and arrive after midnight, and a row that reads "23:50 → 04:00" looks like a 4-hour journey when
     * it is 5 hours. The marker is therefore printed whenever the value leaves day 1 of the run, and
     * callers must not suppress it. [formatRailMinuteBare] exists for axis ticks, where the marker is
     * noise.
     */
    fun formatRailMinute(railMinute: Int): String {
        val minute = ((railMinute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val dayOffset = dayOffsetOf(railMinute)
        val sb = StringBuilder(14)
        sb.append(pad2(minute / 60)).append(':').append(pad2(minute % 60))
        if (dayOffset > 0) sb.append(" (+").append(dayOffset).append("d)")
        else if (dayOffset < 0) sb.append(" (").append(dayOffset).append("d)")
        return sb.toString()
    }

    /**
     * The signed gap between two minute-of-day clocks, taking the day that makes the movement smaller.
     *
     * A live feed reports "00:20" and the reference table reports "23:50", and neither says which day of the
     * run it means. Plain subtraction answers "-1410 minutes", i.e. a train that arrived half an hour late is
     * declared 23 h 30 m *early* — and that number is not cosmetic: it is what the delay chip prints, what
     * `PositionEngine.delayProfile` carries forward to every stop still ahead, and what bends the reported
     * curve on the chart. Wrapping into ±12 h turns it into +30.
     *
     * A gap of exactly 12 h or more cannot be resolved from clocks alone — a day of drift either way would
     * fit — so it is reported as late rather than early (the common direction) and is visibly odd enough to
     * be questioned. `null` when either side is missing: no invented difference.
     */
    fun clockDelta(actual: Int?, scheduled: Int?): Int? {
        if (actual == null || scheduled == null) return null
        val d = actual - scheduled
        return when {
            d < -MINUTES_PER_DAY / 2 -> d + MINUTES_PER_DAY
            d > MINUTES_PER_DAY / 2 -> d - MINUTES_PER_DAY
            else -> d
        }
    }

    /**
     * Put a day-blind clock time on the run's cumulative minute scale, choosing the nearest day to
     * [scheduled]. The table counts minutes from the run's first halt, so a halt on day 2 is 1470 there and
     * "00:30" in a feed; printed side by side, those two are a 24-hour apart pair of cells in the same row.
     */
    fun alignClock(clock: Int?, scheduled: Int?): Int? {
        if (clock == null) return null
        if (scheduled == null) return clock      // nothing to anchor to, so show what the feed said
        var v = Math.floorMod(clock, MINUTES_PER_DAY)
        while (v - scheduled < -MINUTES_PER_DAY / 2) v += MINUTES_PER_DAY
        while (v - scheduled > MINUTES_PER_DAY / 2) v -= MINUTES_PER_DAY
        return v
    }

    /** "14:30" regardless of which day of the run it falls on. */
    fun formatRailMinuteBare(railMinute: Int): String {
        val minute = ((railMinute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return pad2(minute / 60) + ":" + pad2(minute % 60)
    }

    /** 0 for day 1 of the run, 1 for day 2 … */
    fun dayOffsetOf(railMinute: Int): Int = Math.floorDiv(railMinute, MINUTES_PER_DAY)

    fun dayOf(railMinute: Int): Int = dayOffsetOf(railMinute) + 1

    fun minutesBetween(fromRailMinute: Int, toRailMinute: Int): Int = toRailMinute - fromRailMinute

    fun formatDuration(totalMinutes: Int): String {
        val m = if (totalMinutes < 0) 0 else totalMinutes
        val h = m / 60
        return if (h == 0) "${m}m" else if (h < 24) "${h}h ${m % 60}m" else "${h / 24}d ${h % 24}h"
    }

    fun formatDelay(delayMinutes: Int): String = when {
        delayMinutes > 0 -> "+${delayMinutes}m late"
        delayMinutes < 0 -> "${-delayMinutes}m early"
        else -> "on time"
    }

    /**
     * Convert a rail minute into epoch millis for a given origin date.
     * @param originDayEpochDay epoch-day of the run's origin date (1970-00-01 based)
     */
    fun railMinuteToEpochMillis(originEpochDay: Long, railMinute: Int): Long =
        (originEpochDay * MINUTES_PER_DAY + railMinute) * 60_000L - IST_OFFSET_MILLIS

    /** Epoch millis -> epoch-day in IST. */
    fun istEpochDay(millis: Long): Long = Math.floorDiv(millis + IST_OFFSET_MILLIS, 86_400_000L)

    fun istHour(millis: Long): Int = (((millis + IST_OFFSET_MILLIS) / 3_600_000L) % 24L).toInt()

    /** Minutes-since-midnight-IST-of-today, on the same axis a rail minute uses. */
    fun istMinuteOfDay(millis: Long): Int {
        val abs = Math.floorDiv(millis + IST_OFFSET_MILLIS, 60_000L)
        return (((abs % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY).toInt()
    }

    /** 0=Monday .. 6=Sunday, in IST. */
    fun istWeekday(millis: Long): Int = istWeekdayOfEpochDay(istEpochDay(millis))

    /** 0=Monday .. 6=Sunday for an epoch-day. 1970-01-01 was a Thursday, so +3 shifts Monday to 0. */
    fun istWeekdayOfEpochDay(epochDay: Long): Int = ((((epochDay + 3) % 7).toInt() + 7) % 7)

    /**
     * Weekday bit for a run-day bitmap where bit 0 = Sunday (the layout produced by the ETL).
     * `runDays` may be null when the source dump had no schedule calendar; callers must then treat
     * every day as "probably runs" rather than hiding the train.
     */
    fun runsOn(runDays: Int?, weekdayMondayZero: Int): Boolean {
        if (runDays == null) return true
        val sundayZero = (weekdayMondayZero + 1) % 7
        return (runDays shr sundayZero) and 1 == 1
    }

    fun formatClock(millis: Long): String {
        val minuteOfDay = istMinuteOfDay(millis)
        return pad2((minuteOfDay / 60) % 24) + ":" + pad2(minuteOfDay % 60)
    }

    /** Local IST time at which the selected data was painted, not a source event clock. */
    fun formatClockSeconds(millis: Long): String =
        formatClock(millis) + ":" + pad2(Math.floorMod(millis / 1_000L, 60L).toInt())

    /**
     * "14 Aug 2024". Spelled month because the app shows this next to a train number, where a numeric
     * "14-08-24" invites the dd/mm vs mm/dd reading that Indian and American readers disagree about.
     */
    fun formatDate(epochDay: Long): String {
        val (y, m, d) = civilFromDays(epochDay)
        val month = MONTHS.getOrNull(m - 1) ?: m.toString()
        return "$d $month $y"
    }

    /** yyyy-MM-dd for an epoch-day (ISO, used by every outbound request). */
    fun formatIsoDate(epochDay: Long): String {
        val (y, m, d) = civilFromDays(epochDay)
        return "%04d-%02d-%02d".format(y, m, d)
    }

    /** yyyyMMdd, the compact variant some endpoints expect. */
    fun formatCompactDate(epochDay: Long): String = formatIsoDate(epochDay).replace("-", "")

    fun epochDayOf(isoDate: String): Long? {
        val m = ISO_DATE_RE.matchEntire(isoDate.trim()) ?: return null
        val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        return daysFromCivil(y, mo, d)
    }

    fun todayEpochDay(): Long = istEpochDay(System.currentTimeMillis())

    /**
     * Seconds since the epoch for an UTC stamp like `2026-10-10T20:27:26Z`, or null if it is not in
     * that shape. Parsed by hand because `java.time` would mean enabling core-library desugaring for
     * one string (see the header of this file).
     */
    fun utcStampToMillis(iso: String?): Long? {
        if (iso == null || iso.length < 16 || iso[10] != 'T') return null
        val day = epochDayOf(iso.substring(0, 10)) ?: return null
        val minute = parseHm(iso.substring(11, 16)) ?: return null
        return (day * MINUTES_PER_DAY + minute) * 60_000L
    }

    /**
     * The same stamp as `11 Oct 2026, 01:57 IST`. A machine-readable build date in a status chip reads
     * as a bug even when it is exact, and the question the chip answers is "how stale is my timetable",
     * which is only meaningful in the reader's own calendar. Null on a shape we do not recognise, so
     * the caller can show the raw value rather than invent a date.
     */
    fun formatStamp(iso: String?): String? {
        val millis = utcStampToMillis(iso) ?: return null
        return "${formatDate(istEpochDay(millis))}, ${formatClock(millis)} IST"
    }

    // --- Howard Hinnant's civil-from-days / days-from-civil (proleptic Gregorian).
    // Inlined rather than using java.time.LocalDate.toEpochDay for the same desugaring reason as above.
    fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
        var z = z0 + 719_468
        val era = Math.floorDiv(z, 146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
    }

    fun daysFromCivil(y0: Int, m0: Int, d0: Int): Long {
        val y = (if (m0 <= 2) y0 - 1 else y0).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400                                  // [0, 399]
        val mp = if (m0 > 2) m0 - 3 else m0 + 9                   // March-based month
        val doy = (153 * mp + 2) / 5 + d0 - 1                     // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy           // [0, 146096]
        return era * 146_097 + doe - 719_468
    }

    fun addDays(epochDay: Long, days: Int): Long = epochDay + days

    /**
     * Two-digit minutes/hours. A function, not an `Int` extension: the call sites append into a
     * `StringBuilder`, and `StringBuilder.append` returns the builder — an extension on the *value*
     * cannot be chained after it, which is exactly the mistake this helper replaced.
     */
    private fun pad2(v: Int): String = if (v in 0..9) "0$v" else v.toString()

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    private val HM_RE = Regex("""(\d{1,2}):(\d{2})""")
    private val ISO_DATE_RE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
}
