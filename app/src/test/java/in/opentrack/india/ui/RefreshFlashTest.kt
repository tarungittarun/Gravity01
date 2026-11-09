package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshFlashTest {
    @Test fun unchangedWebsiteEventIsNotAnErrorButFailedConnectionIs() {
        val source = PublicPageChecks.ORG
        assertNull(RefreshFlash.connectionError(source, "candidate" to
            "same last site-reported stop; checked in 650 ms", null, true))
        assertEquals("Connection failed", RefreshFlash.connectionError(source,
            "last-known" to "Request failed: connection reset", null, false))
        assertEquals("Website refused request (HTTP 403)", RefreshFlash.connectionError(source,
            "unverified" to "Request failed: HTTP · HTTP 403", null, false))
        assertEquals("Website rate limited (HTTP 429)", RefreshFlash.connectionError(source,
            "unverified" to "Request failed: HTTP · HTTP 429", null, false))
        assertEquals("Website request paused", RefreshFlash.connectionError(source,
            "unverified" to "Website request paused after HTTP 403; no new request. Retry in 15 min.",
            null, false))
        assertNull("a new page answer must clear an old failure", RefreshFlash.connectionError(source,
            "comparison" to "HTTP 403 from earlier request", null, true))
        assertEquals("Source timed out", RefreshFlash.connectionError(source,
            "unverified" to "timed out", null, false))
    }

    @Test fun firstLoadWebsiteFailureStillNamesWebsiteWhenOfficialIsDisplayed() {
        val site = PublicPageChecks.ORG
        val official = TrackingSources.OFFICIAL
        assertEquals("Website refused request (HTTP 403)", RefreshFlash.preferredWebsiteError(
            official, site, "unverified" to "Request failed: HTTP · HTTP 403", false))
        assertNull(RefreshFlash.preferredWebsiteError(official, official,
            "unverified" to "Request failed: HTTP · HTTP 403", false))
        assertNull(RefreshFlash.preferredWebsiteError(site, site,
            "unverified" to "Request failed: HTTP · HTTP 403", false))
        assertNull(RefreshFlash.preferredWebsiteError(official, site,
            "comparison" to "page answered", true))
    }

    @Test fun officialErrorIsSeparateFromAUsableEarlierStop() {
        assertEquals("Connection failed", RefreshFlash.connectionError(TrackingSources.OFFICIAL,
            "last-known" to "saved stop", OfficialRequestReceipt(
                OfficialRequestReceipt.Kind.REQUEST_FAILED), null))
        assertEquals("Source timed out", RefreshFlash.connectionError(TrackingSources.OFFICIAL,
            "last-known" to "saved stop", OfficialRequestReceipt(
                OfficialRequestReceipt.Kind.REQUEST_FAILED, "request timed out"), null))
        assertEquals("Source blocked request", RefreshFlash.connectionError(TrackingSources.OFFICIAL,
            "unverified" to "HTTP 429", OfficialRequestReceipt(
                OfficialRequestReceipt.Kind.REQUEST_FAILED), null))
        assertNull("answered but unusable is not a network fault",
            RefreshFlash.connectionError(TrackingSources.OFFICIAL,
                "unverified" to "no per-stop actual minute", OfficialRequestReceipt(
                    OfficialRequestReceipt.Kind.ANSWERED_UNUSABLE), null))
    }
}
