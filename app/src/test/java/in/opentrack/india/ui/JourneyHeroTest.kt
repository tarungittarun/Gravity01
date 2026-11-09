package `in`.opentrack.india.ui

import `in`.opentrack.india.domain.TrackModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyHeroTest {
    private val reported = TrackModel.Stop(
        seq = 1, code = "TK", name = "TUMKUR", schedArr = 13, schedDep = 15,
        showArr = 46, showDep = 48, delay = 33,
        source = TrackModel.MinuteSource.REPORTED, platform = "3",
        state = TrackModel.State.PASSED, isAnchor = true,
        kmFromOrigin = 209, kmFromBoard = 209, kmRemaining = 301,
        isBoarding = false, isAlighting = false, minutesFromNow = -7,
    )
    private val next = reported.copy(
        seq = 2, code = "TIPT", name = "TIPTUR", schedArr = 65, schedDep = 67,
        showArr = null, showDep = null, source = TrackModel.MinuteSource.SCHEDULE,
        state = TrackModel.State.NEXT, isAnchor = false,
    )

    @Test fun lastReportedDepartureIsShortButNextTimeRemainsScheduleOnly() {
        val hero = JourneyHero.official(listOf(reported, next), "TK")!!
        assertEquals("Departed Tumkur", hero.title)
        assertTrue(hero.next.startsWith("Tiptur · 01:05"))
        assertTrue(hero.next.endsWith("\nTimetable"))
        assertEquals(hero, JourneyHero.official(listOf(reported, next), null))
        assertNull(JourneyHero.official(listOf(reported.copy(source = TrackModel.MinuteSource.SCHEDULE),
            next), "TK"))
    }

    @Test fun arrivalWithoutDepartureNeverBecomesLeft() {
        val hero = JourneyHero.official(listOf(reported.copy(showDep = null), next), "TK")!!
        assertEquals("Arrived at Tumkur", hero.title)
    }
}
