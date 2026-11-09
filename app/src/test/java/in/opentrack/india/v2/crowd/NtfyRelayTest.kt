package `in`.opentrack.india.v2.crowd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crowd-actuals relay (ADR-006, accepted): encode/decode round-trips, publish/poll against canned
 * wires, and the honest edges - a dead relay is an empty list, a junk line is dropped, a half-report
 * is null. No network in these tests; the wire seam is a lambda (ErailTransport's pattern).
 */
class NtfyRelayTest {

    private fun report() = StopReport("16579", "SMET", StopReport.Kind.DEPARTED, 637, 1_760_000_000_000L)

    @Test
    fun a_report_survives_its_own_json_round_trip() {
        val json = report().toJson()
        assertEquals(report(), StopReport.fromJson(json))
    }

    @Test
    fun a_quoted_station_name_round_trips_through_the_escapes() {
        val odd = StopReport("12627", "SBC", StopReport.Kind.ARRIVED, 0, 42L)
        assertEquals(odd, StopReport.fromJson(odd.toJson()))
    }

    @Test
    fun junk_is_null_not_a_half_report() {
        assertNull(StopReport.fromJson("{\"trainNo\":\"16579\"}"))
        assertNull(StopReport.fromJson("not json at all"))
        assertNull(StopReport.fromJson("{\"trainNo\":\"16579\",\"stationCode\":\"SMET\",\"kind\":\"TELEPORTED\",\"railMinute\":5,\"reportedAt\":1}"))
        assertNull(StopReport.fromJson("{\"trainNo\":\"16579\",\"stationCode\":\"SMET\",\"kind\":\"DEPARTED\",\"railMinute\":9999,\"reportedAt\":1}"))
    }

    @Test
    fun publish_posts_the_report_to_the_trains_topic() {
        var askedUrl: String? = null
        var askedBody: String? = null
        val relay = NtfyRelay(fetch = { url, method, body ->
            askedUrl = url; askedBody = body
            NtfyRelay.Wire.Ok(200, "{\"id\":\"x\"}")
        })
        val wire = relay.publish(report())
        assertTrue(wire is NtfyRelay.Wire.Ok)
        assertEquals("https://ntfy.sh/ct-16579", askedUrl)
        assertEquals(report().toJson(), askedBody)
    }

    @Test
    fun poll_reads_only_message_events_and_decodes_the_payloads() {
        val mine = report().toJson()
        val body = """
            {"id":"a","time":1,"event":"open","message":""}
            {"id":"b","time":2,"event":"message","message":"${mine.replace("\"", "\\\"")}"}
            {"id":"c","time":3,"event":"keepalive","message":""}
            {"id":"d","time":4,"event":"message","message":"this is not a report"}
        """.trimIndent()
        val relay = NtfyRelay(fetch = { _, _, _ -> NtfyRelay.Wire.Ok(200, body) })
        val reports = relay.pollRecent("16579")
        assertEquals(1, reports.size)
        assertEquals(report(), reports[0])
    }

    @Test
    fun a_dead_relay_is_an_empty_list_and_the_topic_name_stays_honest() {
        val relay = NtfyRelay(fetch = { _, _, _ -> NtfyRelay.Wire.Unreachable("UnknownHost") })
        assertTrue(relay.pollRecent("16579").isEmpty())
        assertEquals("ct-16579", relay.topicFor("16579"))
    }
    /**
     * The REAL ntfy.sh poll bytes, captured by CI itself on 2026-10-12 (commit comment, run 38254956503:
     * CI published id QAjhuL9tktAO and its own poll returned it - relay and parser shape both proven
     * against reality, not assumption). Every hand-rolled line filter in the parser must survive THESE
     * bytes; if the owner's phone still reads 0 from a topic that CI can read, the phone's network path
     * is the broken link, not this file.
     */
    @Test
    fun `the real captured ntfy poll line parses to the canned report`() {
        val body = javaClass.getResourceAsStream("/captures/ntfy-poll-ct-cap-2026-10-12.txt")!!
            .readBytes().toString(Charsets.UTF_8)
        val relay = NtfyRelay(fetch = { _, _, _ -> NtfyRelay.Wire.Ok(200, body) })
        val poll = relay.pollRecentOrFailure("cap-38254956503")
        assertTrue("the relay answered", poll.relayOk)
        assertEquals("exactly one report in the captured line", 1, poll.reports.size)
        assertEquals("CAP", poll.reports[0].stationCode)
        assertEquals(StopReport.Kind.DEPARTED, poll.reports[0].kind)
        assertEquals(1025, poll.reports[0].railMinute)
        assertTrue("the raw bytes ride along for parse-independent verification", poll.rawBody.contains("QAjhuL9tktAO"))
    }

}
