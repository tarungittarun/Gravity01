package `in`.opentrack.india.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The calendar maths in [RailTime] is hand-written (no `java.time` on API 24 without desugaring), so
 * it is tested against `java.time` on the JVM over a wide range rather than with a handful of examples.
 * A one-day error here shifts every ±24 h search and every "runs on Tuesday" answer.
 */
class RailTimeTest {

    @Test
    fun epochDayMatchesJavaTimeAcrossCentury() {
        for (offsetDays in -30_000L..30_000L step 7) {
            val date = LocalDate.of(2000, 1, 1).plusDays(offsetDays)
            val epochDay = date.toEpochDay()
            val (y, m, d) = RailTime.civilFromDays(epochDay)
            assertEquals("civilFromDays($epochDay)", date.year, y)
            assertEquals("civilFromDays($epochDay)", date.monthValue, m)
            assertEquals("civilFromDays($epochDay)", date.dayOfMonth, d)
            assertEquals(epochDay, RailTime.daysFromCivil(y, m, d))
        }
    }

    @Test
    fun istEpochDayRollsAtHalfPastMidnightUtcThePreviousDay() {
        // IST is UTC+05:30, so the Indian calendar day begins at 18:30 UTC the day before. That
        // half-hour is where a `floorDiv` on the wrong constant silently shifts every "does this train
        // run today" answer by a day.
        val boundary = java.time.Instant.parse("2023-12-31T18:30:00Z").toEpochMilli()
        val before = LocalDate.of(2023, 12, 31).toEpochDay()
        val after = LocalDate.of(2024, 1, 1).toEpochDay()
        assertEquals(after, RailTime.istEpochDay(boundary))
        assertEquals(before, RailTime.istEpochDay(boundary - 1L))
        // The same clock time one day later is one day later in the Indian calendar too — asserted as
        // after + 1 because `boundary` is already 00:00 IST of `after`.
        assertEquals(after + 1, RailTime.istEpochDay(boundary + 86_400_000L))
    }

    @Test
    fun istMinuteOfDayUsesThirtyMinuteOffset() {
        // 18:30 UTC == 00:00 IST: the minute counter must roll at the *half* hour, not the hour.
        val midnightIst = java.time.Instant.parse("2023-12-31T18:30:00Z").toEpochMilli()
        assertEquals(0, RailTime.istMinuteOfDay(midnightIst))
        assertEquals(1, RailTime.istMinuteOfDay(midnightIst + 60_000L))
        assertEquals(1439, RailTime.istMinuteOfDay(midnightIst - 60_000L))
        assertEquals(390, RailTime.istMinuteOfDay(midnightIst + 390 * 60_000L))
    }

    @Test
    fun weekdayBitmapMatchesJavaTime() {
        // run_days is a weekday bitmap with bit 0 = Sunday (the shape the upstream dump uses).
        for (offset in 0L..36L) {
            val date = LocalDate.of(2024, 3, 1).plusDays(offset)
            val epochDay = date.toEpochDay()
            val mondayZero = RailTime.istWeekdayOfEpochDay(epochDay)
            assertEquals("weekday of $date", date.dayOfWeek.value - 1, mondayZero)
            val sundayBit = ((mondayZero + 1) % 7)
            val bitmap = 1 shl sundayBit
            assertTrue("$date should run on $bitmap", RailTime.runsOn(bitmap, mondayZero))
            val complement = 127.inv() or bitmap   // every day except this one
            assertTrue("$date still runs on $complement", RailTime.runsOn(complement, mondayZero))
            val without = bitmap.inv() and 127
            assertFalse("$date must not run on $without", RailTime.runsOn(without, mondayZero))
        }
    }

    @Test
    fun nullRunDaysMeansAssumeRunning() {
        assertTrue(RailTime.runsOn(null, 3))
        // An empty bitmap is not "unknown", it is "no day" — usually a cancelled train. Hiding it is the
        // honest answer; the ±24 h window already surfaced it under "does not run on this date".
        assertFalse(RailTime.runsOn(0, 3))
    }

