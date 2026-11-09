package `in`.opentrack.india.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Private, opt-in operator correlation for one refused main-frame website navigation.
 * Never put this receipt in UI, crash reports, Actions summaries or CI artifacts. */
object WebsiteRefusalMetadata {
    private val urlPattern = Regex("https://whereismytrain\\.org\\.in/\\?trainNo=[0-9]{5}")
    // Accept the original header value (including a possible edge suffix), without splitting it.
    // Reject CR/LF, controls and oversized/unusual values rather than logging a forged second line.
    private val safeId = Regex("[A-Za-z0-9._~:+/-]{1,200}(?: [A-Za-z0-9._~:+/-]{1,80})?")

    fun header(headers: Map<String, String>?): String? =
        headers?.entries?.firstOrNull { it.key.equals("x-hcdn-request-id", ignoreCase = true) }?.value

    fun line(url: String, rawId: String?, atMillis: Long): String? {
        if (!urlPattern.matches(url)) return null
        val id = when {
            rawId == null -> "<absent>"
            safeId.matches(rawId) -> rawId // exact value, even if it is a 32-hex string
            else -> "<invalid-or-oversized>"
        }
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(atMillis))
        return "HTTP 403; x-hcdn-request-id=$id; atUtc=$utc; url=$url"
    }
}
