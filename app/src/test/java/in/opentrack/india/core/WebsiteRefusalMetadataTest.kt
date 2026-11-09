package `in`.opentrack.india.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test-owned headers only. Never contact the website or print a real request ID. */
class WebsiteRefusalMetadataTest {
    private val url = "https://whereismytrain.org.in/?trainNo=20651"
    private val id = "0123456789abcdef0123456789abcdef" // intentionally a digest-shaped ID

    @Test fun `case insensitive header and exact UTC correlation values`() {
        val headers = mapOf("X-HCDN-Request-ID" to "$id edge-1", "Set-Cookie" to "secret=do-not-log")
        assertEquals("$id edge-1", WebsiteRefusalMetadata.header(headers))
        val receipt = WebsiteRefusalMetadata.line(url, WebsiteRefusalMetadata.header(headers), 0L)!!
        assertEquals("HTTP 403; x-hcdn-request-id=$id edge-1; " +
            "atUtc=1970-01-01T00:00:00.000Z; url=$url", receipt)
        assertFalse(receipt.contains("Set-Cookie"))
        assertFalse(receipt.contains("do-not-log"))
    }

    @Test fun `missing and hostile headers cannot invent an exact id or forge a log line`() {
        assertNull(WebsiteRefusalMetadata.header(null))
        assertTrue(WebsiteRefusalMetadata.line(url, null, 0L)!!.contains("x-hcdn-request-id=<absent>"))
        val bad = WebsiteRefusalMetadata.line(url, "$id\n[HTTP] GET other.host", 0L)!!
        assertTrue(bad.contains("x-hcdn-request-id=<invalid-or-oversized>"))
        assertFalse(bad.contains("other.host"))
        assertTrue(WebsiteRefusalMetadata.line(url, "x".repeat(500), 0L)!!
            .contains("<invalid-or-oversized>"))
        assertNull(WebsiteRefusalMetadata.line("https://other.host/?trainNo=20651", id, 0L))
        assertNull(WebsiteRefusalMetadata.line(url + "&cookie=oops", id, 0L))
    }

    @Test fun `only opt in diary preserves exact 32 hex id while ordinary lines stay scrubbed`() {
        val file = File.createTempFile("website-refusal-test", ".txt").apply { delete() }
        val diary = Diary(file)
        diary.logWebsiteRefusal(url, id, 0L)
        assertFalse(file.exists())
        diary.setEnabled(true)
        diary.logWebsiteRefusal(url, id, 0L)
        diary.log("HTTP", "ordinary digest=$id")
        assertTrue(diary.awaitIdle())
        val body = file.readText()
        assertTrue(body.contains("[SITE_REFUSAL_PRIVATE] HTTP 403; x-hcdn-request-id=$id;"))
        assertTrue(body.contains("atUtc=1970-01-01T00:00:00.000Z; url=$url"))
        assertTrue(body.contains("ordinary digest=<redacted-digest>"))
        assertTrue(diary.text().contains("x-hcdn-request-id=$id"))
        file.delete()
    }
}
