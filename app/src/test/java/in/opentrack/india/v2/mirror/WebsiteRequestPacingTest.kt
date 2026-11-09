package `in`.opentrack.india.v2.mirror

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WebsiteRequestPacingTest {
    @Test fun refusalPausesAcrossScreensAndTrainNumbersButAnAnswerClearsIt() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("org_website_request_pause", Context.MODE_PRIVATE).edit().clear().commit()
        val at = 1_800_000_000_000L
        val gate = WebsiteRequestPacing(context)
        assertNull(gate.paused(at))
        gate.observe(PublicPageChecks.Result("Request failed: HTTP · HTTP 403", at, false), at)
        val reopened = WebsiteRequestPacing(context)
        assertEquals(false, reopened.paused(at + 1_000)!!.pageAnswered)
        assertTrue(reopened.paused(at + 1_000)!!.detail.contains("no new request"))
        // Repeated UI attempts must never slide the deadline forward indefinitely.
        reopened.observe(reopened.paused(at + 10_000)!!, at + 10_000)
        assertNull(reopened.paused(at + WebsiteRequestPacing.PAUSE_MS))
        gate.observe(PublicPageChecks.Result("Request failed: HTTP · HTTP 429", at, false), at)
        assertTrue(reopened.paused(at + 60_000)!!.detail.contains("HTTP 429"))
        gate.observe(PublicPageChecks.Result("site answered", at + 60_000, true), at + 60_000)
        assertNull(reopened.paused(at + 60_001))
        assertFalse("a parse-only failure is not a refusal",
            WebsiteRequestPacing(context).paused(at + 60_002) != null)
    }
}
