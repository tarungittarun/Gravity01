package `in`.opentrack.india.ui

import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.v2.mirror.OrgWebsiteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DepartureCueTest {
    private val day = LocalDate.of(2026, 10, 19).toEpochDay()
    private val departure = 20 * 60 + 21
    private val checked = RailTime.railMinuteToEpochMillis(day, 20 * 60 + 26) + 35_000L
    private val report = OrgWebsiteStatus.Report(
        rows = mapOf("RRB" to LiveStopFields(actualArrivalMin = 20 * 60 + 15)),
        anchorCode = "RRB", departed = false, eventMinute = 20 * 60 + 15,
        pageUpdatedAt = checked, provisionalDeparture = departure)

    @Test fun websiteSuggestionIsDistinctFromAnActualAndNamesFirstMappedPoint() {
        assertEquals(departure, DepartureCue.websiteSuggestion(report, day, checked + 1_000L))
        assertEquals("Between Birur Jn and Sivapur",
            DepartureCue.between("Birur Jn", "Sivapur", "Tarikere Jn"))
        assertEquals("Between Birur Jn and Tarikere Jn",
            DepartureCue.between("Birur Jn", null, "Tarikere Jn"))
    }

    @Test fun pendingDepartureIsVisibleAsUnconfirmedBeforeAnyMovementCue() {
        val atDeparture = RailTime.railMinuteToEpochMillis(day, departure) + 20_000L
        val fresh = report.copy(pageUpdatedAt = atDeparture)
        assertEquals(departure, DepartureCue.websitePending(fresh, atDeparture + 1_000L))
        assertNull("a fresh first-clock departure is NOT an outbound event",
            DepartureCue.websiteSuggestion(fresh, day, atDeparture + 1_000L))
        assertNull("elapsed phone time alone cannot settle the site's first clock",
            DepartureCue.websiteSuggestion(fresh, day, atDeparture + 150_000L))
        assertEquals(departure, DepartureCue.websiteSuggestion(
            fresh.copy(pageUpdatedAt = atDeparture + 150_000L), day, atDeparture + 151_000L))
        assertNull("do not keep a pending source suggestion after its page expires",
            DepartureCue.websitePending(fresh, atDeparture + 300_001L))
        assertNull(DepartureCue.websitePending(fresh.copy(provisionalDeparture = null), atDeparture))
        assertNull(DepartureCue.websitePending(fresh.copy(intermediate =
            OrgWebsiteStatus.Intermediate("SPV", "Sivapur", "RRB", "TKE", 215, 1216)), atDeparture))
    }

    @Test fun noMovementFromArrivalFutureReversalPageClockOrStaleSuggestion() {
        assertNull(DepartureCue.websiteSuggestion(report.copy(provisionalDeparture = null), day, checked))
        assertNull(DepartureCue.websiteSuggestion(report.copy(provisionalDeparture = 20 * 60 + 14), day, checked))
        assertNull(DepartureCue.websiteSuggestion(report.copy(provisionalDeparture = 20 * 60 + 25), day, checked))
        assertNull(DepartureCue.websiteSuggestion(report.copy(pageUpdatedAt = checked - 301_000), day, checked))
        assertNull(DepartureCue.websiteSuggestion(report, day, checked + 601_000))
        assertNull(DepartureCue.websiteSuggestion(report.copy(intermediate =
            OrgWebsiteStatus.Intermediate("SPV", "Sivapur", "RRB", "TKE", 215, 1216)), day, checked))
        assertNull(DepartureCue.websiteSuggestion(report.copy(rows = emptyMap()), day, checked))
    }
}
