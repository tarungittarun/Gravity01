package `in`.opentrack.india.core

/** Bounded operational metadata for a refused website GET. No response body, cookie, challenge
 * token, device identifier, full URL or request headers enter the diary. Hostinger's own support
 * asks for the CDN request ID when its security rule blocks a legitimate client. This ID is a
 * non-secret correlation handle, not an authentication credential or a way around the block. */
object SiteRefusalReceipt {
    private val requestId = Regex("[A-Za-z0-9_-]{8,80}")

    fun forResponse(host: String, status: Int, header: (String) -> String?): String? {
        if (!host.equals("whereismytrain.org.in", ignoreCase = true) || status !in setOf(403, 429))
            return null
        val server = if (header("Server")?.trim()?.equals("hcdn", ignoreCase = true) == true)
            "hcdn" else "unidentified"
        // The first whitespace-delimited token is the request identifier. Some CDN headers append
        // an edge name; it is not needed to correlate a single response. Reject anything unusual.
        val id = header("x-hcdn-request-id")?.trim()?.substringBefore(' ')
            ?.takeIf { requestId.matches(it) } ?: "unavailable"
        val retry = header("Retry-After")?.trim()?.toIntOrNull()
            ?.takeIf { it in 0..86_400 }?.toString() ?: "none"
        return "website HTTP $status; server=$server; cdnRequestId=$id; retryAfterSeconds=$retry; " +
            "no page body read (refusal is not a station report)"
    }
}
