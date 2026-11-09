package `in`.opentrack.india.remote

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection

/** kilotrain's STNS example is a documented shape, not proof of a real NTES reply.
 * These tests pin the alternate request and the gates that prevent its future estimates being Act. */
class NtesMobileFallbackTest {
    private val day = 20739L // 13 Oct 2026
    private val body = """{"TrainNo":"20651","StartDate":"13-Oct-2026","STNS":[
        {"SC":"SBC","STD":"Source/15:00 13-Oct","ETD":"Source/15:00 13-Oct"},
        {"SC":"YPR","STA":"15:10","STD":"15:12","ETA":"15:22 13-Oct","ETD":"15:24 13-Oct","DARR":"12","DDEP":"12"},
        {"SC":"TK","STA":"16:00","STD":"16:02","ETA":"16:20 13-Oct","ETD":"16:22 13-Oct"},
        {"SC":"TTR","STA":"17:00","STD":"17:02","ETA":"17:20 13-Oct","ETD":"17:22 13-Oct"}
    ]}"""

    @Test
    fun `plain STNS is JSON not an AES envelope and echoes never become actual`() {
        val doc = NtesWire.unwrapJson(body)
        assertNotNull(doc)
        assertTrue(NtesMobileFallback.matchesRun(doc!!, "20651", day))
        assertFalse(NtesMobileFallback.matchesRun(doc, "20652", day))
        assertFalse(NtesMobileFallback.matchesRun(doc, "20651", day + 1))
        // R53's real encrypted STNS reply has no top-level StartDate. Dated, changed,
        // delay-bearing stop cells are still run evidence; schedule dates alone are not.
        assertTrue(NtesMobileFallback.matchesRun(JSONObject(body.replace("\"StartDate\":\"13-Oct-2026\",", "\"LUPDT\":\"16:11 13-Oct\",")), "20651", day))
        assertFalse(NtesMobileFallback.matchesRun(
            JSONObject(body.replace("\"StartDate\":\"13-Oct-2026\",", "\"LUPDT\":\"16:11 13-Oct\",").replace("15:22 13-Oct", "15:22 12-Oct")
                .replace("15:24 13-Oct", "15:24 12-Oct")), "20651", day,
        ))
        assertFalse(NtesMobileFallback.matchesRun(
            JSONObject(body.replace("\"StartDate\":\"13-Oct-2026\",", "\"LUPDT\":\"16:11 13-Oct\",").replace("\"DARR\":\"12\",\"DDEP\":\"12\"", "\"DARR\":\"\",\"DDEP\":\"\"")),
            "20651", day,
        ))
        val parsed = NtesWire.parse(doc.toString())!!
        assertEquals(4, parsed.rows.size)
        assertNull(parsed.rows[0].actDep) // schedule echoed in mobile ETD
        assertEquals(922, parsed.rows[1].actArr)
        assertEquals(924, parsed.rows[1].actDep)
        val now = RailTime.railMinuteToEpochMillis(day, 16 * 60 + 11)
        val safe = NtesMobileFallback.trustedRows(parsed, route(), day, now)
        assertEquals(922, safe.rows[1].actArr)
        assertEquals(924, safe.rows[1].actDep)
        assertNull("estimate after clock", safe.rows[2].actArr)
        assertNull("future estimate", safe.rows[3].actDep)
        assertNull("a rejected estimate cannot anchor delay", safe.rows[3].delayDep)
    }

    /** The first three STNS entries and LUPDT are transcribed from the owner's R54 diary.
     * The capture contained 29 rows, but no full payload; this is a partial, not a fake
     * 29-stop fixture. In particular it contains NO TrainNo or StartDate header. */
    private val observed16227 = """{"SRC":"MYS","LUPDT":"23:39 13-Oct","STNS":[
        {"SC":"MYS","STA":"Source","STD":"19:40 13-Oct","ETA":"Source","ETD":"19:40 13-Oct","DARR":"","DDEP":"On Time"},
        {"SC":"NHY","STA":"19:51 13-Oct","STD":"19:52 13-Oct","ETA":"19:51 13-Oct","ETD":"19:52 13-Oct","DARR":"On Time","DDEP":"On Time"},
        {"SC":"PANP","STA":"20:02 13-Oct","STD":"20:03 13-Oct","ETA":"20:05 13-Oct","ETD":"20:06 13-Oct","DARR":"00:03","DDEP":"00:03"}
    ]}"""

