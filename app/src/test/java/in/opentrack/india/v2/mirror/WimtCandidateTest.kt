package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.RailTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WimtCandidateTest {
    private val day = RailTime.istEpochDay(1791949713_000L)
    private val now = 1791961500_000L
    // Minimal dated capture from the 20689 trial. Some actual_* values are ETA or were
    // generated before the claimed departure: a key name is NOT evidence of an event.
    private val capture = """{
      "start_date":"14-10-2026","source_station":"YPR","destination_station":"SMET",
      "days_schedule":[
        {"station_code":"YPR","departed":true,"actual_arrival_tm":1791949713,
         "actual_departure_tm":1791950020,"updated_at":"2026-10-14T09:25:49.710676+05:30"},
        {"station_code":"BAW","departed":true,"actual_arrival_tm":1791950499,
         "actual_departure_tm":1791950633,"updated_at":"2026-10-14T09:32:24.542168+05:30"},
        {"station_code":"VNR","actual_arrival_tm":1791960456},
        {"station_code":"RRB","source":"eta","departed":true,"actual_arrival_tm":1791959520,
         "updated_at":"2026-10-14T12:30:00.000000+05:30"},
        {"station_code":"SMET","departed":true,"actual_arrival_tm":1791966900,
         "updated_at":"2026-10-14T12:30:00.000000+05:30"}
      ]} """

    @Test fun `accepts only event times preceded by a separate later update`() {
        val r = WimtCandidate.parse(capture, day, "YPR", "SMET", now,
            setOf("YPR", "BAW", "VNR", "RRB", "SMET"))
        assertEquals(2, r.lines.size)
        assertTrue(r.lines["YPR"]!!.contains("arrival 09:18"))
        assertTrue(r.lines["YPR"]!!.contains("departure 09:23"))
        assertTrue(r.lines["BAW"]!!.contains("arrival 09:31"))
        assertFalse(r.lines["BAW"]!!.contains("departure"))
        assertFalse("a future ETA is not an arrival", r.lines.containsKey("RRB"))
        assertFalse("current station without departed flag is not an arrival", r.lines.containsKey("VNR"))
        assertFalse(r.lines.containsKey("SMET"))
        assertTrue(r.detail.contains("1 ETA rows"))
    }

    @Test fun `wrong run route and unknown codes never yield candidate minutes`() {
        assertTrue(WimtCandidate.parse(capture, day - 1, "YPR", "SMET", now).lines.isEmpty())
        assertTrue(WimtCandidate.parse(capture, day, "TLGP", "SBC", now).lines.isEmpty())
        assertTrue(WimtCandidate.parse(capture, day, "YPR", "SMET", now, setOf("ASK"))
            .lines.isEmpty())
    }

    @Test fun `web point requires its own recent event and independent route brackets`() {
        val event = 1792014679L // 15 Oct, 03:21 IST: BLKR's own arrival field
        val at = event * 1000 + 120_000L
        val sample = """{
          "start_date":"14-10-2026","source_station":"MYS","destination_station":"TLGP",
          "lastUpdateIsoDate":"2026-10-15T03:22:00.000000+05:30",
          "curStn":"BLKR","distance":338.285,
          "days_schedule":[
            {"station_code":"ASK","distance":302.9,"departed":true},
            {"station_code":"BLKR","curStn":true,"stops":false,
             "actual_arrival_tm":1792014679,"actual_departure_tm":1792014709},
            {"station_code":"RRB","source":"eta","distance":348.8}
          ]
        }""".trimIndent()
        val kms = linkedMapOf("ASK" to 304, "RRB" to 349)
        val point = WimtCandidate.parse(sample, day, "MYS", "TLGP", at,
            knownKms = kms).webPoint!!
        assertEquals("BLKR", point.code)
        assertEquals("ASK", point.precedingHalt)
        assertEquals("RRB", point.followingHalt)
        assertEquals(event * 1000, point.eventAtMillis)
        assertTrue("contradictory departure-before-arrival cannot locate a train",
            WimtCandidate.parse(sample.replace("1792014709", "1792011780"),
                day, "MYS", "TLGP", at, knownKms = kms).webPoint == null)
        assertTrue("a cache timestamp cannot revive an old event",
            WimtCandidate.parse(sample.replace("03:22:00", "03:33:00"),
                day, "MYS", "TLGP", at + 10 * 60_000, knownKms = kms).webPoint == null)
        assertTrue(WimtCandidate.parse(sample.replace("\"distance\":338.285", "\"distance\":400.0"),
            day, "MYS", "TLGP", at, knownKms = kms).webPoint == null)
        assertTrue(WimtCandidate.parse(sample.replace("\"curStn\":true", "\"curStn\":false"),
            day, "MYS", "TLGP", at, knownKms = kms).webPoint == null)
        assertTrue(WimtCandidate.parse(sample.replace("\"stops\":false", "\"source\":\"eta\""),
            day, "MYS", "TLGP", at, knownKms = kms).webPoint == null)
        assertTrue(WimtCandidate.parse(sample, day, "MYS", "TLGP", at).webPoint == null)
        assertTrue(WimtCandidate.parse(sample, day - 1, "MYS", "TLGP", at,
            knownKms = kms).webPoint == null)
    }

    @Test fun `fresh cache clock cannot revive old contradictory 20689 station`() {
        val at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.ROOT)
            .parse("2026-11-02 15:13:05 +0530")!!.time
        val run = RailTime.istEpochDay(at)
        // Public web-cache shape observed on 2 Nov, reduced to fields needed for this gate.
        // BDVT arrival was 13:35 while its 'actual departure' was schedule 12:52;
        // a fresh root timestamp is not proof the train is still there at 15:13.
        val sample = """{
          "start_date":"02-11-2026","source_station":"YPR","destination_station":"SMET",
          "lastUpdateIsoDate":"2026-11-02T15:13:02.000000+05:30",
          "curStn":"BDVT","distance":258.5,
          "days_schedule":[
            {"station_code":"BDVT","curStn":true,"stops":true,
             "actual_arrival_tm":1793606741,"actual_departure_tm":1793604120},
            {"station_code":"SMET","source":"eta","actual_arrival_tm":1793608500}
          ]
        }""".trimIndent()
        val kms = linkedMapOf("BDVT" to 246, "SMET" to 269)
        val result = WimtCandidate.parse(sample, run, "YPR", "SMET", at,
            setOf("BDVT", "SMET"), kms)
        assertTrue(result.lines.isEmpty())
        assertTrue(result.webPoint == null)
        assertTrue(result.detail.contains("no fresh, route-bracketed web position event"))
        val claimedDeparted = sample.replace("\"curStn\":true,\"stops\":true,",
            "\"curStn\":true,\"stops\":true,\"departed\":true," )
            .replace("\"actual_arrival_tm\":1793606741,",
                "\"updated_at\":\"2026-11-02T13:40:00.000000+05:30\"," +
                "\"actual_arrival_tm\":1793606741,")
        val past = WimtCandidate.parse(claimedDeparted, run, "YPR", "SMET", at,
            setOf("BDVT", "SMET"), kms)
        assertTrue(past.lines["BDVT"]?.contains("arrival 13:35") == true)
        assertFalse(past.lines["BDVT"]!!.contains("departure"))
        assertTrue(past.webPoint == null)
    }

    @Test fun `past next day station belongs to origin run not another day`() {
        val event = 1792014679L // 15 Oct 03:21 IST; origin run 14 Oct
        val sample = """{"start_date":"14-10-2026","source_station":"MYS",
          "destination_station":"TLGP","days_schedule":[
          {"station_code":"BLKR","departed":true,
           "actual_arrival_tm":1792014679,"actual_departure_tm":1792014739,
           "updated_at":"2026-10-15T03:23:30.000000+05:30"}]}"""
        val r = WimtCandidate.parse(sample, day, "MYS", "TLGP", event * 1000 + 180_000,
            setOf("BLKR"))
        assertTrue(r.lines["BLKR"]?.contains("arrival") == true)
        assertTrue(r.lines["BLKR"]?.contains("departure") == true)
        assertTrue(WimtCandidate.parse(sample, day - 1, "MYS", "TLGP", event * 1000 + 180_000)
            .lines.isEmpty())
    }

    @Test fun `manual fetch is exactly one GET and transports its run date`() {
        var calls = 0
        val r = WimtCandidate.fetch("20689", day, "YPR", "SMET", setOf("YPR"), now) { url ->
            calls++
            assertEquals("https://whereismytrain.in/cache/live_status?" +
                "date=14-10-2026&train_no=20689&lang=en", url)
            assertFalse("no copied phone-app version", url.contains("appVersion"))
            assertFalse("no assumed first-day key", url.contains("from_day"))
            capture to "ok"
        }
        assertEquals(1, calls)
        assertEquals(setOf("YPR"), r.lines.keys)
        val invalid = WimtCandidate.fetch("2068x", day, "YPR", "SMET",
            setOf("YPR"), now) { error("invalid train must not request the web cache") }
        assertTrue(invalid.lines.isEmpty())
    }
}
