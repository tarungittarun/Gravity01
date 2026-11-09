package `in`.opentrack.india.remote

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot codec is used in three places at once — the on-disk live cache, the user-configured
 * endpoint's expected wire shape, and the offline "what I last saw" record — so a mismatch between
 * `encode` and `decode` is invisible in a single-directional test. Round-tripping is the point.
 */
class SnapshotCodecTest {

    private val sample = LiveSnapshot(
        trainNo = "12951",
        journeyEpochDay = RailTime.epochDayOf("2024-08-14"),
        atCode = "BRC",
        atSeq = 12,
        atName = "Vadodara Jn",
        event = StopStatus.DEPARTED,
        eventMinute = 742,
        scheduledMinuteAtEvent = 728,
        nextCode = "RTM",
        expectedNextMinute = 900,
        delayMinutes = 14,
        platform = "3",
        perStop = linkedMapOf(
            "BRC" to LiveStopFields(
                actualArrivalMin = 735, actualDepartureMin = 742,
                delayArrivalMin = 12, delayDepartureMin = 14, platform = "2",
            ),
            "ST" to LiveStopFields(cancelled = true),
        ),
        fetchedAtMillis = 1_723_612_800_000L,
        sourceKind = SourceKind.PROVIDER,
        rawNote = "ok",
    )

    @Test
    fun encodeDecodeRoundTripKeepsEveryField() {
        val back = SnapshotCodec.decode(SnapshotCodec.encode(sample))
        assertEquals(sample.trainNo, back!!.trainNo)
        assertEquals(sample.journeyEpochDay, back.journeyEpochDay)
        assertEquals("BRC", back.atCode)
        assertEquals(12, back.atSeq)
        assertEquals("Vadodara Jn", back.atName)
        assertEquals(StopStatus.DEPARTED, back.event)
        assertEquals(742, back.eventMinute)
        assertEquals(728, back.scheduledMinuteAtEvent)
        assertEquals("RTM", back.nextCode)
        assertEquals(900, back.expectedNextMinute)
        assertEquals(14, back.delayMinutes)
        assertEquals("3", back.platform)
        assertEquals(1_723_612_800_000L, back.fetchedAtMillis)
        assertEquals(SourceKind.PROVIDER, back.sourceKind)
        assertEquals("ok", back.rawNote)
        assertEquals(2, back.perStop.size)
        assertEquals(12, back.perStop["BRC"]!!.delayArrivalMin)
        assertEquals(742, back.perStop["BRC"]!!.actualDepartureMin)
        assertTrue(back.perStop["ST"]!!.cancelled)
    }

    @Test
    fun decodeAcceptsTheAggregatorShapeDirectly() {
        // A different provider, same facts: nested `currentLocation`, HH:MM strings, no `data` wrapper.
        val json = """
            {"data":{"trainNumber":"12002","journeyDate":"2024-08-14",
             "currentLocation":{"stationCode":"CSMT","sequence":1,"stationName":"Mumbai Csmt",
               "status":"Train has arrived","actualArrival":"20:05","scheduledDeparture":"20:00"},
             "nextStationCode":"KYN","expectedArrival":"21:02","delayMinutes":5,
             "route":[{"stationCode":"CSMT","delayArrival":"5","platform":"1"},
                      {"stationCode":"KYN","cancelled":true}]}}
        """.trimIndent()
        val s = SnapshotCodec.decode(json)!!
        assertEquals("12002", s.trainNo)
        assertEquals("CSMT", s.atCode)
        assertEquals(1, s.atSeq)
        assertEquals(1205, s.eventMinute)
        assertEquals(1200, s.scheduledMinuteAtEvent)
        assertEquals(StopStatus.ARRIVED, s.event)
        assertEquals(RailTime.epochDayOf("2024-08-14"), s.journeyEpochDay)
        assertEquals(5, s.delayMinutes)
        assertEquals(1262, s.expectedNextMinute)   // 21:02
        assertEquals(2, s.perStop.size)
        assertEquals(5, s.perStop["CSMT"]!!.delayArrivalMin)
        assertEquals("1", s.perStop["CSMT"]!!.platform)
        assertTrue(s.perStop["KYN"]!!.cancelled)
    }

    @Test
    fun dayCrossingMinutesAreWrittenBareSoTheRoundTripIsLossless() {
        // A two-day run (1500 = 01:00 on day 2) must not come back as 60. `formatRailMinute` appends
        // "(+1d)", which the loose time parser would drop, so the encoder is required to write the bare
        // clock value plus nothing — the day is carried by journeyEpochDay/sequence, not the string.
        val late = sample.copy(eventMinute = 1500, expectedNextMinute = 1600, perStop = emptyMap())
        val back = SnapshotCodec.decode(SnapshotCodec.encode(late))!!
        assertEquals(1500, back.eventMinute)
        assertEquals(1600, back.expectedNextMinute)
    }

    @Test
    fun garbageAndEmptyInputYieldNullNotAnException() {
        assertNull(SnapshotCodec.decode("not json at all"))
        assertNull(SnapshotCodec.decode(""))
        assertNull(SnapshotCodec.decode("<html>temporarily unavailable</html>"))
        // Valid JSON but no train identity: better null than a snapshot about train "null".
        assertNull(SnapshotCodec.decode("""{"foo":"bar"}"""))
        // No perStop / no current location is still a valid "we know nothing yet" record.
        val s = SnapshotCodec.decode("""{"trainNumber":"12951"}""")!!
        assertEquals(0, s.atSeq)
        assertEquals(StopStatus.UNKNOWN, s.event)
        assertTrue(s.perStop.isEmpty())
    }

    @Test
    fun aCustomEndpointThatOmitsTheTrainNumberStillWorks() {
        // Settings' escape hatch must not demand a field the caller already knows. Without the fallback,
        // a minimal self-hosted endpoint would decode to null and the screen would look broken.
        val json = """{"currentLocation":{"stationCode":"BRC","sequence":12,"actualDeparture":742}}"""
        assertNull(SnapshotCodec.decode(json))
        val withFallback = SnapshotCodec.decode(json, fallbackTrainNo = "12951")!!
        assertEquals("12951", withFallback.trainNo)
        assertEquals("BRC", withFallback.atCode)
        assertEquals(742, withFallback.eventMinute)
    }

    @Test
    fun stringsAreEscapedSoANoteCannotBreakTheRecord() {
        val nasty = sample.copy(rawNote = "he said \"hi\"\nthen left", atName = "a\\b", perStop = emptyMap())
        val text = SnapshotCodec.encode(nasty)
        val back = SnapshotCodec.decode(text)!!
        assertEquals("he said \"hi\"\nthen left", back.rawNote)
        assertEquals("a\\b", back.atName)
    }
}