    @Test
    fun `missing bundled number still gets dated static stops without promoting guesses to actuals`() {
        val doc = JSONObject(observed16227.replace("\"ETA\":\"20:05 13-Oct\"", "\"ETA\":\"20:02 13-Oct\""))
        assertTrue(NtesMobileFallback.matchesScheduleRun(doc, "16227", day, "MYS", "PANP"))
        assertFalse(NtesMobileFallback.matchesScheduleRun(doc, "16227", day + 1, "MYS", "PANP"))
        assertFalse(NtesMobileFallback.matchesScheduleRun(doc, "16227", day, "SBC", "SMET"))
        assertFalse(NtesMobileFallback.matchesScheduleRun(doc.put("TrainNo", "99999"),
            "16227", day, "MYS", "PANP"))
        val synthetic = TrainRoute("16227", "from listing", "MYS", "MYS", "PANP", "PANP",
            0, null, null, null, listOf(
                StopRow(1, "MYS", "MYS", null, null, null, null, 1180, 0, 0, true),
                StopRow(2, "PANP", "PANP", null, null, null, 1202, null, 0, 0, true),
            ), emptyList())
        val parsed = NtesWire.parse(observed16227)!!
        val reconstructed = NtesRoute.fromStatus(parsed, synthetic)!!
        assertEquals(listOf("MYS", "NHY", "PANP"), reconstructed.stops.map { it.code })
        assertEquals(20 * 60 + 2, reconstructed.stops.last().arrivalMin)
    }

    @Test
    fun `captured no-header STNS accepts selected corridor and replaces contradictory device timetable`() {
        val doc = JSONObject(observed16227)
        val wrong = route() // device route starts SBC, not the official response's MYS
        assertFalse(NtesMobileFallback.matchesRun(doc, "16227", day))
        assertTrue(NtesMobileFallback.matchesRun(doc, "16227", day, "MYS", "PANP"))
        assertFalse(NtesMobileFallback.matchesRun(doc, "16227", day, "SBC", "SMET"))
        assertFalse(NtesMobileFallback.matchesRun(doc, "16227", day - 1, "MYS", "PANP"))
        assertFalse(NtesMobileFallback.matchesRun(
            JSONObject(observed16227.replace("23:39 13-Oct", "23:39 12-Oct")),
            "16227", day, "MYS", "PANP",
        ))
        assertFalse(NtesMobileFallback.matchesRun(
            JSONObject(observed16227).put("TrainNo", "99999"),
            "16227", day, "MYS", "PANP",
        ))
        val parsed = NtesWire.parse(doc.toString())!!
        assertEquals(3, parsed.rows[2].delayArr)
        assertEquals(3, parsed.rows[2].delayDep)
        val official = NtesRoute.fromStatus(parsed, wrong)!!
        assertEquals(listOf("MYS", "NHY", "PANP"), official.stops.map { it.code })
        assertEquals(19 * 60 + 40, official.stops.first().departureMin)
        assertEquals(20 * 60 + 2, official.stops.last().arrivalMin)
        val stamp = NtesMobileFallback.updateMillis(doc, day)!!
        assertEquals(23 * 60 + 39, RailTime.istMinuteOfDay(stamp))
        val safe = NtesMobileFallback.trustedRows(parsed.copy(feedStampMillis = stamp),
            official, day, RailTime.railMinuteToEpochMillis(day, 23 * 60 + 40))
        assertEquals(20 * 60 + 5, safe.rows[2].actArr)
        assertEquals(20 * 60 + 6, safe.rows[2].actDep)
        assertEquals(3, safe.rows[2].delayArr)
        val snap = NtesAdapter.toSnapshot(safe, "16227", day)!!
        assertEquals("PANP", snap.atCode)
        assertEquals(20 * 60 + 5, snap.perStop["PANP"]!!.actualArrivalMin)
        assertEquals(20 * 60 + 6, snap.perStop["PANP"]!!.actualDepartureMin)
    }

    @Test
    fun `official last-known cache keeps route and actuals paired to selected run`() {
        val doc = JSONObject(observed16227)
        val parsed = NtesWire.parse(doc.toString())!!
        val route = NtesRoute.fromStatus(parsed, route())!!
        val stamp = NtesMobileFallback.updateMillis(doc, day)!!
        val safe = NtesMobileFallback.trustedRows(parsed.copy(feedStampMillis = stamp), route, day,
            stamp + 60_000)
        val fresh = NtesAdapter.toSnapshot(safe, "16227", day)!!.copy(
            reportedRoute = route, fetchedAtMillis = stamp + 60_000)
        val record = OfficialRunCache.encode(fresh)
        val cached = OfficialRunCache.decode(record, "16227", day, "MYS", "PANP",
            stamp + 120_000)!!
        assertEquals(`in`.opentrack.india.model.SourceKind.CACHED, cached.sourceKind)
        assertEquals(listOf("MYS", "NHY", "PANP"), cached.reportedRoute!!.stops.map { it.code })
        assertEquals(20 * 60 + 5, cached.perStop["PANP"]!!.actualArrivalMin)
        assertEquals(stamp, cached.feedStampMillis)
        // 16227's actual 29-halt answer includes Y. An old 2-character-only cache
        // validator discarded the whole saved run on a transient NTES timeout.
        val withSingleLetterCode = fresh.copy(reportedRoute = route.copy(stops =
            route.stops.toMutableList().apply { add(1, route.stops[1].copy(code = "Y")) }))
        val restored = OfficialRunCache.decode(OfficialRunCache.encode(withSingleLetterCode),
            "16227", day, "MYS", "PANP", stamp + 120_000)
        assertEquals("Y", restored?.reportedRoute?.stops?.get(1)?.code)
        assertNull(OfficialRunCache.decode(record, "16227", day + 1, "MYS", "PANP", stamp + 120_000))
        assertNull(OfficialRunCache.decode(record, "16227", day, "SBC", "SMET", stamp + 120_000))
        assertNull(OfficialRunCache.decode(record, "16227", day, "MYS", "PANP",
            stamp + OfficialRunCache.MAX_AGE_MS + 120_000))
    }

