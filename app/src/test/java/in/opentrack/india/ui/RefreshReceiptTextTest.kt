package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshReceiptTextTest {
    private val unchanged = RefreshEvidence.Change.UNCHANGED
    @Test fun officialAnsweredButSameOldYesvantpurStopIsNotAConnectionFailureOrMovement() {
        val receipt = RefreshReceiptText.format("NTES (official)", "NTES · official",
            "NTES · official", "00:35:01", "last reported stop 53 min ago",
            "live" to "13 actuals", OfficialRequestReceipt.from(
                listOf("PROVIDER_OVERRIDE=miss", "PROVIDER_NTES=hit"), emptyList()),
            null, false, unchanged, false)
        assertTrue(receipt.contains("NTES answered at 00:35:01"))
        assertTrue(receipt.contains("same last reported stop"))
        assertTrue(receipt.contains("53 min ago"))
        assertFalse(receipt.contains("request failed"))
    }

    @Test fun failedOfficialRequestKeepsOldDataAndNamesWorkingFallback() {
        val outcome = OfficialRequestReceipt.from(listOf("PROVIDER_NTES=miss", "CACHED=hit"),
            listOf("LIVE · running status: The live feed did not answer within the time limit."))
        assertEquals(OfficialRequestReceipt.Kind.REQUEST_FAILED, outcome.kind)
        val receipt = RefreshReceiptText.format("NTES (official)", "NTES · official", "website",
            "00:36:00", "site-reported stop 54 min ago", "last-known" to "saved",
            outcome, null, false, unchanged, true)
        assertTrue(receipt.contains("NTES request failed"))
        assertTrue(receipt.contains("showing website"))
        assertTrue(receipt.contains("54 min ago"))
    }

    @Test fun websiteAnsweredWithSameStopIsDifferentFromFailureAndCache() {
        val website = PublicPageChecks.ORG
        val answer = RefreshReceiptText.format(website, "website", "website",
            "00:35:02", "no new timed position from this source", "candidate" to "past card",
            null, true, false, unchanged, false)
        assertTrue(answer.contains("Website answered"))
        assertTrue(answer.contains("no validated site stop report"))
        assertFalse(answer.contains("site-reported stop"))
        val failure = RefreshReceiptText.format(website, "website", "NTES · official",
            "00:36:00", "last reported stop 54 min ago",
            "last-known" to "Request failed: TIMEOUT; previous website report retained",
            null, false, false, unchanged, true)
        assertTrue(failure.contains("website request failed"))
        assertTrue(failure.contains("showing NTES"))
        assertFalse(failure.contains("Website answered"))
    }

    @Test fun pausedWebsiteDoesNotClaimAnHttpAttempt() {
        val text = RefreshReceiptText.format(PublicPageChecks.ORG, "website", "NTES · official",
            "17:40:00", "last reported stop 12 min ago",
            "unverified" to "Website request paused after HTTP 403; no new request. Retry in 15 min.",
            null, false, false, unchanged, true)
        assertTrue(text.contains("website not asked"))
        assertTrue(text.contains("Retry in 15 min"))
        assertFalse(text.contains("website request failed"))
    }

    @Test fun websiteHttpSuccessWithPastCardShowsSourceEventNotPageClockMovement() {
        val text = RefreshReceiptText.format(PublicPageChecks.ORG, "website", "website",
            "06:15:58", "no new timed position from this source",
            "comparison" to "3 past-marked cards, origin run inferred",
            null, true, false, RefreshEvidence.Change.NEW_STOP, false)
        assertTrue(text.contains("Website answered"))
        assertTrue(text.contains("new site-reported stop"))
        assertTrue(text.contains("last website stop") || text.contains("no new timed position"))
        assertFalse(text.contains("showing NTES"))
        val same = RefreshReceiptText.format(PublicPageChecks.ORG, "website", "website",
            "11:28:54", "last website stop 2 min ago", "comparison" to "15 past cards",
            null, true, false, RefreshEvidence.Change.UNCHANGED, false)
        assertTrue(same.contains("same last qualifying site stop, no newer event"))
        assertFalse(same.contains("new site-reported stop"))
    }

    @Test fun changedWebsiteCardClocksDoNotBecomeAnInventedNewStop() {
        val detail = RefreshReceiptText.format(PublicPageChecks.ORG, "website", "website",
            "07:01:54", "last website arrival 48 min ago", "comparison" to "site answered",
            null, true, false, unchanged, false, websiteClocksChanged = true)
        assertTrue(detail.contains("site clocks changed; no newer mapped arrival"))
        assertFalse(detail.contains("new site-reported stop"))
        assertTrue(RefreshReceiptUi.short("website", "07:01:54", unchanged, detail, false)
            .endsWith("site times revised"))
    }

    @Test fun websiteNewerGreenCardWithoutActualTimeIsNamedNotCalledNewMovement() {
        val text = RefreshReceiptText.format(PublicPageChecks.ORG, "website", "website",
            "05:26:36", "last website stop 43 min ago",
            "comparison" to "23 website rows · Unqualified later card: Website marks SMET past; " +
                "clocks do not distinguish report from timetable. · from site",
            null, true, false, unchanged, false)
        assertTrue(text.contains("same last qualifying site stop"))
        assertTrue(text.contains("Website marks SMET past"))
        assertTrue(text.contains("clocks do not distinguish report from timetable"))
        assertFalse(text.contains("new site-reported stop"))
    }

    @Test fun answeredWebsiteWithoutQualifyingStopNeverClaimsSameOldSiteEvent() {
        val text = RefreshReceiptText.format(PublicPageChecks.ORG, "website", "NTES · official",
            "00:38:00", "last reported stop 56 min ago",
            "unverified" to "page answered, no unambiguous same-run past card",
            null, true, false, unchanged, true)
        assertTrue(text.contains("Website answered at 00:38:00"))
        assertTrue(text.contains("no validated site stop report"))
        assertFalse(text.contains("same last site-reported stop"))
    }

    @Test fun selectedNonOfficialConnectionFailureKeepsEvidenceWithoutCallingItUnchanged() {
        val receipt = RefreshReceiptText.format("RailYatri", "RailYatri", "NTES · official",
            "00:37:00", "last reported stop 55 min ago",
            "unverified" to "Request failed: TIMEOUT · connection refused; no position.",
            null, false, false, unchanged, true)
        assertTrue(receipt.contains("RailYatri request failed"))
        assertTrue(receipt.contains("showing NTES"))
        assertFalse(receipt.contains("same last reported stop"))
    }

    @Test fun answeredWithoutActualsIsNotMislabelledNetworkFailure() {
        val outcome = OfficialRequestReceipt.from(listOf("PROVIDER_NTES=miss"),
            listOf("LIVE · running status: NTES answered but supplied no observed minute."))
        assertEquals(OfficialRequestReceipt.Kind.ANSWERED_UNUSABLE, outcome.kind)
    }
}
