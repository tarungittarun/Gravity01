package `in`.opentrack.india.e2e

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Until
import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.WebsiteBrowserRequest
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WebsiteRequestPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** TEST-OWNED replies only. These verify the installed Android code path, NOT provider access.
 * The acceptance workflow separately requires an actual source answer and source-qualified UI. */
@RunWith(AndroidJUnit4::class)
class WimtMobileRequestSimulationTest {
    private val site = PublicPageChecks.ORG
    private val day = 20_000L
    private val now = 1_800_000_000_000L

    @Test fun oneHonestWebsiteRequestStopsAtRefusalAndDoesNotUseFallbackAsWimt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("org_website_request_pause", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        var calls = 0
        var asked = ""
        val gate = WebsiteRequestPacing(context)
        val result = PublicPageChecks.fetch(site, "20651", day, now, get = { url ->
            asked = url
            calls++
            Http.Result.Failed(Http.Result.Kind.HTTP, "HTTP 403")
        })
        assertEquals("https://whereismytrain.org.in/?trainNo=20651", asked)
        assertEquals(1, calls)
        assertFalse(result.pageAnswered)
        assertNull(result.orgReport)
        assertTrue(result.detail.contains("HTTP 403"))
        gate.observe(result, now)
        val reopened = WebsiteRequestPacing(context)
        val blocked = reopened.paused(now + 1_000)
        assertTrue("a new screen should preserve the host-wide refusal pause", blocked != null)
        assertFalse(blocked!!.pageAnswered)
        assertTrue(blocked.detail.contains("no new request"))
        // A second explicit attempt at a different train must be intercepted BEFORE fetch.
        val next = reopened.paused(now + 2_000) ?: PublicPageChecks.fetch(
            site, "20652", day, now + 2_000, get = { calls++; error("unexpected website GET") })
        assertFalse(next.pageAnswered)
        assertEquals("refusal must not produce another GET", 1, calls)
        assertNull(reopened.paused(now + WebsiteRequestPacing.PAUSE_MS))
        prefs.edit().clear().commit()
    }

    @Test fun challengePageIsNotQualifiedWebsiteData() {
        val challenge = "<html><title>Checking your browser before accessing</title>" +
            "<p>Please wait for up to 5 seconds...</p></html>"
        var calls = 0
        val result = PublicPageChecks.fetch(site, "20651", day, now, get = {
            calls++
            Http.Result.Ok(challenge, fromCache = false, ageMillis = 0)
        })
        assertEquals(1, calls)
        assertNull("200 browser verification is not a website past card", result.orgReport)
        assertNull(result.stationClaim)
        assertEquals(0, result.lines.size)
        assertFalse("browser challenge cannot supply website station events", result.orgReport != null)
        assertEquals("en-IN,en;q=0.8", PublicPageChecks.headersFor(site)["Accept-Language"])
        assertFalse(PublicPageChecks.headersFor(site).keys.any { it.equals("Cookie", true) })
    }

    @Test fun browserDocumentNeverNavigatesToAnUnapprovedUrl() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (url in listOf("http://whereismytrain.org.in/?trainNo=20651",
                "https://whereismytrain.org.in/?trainNo=20651&other=1",
                "https://other.example/?trainNo=20651")) {
            val answer = WebsiteBrowserRequest.document(context, url)
            assertTrue("unexpected browser navigation for $url", answer is Http.Result.Failed)
            assertEquals("invalid website URL", (answer as Http.Result.Failed).message)
        }
    }

    @Test fun installedApkOpensAnOfflineTrainTrackWithoutAskingWebsite() {
        // CI disables Wi-Fi and mobile data. This is only an app-launch/track smoke;
        // it must NOT be confused with a successful online provider test.
        E2E.launch()
        E2E.awaitSearchScreen()
        assertTrue("train search missing", E2E.tap("modeTrain"))
        assertTrue("offline 20651 missing", E2E.typeInBox("trainBox", "20651") &&
            E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS))
        assertTrue("suggestion missing", E2E.tapSuggestion("trainBox"))
        assertTrue("Find missing", E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
        assertTrue("offline result missing", E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.LOCAL_MS))
        assertTrue("track not opened", E2E.openTrackFromFirstResult())
        assertEquals("20651", E2E.text("trainNo"))
        E2E.shotWimt("wimt-simulation-offline-track")
    }
}