    @Test
    fun `past day two stop stays on day two while next estimate cannot turn into actual`() {
        val status = JSONObject("""{"LUPDT":"00:55 14-Oct","STNS":[
            {"SC":"MYS","STD":"23:40 13-Oct","ETD":"23:40 13-Oct"},
            {"SC":"NHY","STA":"00:20 14-Oct","STD":"00:21 14-Oct",
              "ETA":"00:23 14-Oct","ETD":"00:24 14-Oct","DARR":"00:03","DDEP":"00:03"},
            {"SC":"PANP","STA":"01:30 14-Oct","STD":"01:31 14-Oct",
              "ETA":"01:33 14-Oct","ETD":"01:34 14-Oct","DARR":"00:03","DDEP":"00:03"}
        ]}""")
        assertTrue(NtesMobileFallback.matchesRun(status, "16227", day, "MYS", "PANP"))
        val parsed = NtesWire.parse(status.toString())!!
        val official = NtesRoute.fromStatus(parsed, route())!!
        assertEquals(1440 + 20, official.stops[1].arrivalMin)
        assertEquals(1, official.stops[1].journeyDay)
        val stamp = NtesMobileFallback.updateMillis(status, day)!!
        assertEquals(day + 1, RailTime.istEpochDay(stamp))
        val safe = NtesMobileFallback.trustedRows(parsed.copy(feedStampMillis = stamp), official,
            day, RailTime.railMinuteToEpochMillis(day + 1, 60))
        assertEquals(1440 + 23, safe.rows[1].actArr)
        assertNull(safe.rows[2].actArr)
        assertNull(safe.rows[2].delayArr)
    }

    @Test
    fun `mobile request warms the same host and carries cookies into JSON POST`() {
        val seen = ArrayList<String>()
        var sent = ""
        val response = NtesMobileFallback.fetch("20651", day, open = { url ->
            seen.add(url.path)
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int {
                    if (url.path != "/mntes/") {
                        assertEquals("SID=abc", getRequestProperty("Cookie"))
                        assertEquals("https://enquiry.indianrail.gov.in/mntes/", getRequestProperty("Referer"))
                    }
                    return 200
                }
                override fun getHeaderFields(): Map<String, List<String>> =
                    if (url.path == "/mntes/") mapOf("Set-Cookie" to listOf("SID=abc; Path=/"))
                    else emptyMap()
                override fun getOutputStream() = ByteArrayOutputStream().also { out ->
                    // The caller writes after getOutputStream returns. Capture bytes at inputStream.
                    output = out
                }
                private var output: ByteArrayOutputStream? = null
                override fun getInputStream(): ByteArrayInputStream {
                    sent = output?.toString("UTF-8").orEmpty()
                    return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
                }
            }
        })
        assertTrue("second request must read a body", response is `in`.opentrack.india.core.Http.Result.Ok)
        assertEquals(listOf("/mntes/", "/crisns/AppServAnd"), seen)
        assertTrue(sent.contains("\"queryType\":\"6\""))
        assertTrue(sent.contains("\"startDate\":\"13-Oct-2026\""))
    }

    private fun route(): TrainRoute = TrainRoute(
        "20651", "SBC TLGP", "SBC", "Bengaluru", "TTR", "Tiptur", 100, 120, null, null,
        listOf(
            StopRow(1, "SBC", "Bengaluru", null, null, null, null, 900, 1, 0, true),
            StopRow(2, "YPR", "Yesvantpur", null, null, null, 910, 912, 1, 5, true),
            StopRow(3, "TK", "Tumkur", null, null, null, 960, 962, 1, 70, true),
            StopRow(4, "TTR", "Tiptur", null, null, null, 1020, 1022, 1, 100, true),
        ), emptyList(),
    )
}
