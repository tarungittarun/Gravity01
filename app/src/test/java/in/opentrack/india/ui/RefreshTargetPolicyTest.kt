package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshTargetPolicyTest {
    private val site = PublicPageChecks.ORG
    private val official = TrackingSources.OFFICIAL

    @Test fun firstSurveyAsksSiteOnlyWhenItIsSelectedAndNotYetChecked() {
        assertEquals(false, RefreshTargetPolicy.surveyWebsite(official, false))
        assertEquals(true, RefreshTargetPolicy.surveyWebsite(site, false))
        assertEquals(false, RefreshTargetPolicy.surveyWebsite(site, true))
    }

    @Test fun firstSurveyRefusalDisplaysAlreadyAvailableOfficialTimesWithoutChangingManualTarget() {
        assertEquals(official, RefreshTargetPolicy.afterWebsiteSurvey(site, false, true))
        assertEquals(site, RefreshTargetPolicy.target(official, site, auto = false))
        assertEquals(official, RefreshTargetPolicy.target(official, site, auto = true))
        assertEquals(site, RefreshTargetPolicy.afterWebsiteSurvey(site, false, false))
        assertEquals(site, RefreshTargetPolicy.afterWebsiteSurvey(site, true, true))
        assertEquals(official, RefreshTargetPolicy.afterWebsiteSurvey(official, false, true))
    }

    @Test fun manualRefreshRetriesPreferredWebsiteAfterOfficialFallback() {
        assertEquals(site, RefreshTargetPolicy.target(official, site, auto = false))
        assertEquals(site, RefreshTargetPolicy.target(site, site, auto = false))
    }

    @Test fun automaticRefreshDoesNotHammerARefusingWebsiteAfterFallback() {
        assertEquals(official, RefreshTargetPolicy.target(official, site, auto = true))
        assertEquals(site, RefreshTargetPolicy.target(site, site, auto = true))
    }

    @Test fun explicitOfficialChoiceStaysOfficialAndDoesNotRetryWebsite() {
        assertEquals(official, RefreshTargetPolicy.target(official, official, auto = false))
        assertEquals(official, RefreshTargetPolicy.target(null, null, auto = false))
    }
}
