package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WimtCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIndicatorTest {
    @Test fun travellerChoicesPresentWorkingSources() {
        assertEquals(listOf(TrackingSources.OFFICIAL, TrackingSources.WIMT), TrackingSources.options)
        assertEquals("Where Is My Train · live",
            TrackingSources.label(TrackingSources.WIMT))
        assertEquals(TrackingSources.WIMT, TrackingSources.valid(TrackingSources.WIMT))
    }

    @Test fun aQuickHttpPageDoesNotBecomeGreen() {
        assertEquals(SourceIndicator.Tone.UNAVAILABLE,
            SourceIndicator.of(PublicPageChecks.IXIGO, "unverified",
                "HTTP 200; 1 undated excerpt; no event-level observation").tone)
        assertEquals(SourceIndicator.Tone.UNAVAILABLE,
            SourceIndicator.of("confirmtkt", "none", "11 rows; 0 confirmed actuals").tone)
        assertEquals(SourceIndicator.Tone.PENDING,
            SourceIndicator.of(PublicPageChecks.ORG, "none", "checking on first load").tone)
    }

    @Test fun secondsAndFailureReasonsAreSeparateFromDataFreshness() {
        val failure = SourceIndicator.of("runningstatus.in", "none", "HTTP 403 · checked in 3810 ms")
        assertEquals(SourceIndicator.Tone.UNAVAILABLE, failure.tone)
        assertEquals("site blocks request", failure.label)
        assertEquals("3.8 s", SourceIndicator.seconds("HTTP 403 · checked in 3810 ms"))
        assertEquals(null, SourceIndicator.seconds("not checked yet"))
        assertEquals(0, SourceIndicator.priority(SourceIndicator.Tone.READY))
        assertEquals(4, SourceIndicator.priority(SourceIndicator.Tone.UNAVAILABLE))
        assertEquals("run day unclear", SourceIndicator.of(PublicPageChecks.ORG,
            "unverified", "selected origin run is not unambiguously active").label)
        assertEquals("site update too old", SourceIndicator.of(PublicPageChecks.ORG,
            "unverified", "website update clock missing or over five minutes old").label)
    }

    @Test fun siteClaimsAreAmberAndNeverLabelledAsVerifiedActuals() {
        val site = SourceIndicator.of(PublicPageChecks.ORG, "comparison", "13 past-card rows")
        assertEquals(SourceIndicator.Tone.CANDIDATE, site.tone)
        assertEquals(true, site.label.contains("site past-card times"))
        assertEquals(false, site.label.contains("verified"))
        assertEquals(SourceIndicator.Tone.CANDIDATE,
            SourceIndicator.of(WimtCandidate.SOURCE, "comparison", "dated km point").tone)
        assertEquals(SourceIndicator.Tone.CANDIDATE,
            SourceIndicator.of(PublicPageChecks.RAIL_YATRI, "comparison", "no passage minute").tone)
        assertEquals(SourceIndicator.Tone.READY,
            SourceIndicator.of("NTES (official)", "live", "11 actual stops").tone)
        assertEquals(SourceIndicator.Tone.READY,
            SourceIndicator.of(TrackingSources.WIMT, "live", "10 actual stops").tone)
    }
}
