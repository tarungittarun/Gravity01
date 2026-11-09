package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** New display formatting must not change train-event identity or turn a page check into movement. */
class R140PresentationTest {
    @Test fun dayTwoAndThreeAppearOnlyAtRunDayTransitions() {
        assertEquals("01:38", RouteClockUi.clock(1538))
        assertEquals("day2", RouteClockUi.dayMarker(1440, 1439))
        assertEquals(null, RouteClockUi.dayMarker(1500, 1450))
        assertEquals("day3", RouteClockUi.dayMarker(2880, 2879))
        assertEquals(null, RouteClockUi.dayMarker(60, null))
        assertEquals("day2", RouteClockUi.departureDay(1500, 1430))
        assertEquals("day2", RouteClockUi.departureDay(1570, 1560)) // every Day 2 station
        assertEquals("day3", RouteClockUi.departureDay(3000, 2870))
        assertEquals("day3", RouteClockUi.departureDay(null, 3005)) // final arrival
        assertEquals(null, RouteClockUi.departureDay(530, null))
        assertEquals("—", RouteClockUi.clock(null))
    }

    @Test fun oneLineReceiptDoesNotRepeatTheWebsiteStationOrCallItPosition() {
        val short = RefreshReceiptUi.short("website", "01:43:27",
            RefreshEvidence.Change.UNCHANGED,
            "Website answered at 01:43:27 · same last site stop, no newer event · position unknown",
            false)
        assertEquals("Checked 01:43:27 · website · no newer qualified stop", short)
        assertFalse(short.contains("position"))
        assertEquals("Checked 01:43:27 · website · cached reply · no new event",
            RefreshReceiptUi.short("website", "01:43:27", RefreshEvidence.Change.NEW_STOP,
                "Cached HTTP body; previous report retained", true))
    }

    @Test fun transientOverlayNamesRequestDurationCheckResultAndEventAgeSeparately() {
        val lines = RefreshReceiptUi.overlay("website", "17:29:05", 1_260L,
            "last website stop 2m ago · current position unknown",
            RefreshEvidence.Change.UNCHANGED, "same last site stop, no newer event", false)
        assertEquals("Took 1.3 s", lines.took)
        assertEquals("Checked 17:29:05 · website · no newer qualified stop", lines.checked)
        assertEquals("Freshness · last website stop 2m ago · current position unknown", lines.freshness)
        assertEquals("Checked 17:29:05 · website · check failed · last report retained",
            RefreshReceiptUi.overlay("website", "17:29:05", 70L, "no newly reported stop time",
                RefreshEvidence.Change.UNCHANGED, "request failed", false).checked)
    }

    @Test fun routineWebsiteParagraphDisappearsButExceptionalWarningsRemain() {
        assertEquals(null, SummaryNoteUi.visible("Past website card clocks shown; " +
            "upcoming site estimates are not arrivals. Run day inferred."))
        assertEquals(null, SummaryNoteUi.visible("Website past-card times shown in the station table; " +
            "origin run inferred from the timetable. Last site report is not a current GPS position."))
        assertEquals("Website request failed. Previous report retained.",
            SummaryNoteUi.visible("Website request failed. Previous report retained."))
    }

    @Test fun firstIntermediateGapIsExactlyFifthShorterWithoutMovingOtherPoints() {
        val points = listOf(
            `in`.opentrack.india.domain.RouteBetween.LegPoint("A", "A", 12, .2f),
            `in`.opentrack.india.domain.RouteBetween.LegPoint("B", "B", 18, .6f),
        )
        val positions = LegGeometry.pointPositionsDp(points, 40)
        val bonus = 40 * 1.2f
        assertEquals(23f + (34f + bonus * .2f) * .8f, positions[0], .02f)
        assertEquals(34f + bonus * .4f, positions[1] - positions[0], .02f)
        assertTrue(positions[0] < positions[1])
    }
}
