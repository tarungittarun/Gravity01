package `in`.opentrack.india.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteRefusalReceiptTest {
    @Test fun `website 403 keeps only bounded support receipt`() {
        val fields = mapOf("Server" to "hcdn",
            "x-hcdn-request-id" to "1234567890abcdef-sgp-edge1 sgp-edge1",
            "Retry-After" to "60", "Set-Cookie" to "challenge=secret")
        val result = SiteRefusalReceipt.forResponse("whereismytrain.org.in", 403, fields::get)!!
        assertTrue(result.contains("HTTP 403; server=hcdn"))
        assertTrue(result.contains("cdnRequestId=1234567890abcdef-sgp-edge1"))
        assertTrue(result.contains("retryAfterSeconds=60"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("challenge="))
    }

    @Test fun `no other host success or redirect gets a receipt`() {
        assertNull(SiteRefusalReceipt.forResponse("whereismytrain.in", 403) { "hcdn" })
        assertNull(SiteRefusalReceipt.forResponse("whereismytrain.org.in", 200) { "hcdn" })
        assertNull(SiteRefusalReceipt.forResponse("whereismytrain.org.in", 302) { "hcdn" })
    }

    @Test fun `unusual request id and retry header cannot inject log text`() {
        val fields = mapOf("Server" to "hcdn\nforged",
            "x-hcdn-request-id" to "abc\n[ENV] app=wrong", "Retry-After" to "forever")
        val result = SiteRefusalReceipt.forResponse("whereismytrain.org.in", 429, fields::get)!!
        assertTrue(result.contains("server=unidentified"))
        assertTrue(result.contains("cdnRequestId=unavailable"))
        assertTrue(result.contains("retryAfterSeconds=none"))
        assertFalse(result.contains("forged"))
        assertFalse(result.contains("wrong"))
    }
}