    @Test
    fun railMinuteFormatsWithDayMarkers() {
        assertEquals("00:00", RailTime.formatRailMinute(0))
        assertEquals("23:59", RailTime.formatRailMinute(1439))
        // A run that arrives at 04:00 on day 2 must not print as "04:00" next to a 23:50 departure —
        // the marker is the only thing that keeps the row honest, so it is unconditional.
        assertEquals("00:00 (+1d)", RailTime.formatRailMinute(1440))
        assertEquals("06:30 (+1d)", RailTime.formatRailMinute(1830))
        assertEquals("08:00 (+1d)", RailTime.formatRailMinute(1920))
        assertEquals("23:59 (-1d)", RailTime.formatRailMinute(-1))
        assertEquals("08:00", RailTime.formatRailMinuteBare(1920))
    }

    @Test
    fun dayOffsetFloorsForNegatives() {
        assertEquals(0, RailTime.dayOffsetOf(0))
        assertEquals(1, RailTime.dayOffsetOf(1440))
        assertEquals(-1, RailTime.dayOffsetOf(-1))
        assertEquals(-1, RailTime.dayOffsetOf(-1440))
        assertEquals(-2, RailTime.dayOffsetOf(-1441))
    }

    @Test
    fun parseHmAcceptsTheShapesTheFeedUses() {
        assertEquals(0, RailTime.parseHm("00:00"))
        assertEquals(1439, RailTime.parseHm("23:59:00"))
        assertEquals(390, RailTime.parseHm(" 6:30 "))
        assertNull(RailTime.parseHm(null))
        assertNull(RailTime.parseHm(""))
        assertEquals(0, RailTime.parseHm("24:00"))
        assertEquals(5, RailTime.parseHm("24:05"))
        assertNull(RailTime.parseHm("12:75"))
        assertNull(RailTime.parseHm("None"))
        assertNull(RailTime.parseHm("--:--"))
    }

    @Test
    fun durationsAndDelays() {
        assertEquals("0m", RailTime.formatDuration(0))
        assertEquals("1h 30m", RailTime.formatDuration(90))
        assertEquals("2h 5m", RailTime.formatDuration(125))
        assertEquals("1d 1h", RailTime.formatDuration(1500))
        assertEquals("on time", RailTime.formatDelay(0))
        assertTrue(RailTime.formatDelay(14).contains("14"))
        assertTrue(RailTime.formatDelay(-6).contains("6"))
    }

    @Test
    fun weekdayOfKnownDates() {
        // 2024-01-01 was a Monday; 2024-06-15 was a Saturday.
        assertEquals(0, RailTime.istWeekdayOfEpochDay(LocalDate.of(2024, 1, 1).toEpochDay()))
        assertEquals(5, RailTime.istWeekdayOfEpochDay(LocalDate.of(2024, 6, 15).toEpochDay()))
        // 1969-12-31 was a Wednesday, and it is a *negative* epoch day — the modulo must not go
        // negative, or every pre-1970 date reports the wrong running day.
        assertEquals(2, RailTime.istWeekdayOfEpochDay(-1L))
    }

    @Test
    fun isoDateRoundTrip() {
        for (offset in 0L..400L step 13) {
            val date = LocalDate.of(2024, 1, 1).plusDays(offset)
            val iso = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
            assertEquals(iso, RailTime.formatIsoDate(date.toEpochDay()))
            assertEquals(date.toEpochDay(), RailTime.epochDayOf(iso))
        }
        assertEquals("20240101", RailTime.formatCompactDate(LocalDate.of(2024, 1, 1).toEpochDay()))
        assertNull(RailTime.epochDayOf("2024/01/01"))
        assertNull(RailTime.epochDayOf("junk"))
    }

