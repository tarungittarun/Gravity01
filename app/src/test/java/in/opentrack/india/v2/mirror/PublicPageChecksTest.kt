package `in`.opentrack.india.v2.mirror

import `in`.opentrack.india.core.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PublicPageChecksTest {
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse("2026-10-14")!!.time / 86_400_000L
    private val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
        .parse("2026-10-15 04:19:00 +0530")!!.time
    private val relay = """{"success":true,"train_number":"16227","train_start_date":"2026-10-14",
        "data_from":"ntes","update_time":"2026-10-15 04:17:00 +0530",
        "status":"D","current_station_code":"TKE",
        "previous_stations":[{"station_code":"RRB","eta":"03:38","etd":"03:40"}],
        "upcoming_stations":[{"station_code":"BDVT","eta":"04:40","etd":"04:42"}]}"""

    @Test fun `relay reports returned origin provenance and station claim but never a stop event`() {
        val r = PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI, relay, "16227", day, now)
        assertTrue(r.pageAnswered)
        assertTrue(r.detail.contains("origin 2026-10-14"))
        assertTrue(r.detail.contains("upstream ntes"))
        assertTrue(r.detail.contains("TKE departed"))
        assertTrue(r.detail.contains("route not verified against selected timetable"))
        assertEquals(null, r.stationClaim)
        assertTrue(r.detail.contains("ETA/ETD and timetable rows are not Act"))
        assertFalse(r.detail.contains("BDVT 04:40"))
    }

    @Test fun `wrong run stale clock and future update cannot look like recent matching check`() {
        val wrong = PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI,
            relay.replace("2026-10-14", "2026-10-15"), "16227", day, now)
        assertTrue(wrong.detail.contains("wrong train/origin run"))
        val stale = PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI,
            relay.replace("04:17:00", "04:09:00"), "16227", day, now)
        assertTrue(stale.detail.contains(">5 min old"))
        val future = PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI,
            relay.replace("04:17:00", "04:20:00"), "16227", day, now)
        assertTrue(future.detail.contains("future"))
        assertTrue(PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI,
            relay.replace("16227", "20689"), "16227", day, now).detail.contains("wrong train"))
    }

    @Test fun `public pages show status and selected date without converting timetable to position`() {
        val ixigo = """<html><script>Today 14-10-2026 train arrived TKE</script>
            <h1>16227 Running Status</h1><p>Today 15-10-2026</p><p>Yet to start</p>
            <div>BDVT 04:40 arrived on time</div></html>"""
        val r = PublicPageChecks.parse(PublicPageChecks.IXIGO, ixigo, "16227", day, now)
        assertTrue(r.detail.contains("page-selected 15-10-2026"))
        assertTrue(r.detail.contains("wrong origin run"))
        assertTrue(r.detail.contains("yet to start"))
        assertFalse(r.detail.contains("train arrived TKE"))
        assertTrue(PublicPageChecks.parse(PublicPageChecks.IXIGO, ixigo, "20689", day, now)
            .detail.contains("did not identify train"))
        val orgResult = PublicPageChecks.parse(PublicPageChecks.ORG,
            "<p>16227 Running Status</p><p>Last Updated: 04:19</p><p>TKE On time</p>",
            "16227", day, now)
        assertTrue(orgResult.detail.contains("does not identify a selected origin date"))
        assertTrue(orgResult.detail.contains("not Google's WIMT phone app"))
        assertTrue(orgResult.detail.contains("page update label 04:19"))
    }

    @Test fun `20689 stale website timeline cannot become the phone apps SMET arrival`() {
        val run = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse("2026-10-18")!!.time / 86_400_000L
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
            .parse("2026-10-18 13:55:00 +0530")!!.time
        val page = """<h1>20689 Running Status</h1><p>Last Updated: 01:27:33 PM</p>
            Journey Timeline <!-- Station Details Card -->
            <h3>Bhadravati (BDVT)</h3> ARRIVAL 01:11 PM 12:50 PM DEPARTURE 01:17 PM 12:52 PM"""
        val result = PublicPageChecks.parse(PublicPageChecks.ORG, page, "20689", run, at,
            knownCodes = setOf("BDVT", "SMET"))
        assertTrue(result.pageAnswered)
        assertEquals(null, result.orgReport)
        assertEquals("01:27:33 PM", result.pageUpdateLabel)
        val gap = PublicPageChecks.websiteGap(result, "SMET")!!
        assertTrue(gap.contains("no SMET arrival row"))
        assertTrue(gap.contains("Last displayed: BDVT"))
        assertTrue(gap.contains("position unknown"))
        assertTrue(gap.contains("not the phone app"))
        assertFalse(gap.contains("SMET arrived"))
        assertEquals(null, PublicPageChecks.websiteGap(result.copy(pageAnswered = false), "SMET"))
        assertEquals(null, PublicPageChecks.websiteGap(result.copy(lines = emptyMap()), "SMET"))
        assertEquals(null, PublicPageChecks.websiteGap(result.copy(lines = result.lines +
            ("SMET" to "Page shows SMET: ARRIVAL 01:27 PM")), "SMET"))
    }

    @Test fun `each dropdown choice requests its own endpoint and cache never counts as fresh`() {
        val urls = mutableListOf<String>()
        for (name in PublicPageChecks.names) {
            val response = if (name == PublicPageChecks.RAIL_YATRI) relay
                else "<h1>16227 Running Status</h1><p>Today 14-10-2026</p><p>Yet to start</p>"
            val result = PublicPageChecks.fetch(name, "16227", day, now) { url ->
                urls += url
                Http.Result.Ok(response, fromCache = false, ageMillis = 0)
            }
            assertTrue("$name did not make a request", result.pageAnswered)
            assertTrue("$name omitted its returned status", result.detail.contains("16227"))
            assertEquals("HTTP body byte count missing", response.toByteArray(Charsets.UTF_8).size,
                result.responseBytes)
            assertTrue("only a digest, not raw HTML, belongs in a source receipt",
                result.responseDigest?.matches(Regex("[0-9a-f]{16}")) == true)
            assertFalse(result.fromHttpCache)
        }
        assertEquals(3, urls.distinct().size)
        assertTrue(urls[0].contains("/16227/1.json?start_day=1"))
        assertTrue(urls[1].endsWith("/trains/16227/running-status"))
        assertTrue(urls[2].endsWith("/?trainNo=16227"))

        val cached = PublicPageChecks.fetch(PublicPageChecks.RAIL_YATRI, "16227", day, now) {
            Http.Result.Ok(relay, fromCache = true, ageMillis = 900_000)
        }
        assertFalse("cached page is not a new source answer", cached.pageAnswered)
        assertTrue(cached.fromHttpCache)
        assertEquals(relay.toByteArray(Charsets.UTF_8).size, cached.responseBytes)
        assertTrue(cached.detail.contains("Cached HTTP body (15 min old)"))
        assertTrue(cached.detail.contains("No fresh observation"))
        val failed = PublicPageChecks.fetch(PublicPageChecks.IXIGO, "16227", day, now) {
            Http.Result.Failed(Http.Result.Kind.TIMEOUT, "timeout")
        }
        assertFalse(failed.pageAnswered)
        assertTrue(failed.detail.contains("Request failed: TIMEOUT"))
        var requested = false
        val outside = PublicPageChecks.fetch(PublicPageChecks.RAIL_YATRI, "16227", day - 3, now) {
            requested = true
            Http.Result.Ok(relay, false, 0)
        }
        assertFalse("unsupported run must not silently query today", requested)
        assertFalse(outside.pageAnswered)
    }

    @Test fun `fresh matched RailYatri tmt claim is displayed without inventing Act or GPS`() {
        val tmt = relay.replace("\"data_from\":\"ntes\"", "\"data_from\":\"tmt\"")
            .replace("\"current_station_code\":\"TKE\"", "\"current_station_code\":\"SMET\"")
            .replace("\"data_from\":\"tmt\"", "\"source\":\"MYS\",\"destination\":\"TLGP\",\"data_from\":\"tmt\"")
        val codes = setOf("MYS", "SMET", "KMSI", "TLGP")
        val result = PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI, tmt,
            "16227", day, now, "MYS", "TLGP", codes)
        assertEquals("SMET", result.stationClaim?.code)
        assertEquals("departed", result.stationClaim?.status)
        assertEquals("tmt", result.stationClaim?.upstream)
        assertTrue(result.detail.contains("NOT a station event time"))
        assertTrue(result.lines.isEmpty()) // ETA/ETD are not Act
        assertEquals(null, PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI, tmt,
            "16227", day, now, "MYS", "TLGP", setOf("YPR")).stationClaim)
        assertEquals(null, PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI, tmt,
            "16227", day, now, "YPR", "TLGP", codes).stationClaim)
        assertEquals(null, PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI, tmt,
            "16227", day - 1, now, "MYS", "TLGP", codes).stationClaim)
        assertEquals(null, PublicPageChecks.parse(PublicPageChecks.RAIL_YATRI,
            tmt.replace("04:17:00", "04:09:00"), "16227", day, now, "MYS", "TLGP", codes).stationClaim)
        val cached = PublicPageChecks.fetch(PublicPageChecks.RAIL_YATRI,
            "16227", day, now, "MYS", "TLGP", codes) {
            Http.Result.Ok(tmt, fromCache = true, ageMillis = 15 * 60_000L)
        }
        assertEquals(null, cached.stationClaim)
    }

    @Test fun `undated org page shows its actual printed rows only as comparison`() {
        val page = """<h1>16227</h1><p>Last Updated: 05:08:34 AM</p>
          <div>Journey Timeline</div>
          <div>Birur Jn (RRB) • PF 2 ARRIVAL 03:37 AM 02:53 AM DEPARTURE 03:43 AM 02:55 AM</div>
          <div>Tarikere (TKE) • PF 1 ARRIVAL 04:02 AM 03:43 AM DEPARTURE 04:05 AM 03:45 AM</div>
          <div>Bhadravati (BDVT) ARRIVAL 04:20 AM 04:28 AM</div>"""
        val result = PublicPageChecks.parse(PublicPageChecks.ORG, page,
            "16227", day, now, knownCodes = setOf("RRB", "TKE", "BDVT"))
        assertTrue(result.detail.contains("no event-level dated observation"))
        assertEquals(null, result.stationClaim)
        assertEquals(3, result.lines.size)
        assertTrue(result.lines["RRB"]!!.contains("03:37"))
        assertTrue(result.lines["BDVT"]!!.contains("NOT dated Act"))
    }

    @Test fun `relay URL asks for origin-day offset rather than default today`() {
        val url = PublicPageChecks.url(PublicPageChecks.RAIL_YATRI, "16227", day, now)!!
        assertTrue(url.contains("/16227/1.json?start_day=1"))
        assertEquals(null, PublicPageChecks.url(PublicPageChecks.RAIL_YATRI, "16227", day - 3, now))
        assertEquals(null, PublicPageChecks.url(PublicPageChecks.IXIGO, "../../evil", day, now))
    }
}
