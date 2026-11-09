package `in`.opentrack.india.v2.mirror

import android.content.Context

/** Host-wide, persisted courtesy pause after a website HTTP refusal. Never bypass or relabel a
 * cached page as new. A new screen or train number does not reset the refused host's pause. */
class WebsiteRequestPacing(context: Context) {
    private val prefs = context.getSharedPreferences("org_website_request_pause", Context.MODE_PRIVATE)

    fun paused(now: Long): PublicPageChecks.Result? {
        val until = prefs.getLong("until", 0L)
        val code = prefs.getInt("code", 0)
        if (code !in listOf(403, 429) || until <= now || until - now > PAUSE_MS) return null
        val minutes = (until - now + 59_999L) / 60_000L
        return PublicPageChecks.Result(
            "Website request paused after HTTP $code; no new request. Retry in $minutes min.",
            now, false)
    }

    fun observe(result: PublicPageChecks.Result, now: Long) {
        if (result.pageAnswered) {
            prefs.edit().remove("until").remove("code").apply()
            return
        }
        if (result.detail.startsWith("Website request paused")) return
        val code = when {
            result.detail.contains("HTTP 403") -> 403
            result.detail.contains("HTTP 429") -> 429
            else -> return
        }
        prefs.edit().putLong("until", now + PAUSE_MS).putInt("code", code).apply()
    }

    companion object { const val PAUSE_MS = 15 * 60_000L }
}