    @Test
    fun clockAndDateLabelsAreHumanReadable() {
        // formatDate spells the month: the label sits next to a train number where "14-08-24" invites a
        // dd/mm vs mm/dd misreading, and the whole app is for readers on both conventions.
        assertEquals("14 Aug 2024", RailTime.formatDate(LocalDate.of(2024, 8, 14).toEpochDay()))
        assertEquals("1 Jan 1970", RailTime.formatDate(0L))
        // 2024-01-01T18:30Z == 2024-01-02 00:00 IST. Built from the instant, not from an offset
        // arithmetic that is easy to get half an hour wrong (and would then test nothing).
        val midnightIst = java.time.Instant.parse("2024-01-01T18:30:00Z").toEpochMilli()
        assertEquals("00:00", RailTime.formatClock(midnightIst))
        assertEquals("00:00:00", RailTime.formatClockSeconds(midnightIst))
        assertEquals("09:05:07", RailTime.formatClockSeconds(midnightIst +
            (9 * 60 + 5) * 60_000L + 7_000L))
        assertEquals("09:05", RailTime.formatClock(midnightIst + (9 * 60 + 5) * 60_000L))
        assertEquals("23:59", RailTime.formatClock(midnightIst + 86_400_000L - 60_000L))
    }

    @Test
    fun railMinuteToEpochMillisIsConsistent() {
        val origin = LocalDate.of(2024, 6, 15).toEpochDay()
        // 06:30 IST on the origin day
        // 06:30 IST on 2024-06-15 is 01:00 UTC the same day.
        val expected = java.time.Instant.parse("2024-06-15T01:00:00Z").toEpochMilli()
        assertEquals(expected, RailTime.railMinuteToEpochMillis(origin, 390))
        // and one day later
        assertEquals(expected + 86_400_000L, RailTime.railMinuteToEpochMillis(origin, 390 + 1440))
    }
    @Test
    fun clockTimesAcrossMidnightAreADelayNotADayOfAdvance() {
        // The pair a live feed actually sends: a clock for what happened, and a schedule counted from the
        // run's first halt. Subtracting one from the other across midnight is the single most confident way
        // this app could have been wrong — it calls a 30-minute slip "23 h 30 m early".
        assertEquals(30, RailTime.clockDelta(RailTime.parseHm("00:20"), RailTime.parseHm("23:50")))
        assertEquals(-25, RailTime.clockDelta(RailTime.parseHm("23:35"), RailTime.parseHm("00:00")))
        assertEquals(10, RailTime.clockDelta(610, 600))
        assertEquals("half a day of drift is read as late, not as a day early", 720,
            RailTime.clockDelta(720, 0))
        assertEquals(0, RailTime.clockDelta(600, 600))
        assertNull("no clock, no claim", RailTime.clockDelta(null, 600))
        assertNull("no schedule to differ from", RailTime.clockDelta(600, null))

        // And a clock is placed on the run's own day scale, so a day-2 halt reported as "00:20" is printed
        // as 00:20 *on day 2* rather than 22 hours before the schedule cell beside it.
        assertEquals(1460, RailTime.alignClock(RailTime.parseHm("00:20"), 1470))
        assertEquals(20, RailTime.alignClock(RailTime.parseHm("00:20"), 30))
        assertEquals(1442, RailTime.alignClock(2, 1440))
        assertEquals("nothing to anchor to leaves the clock alone", 20, RailTime.alignClock(20, null))
        assertNull(RailTime.alignClock(null, 1470))
        // A value already on the run's scale stays put: this is a day-1 halt, not a wrapped one.
        assertEquals(605, RailTime.alignClock(605, 600))
    }

    @Test
    fun buildStampsAreShownInTheReadersCalendarNotAsAnIsoBlob() {
        // The status chip answers "how old is my timetable", which a UTC ISO stamp does not: it makes
        // the reader add five and a half hours in their head. Half an hour, at 2 a.m., on a train.
        assertEquals("11 Oct 2026, 01:57 IST", RailTime.formatStamp("2026-10-10T20:27:26Z"))
        assertEquals(
            RailTime.istEpochDay(RailTime.utcStampToMillis("2026-10-10T20:27:26Z")!!),
            RailTime.epochDayOf("2026-10-11"),
        )
        // Anything we do not recognise stays null, so the caller can print the raw value rather than a
        // confident wrong date.
        assertNull(RailTime.formatStamp("unknown"))
        assertNull(RailTime.formatStamp("2026-10-10"))
        assertNull(RailTime.formatStamp(null))
        assertNull(RailTime.utcStampToMillis("2026/10/10 20:27"))
    }

}
