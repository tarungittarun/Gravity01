package `in`.opentrack.india.remote

import `in`.opentrack.india.model.SegmentRow
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WimtLiveProviderTest {

    private fun stop(seq: Int, code: String, name: String, arr: Int?, dep: Int?, km: Int) = StopRow(
        seq = seq, code = code, name = name, state = "KR", lat = null, lon = null,
        arrivalMin = arr, departureMin = dep, journeyDay = 1, schedKm = km, isHalt = true,
    )

    private val stops = listOf(
        stop(1, "SBC", "KSR BENGALURU", null, 1035, 0),
        stop(2, "YPR", "YESVANTPUR JN", 1045, 1047, 6),
        stop(3, "TK", "TUMAKURU", 1120, 1122, 70),
        stop(4, "BDVT", "BHADRAVATI", 1320, 1322, 250),
        stop(5, "TLGP", "TALGUPPA", 1440, null, 370),
    )

    private val route = TrainRoute(
        trainNo = "12089", trainName = "JAN SHATABDI",
        srcCode = "SBC", srcName = "KSR BENGALURU", dstCode = "TLGP", dstName = "TALGUPPA",
        totalKm = 370, totalMin = 405, runDays = 0x7F, returnNo = "12090",
        stops = stops,
        segments = stops.zipWithNext().mapIndexed { i, (a, b) ->
            SegmentRow(i + 1, a.code, b.code, b.schedKm - a.schedKm, 60)
        },
    )

    private val sampleJson = """
    {
      "distance": 250.0,
      "curStn": "BDVT",
      "lastUpdateIsoDate": "2026-11-09T17:23:22.867110+05:30",
      "source_station": "SBC",
      "destination_station": "TLGP",
      "start_date": "09-11-2026",
      "days_schedule": [
        {
          "station_code": "SBC",
          "station_name": "KSR Bengaluru",
          "departed": true,
          "actual_arrival_tm": 1794225046,
          "actual_departure_time": "17:20",
          "delay_in_departure": 5.0,
          "platform": "3"
        },
        {
          "station_code": "YPR",
          "station_name": "Yesvantpur",
          "departed": true,
          "actual_arrival_time": "17:31",
          "actual_departure_time": "17:33",
          "delay_in_departure": 4.0,
          "platform": "1"
        },
        {
          "station_code": "BDVT",
          "station_name": "Bhadravati",
          "departed": false,
          "actual_arrival_time": "22:05",
          "actual_departure_time": "22:07",
          "delay_in_arrival": 3.0,
          "platform": "2"
        }
      ]
    }
    """.trimIndent()

    @Test
    fun parsesValidWimtScheduleAndSnapshot() {
        val result = WimtLiveProvider.parse(
            body = sampleJson,
            trainNo = "12089",
            route = route,
            originEpochDay = 20767L,
            nowMillis = 1794226000000L,
        )

        assertNotNull(result.snapshot)
        val snap = result.snapshot!!

        assertEquals("12089", snap.trainNo)
        assertEquals("BDVT", snap.atCode)
        assertEquals(StopStatus.ARRIVED, snap.event)
        assertEquals(3, snap.delayMinutes)

        // Check stop actuals
        val sbc = snap.perStop["SBC"]
        assertNotNull(sbc)
        assertEquals(true, sbc!!.departed)
        assertEquals("3", sbc.platform)
        assertEquals(5, sbc.delayDepartureMin)

        val ypr = snap.perStop["YPR"]
        assertNotNull(ypr)
        assertEquals(true, ypr!!.departed)
        assertEquals("1", ypr.platform)

        val bdvt = snap.perStop["BDVT"]
        assertNotNull(bdvt)
        assertEquals(false, bdvt!!.departed)
        assertEquals("2", bdvt.platform)
        assertEquals(3, bdvt.delayArrivalMin)
    }

    @Test
    fun intermediateNonHaltCurStnResolvesToLastCommercialDepartedHalt() {
        val intermediateJson = """
        {
          "distance": 150.0,
          "curStn": "HHL",
          "lastUpdateIsoDate": "2026-11-10T02:30:00+05:30",
          "days_schedule": [
            {
              "station_code": "SBC",
              "departed": true,
              "actual_arrival_time": "10:30",
              "actual_departure_time": "10:35",
              "delay_in_departure": 0
            },
            {
              "station_code": "YPR",
              "departed": true,
              "actual_arrival_time": "10:48",
              "actual_departure_time": "10:50",
              "delay_in_departure": 3.0,
              "platform": "1"
            },
            {
              "station_code": "HHL",
              "curStn": true,
              "stops": false,
              "actual_arrival_time": "11:15",
              "actual_departure_time": "11:15",
              "delay_in_departure": 2.0
            },
            {
              "station_code": "TK",
              "source": "eta",
              "actual_arrival_time": "11:30",
              "actual_departure_time": "11:32",
              "delay_in_arrival": 10.0,
              "platform": "2"
            }
          ]
        }
        """.trimIndent()

        val result = WimtLiveProvider.parse(
            body = intermediateJson,
            trainNo = "12089",
            route = route,
            originEpochDay = 20767L,
            nowMillis = 1794226000000L,
        )

        assertNotNull(result.snapshot)
        val snap = result.snapshot!!

        // HHL is not on route.stops; anchor should resolve to YPR (last departed commercial stop)
        assertEquals("YPR", snap.atCode)
        assertEquals(StopStatus.DEPARTED, snap.event)
        assertEquals(3, snap.delayMinutes)

        // Future stop TK with source="eta" must NOT have actual times
        val tk = snap.perStop["TK"]
        assertNotNull(tk)
        assertNull(tk!!.actualArrivalMin)
        assertNull(tk.actualDepartureMin)
        assertEquals("2", tk.platform)
    }

    @Test
    fun handlesInvalidJsonGracefully() {
        val result = WimtLiveProvider.parse(
            body = "<html><body>Error</body></html>",
            trainNo = "12089",
            route = route,
            originEpochDay = 20767L,
            nowMillis = 1794226000000L,
        )

        assertNull(result.snapshot)
        assertTrue(result.detail.contains("Invalid JSON"))
    }
}
