package `in`.opentrack.india.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** The 1080×2160 / 440 dpi / 1.30× profile which intermittently showed only
 * two main halts while the source card used a fixed 53% of the screen. */
class RouteViewportBudgetTest {
    @Test fun largeTextLeavesThreeHaltRowsAndTheRouteHeading() {
        val density = 440f / 160f
        val available = (740f * density).toInt() // subtract Android status/nav and the app header
        val cap = RouteViewportBudget.sourceCapPx(available, 2160, density, 1.30f)
        val routeDp = (available - cap) / density
        assertTrue("route only has $routeDp dp with 1.30× font", routeDp >= 460f)
        assertTrue("source explanation cannot fit: ${cap / density} dp", cap / density >= 210f)
    }

    @Test fun shortLargeFontScreenStillShowsHeadlineAndThreeCompactHalts() {
        val density = 440f / 160f
        val cap = RouteViewportBudget.sourceCapPx((660f * density).toInt(), 2160,
            density, 1.15f)
        assertTrue("headline and source badge are clipped: ${cap / density}dp",
            cap / density >= 210f)
        assertTrue("route disappears under summary: ${cap / density}dp",
            660f - cap / density >= 400f)
    }

    @Test fun normalHeightStillLetsSummaryWrapWithoutTakingTheWholeScreen() {
        val density = 2f
        val available = 700 * 2
        val cap = RouteViewportBudget.sourceCapPx(available, 760 * 2, density, 1f)
        assertTrue("route below three compact rows", (available - cap) / density >= 350f)
        assertTrue("card exceeds the 53% hard cap", cap <= (760 * 2 * .53f).toInt())
    }

    @Test fun pinnedSourceRowDoesNotConsumeTheReservedRouteHaltBudget() {
        val density = 2f
        val available = 700 * 2
        val cap = RouteViewportBudget.sourceCapPx(available, 760 * 2, density, 1f)
        val routeAfterSource = (available - cap) / density - 56f
        assertTrue("source row ate the three-halt reserve: $routeAfterSource dp",
            routeAfterSource >= 350f)
    }

    @Test fun tinyScreenCannotAllocateMoreThanItsAvailableHeight() {
        val cap = RouteViewportBudget.sourceCapPx(400, 400, 2f, 1.4f)
        assertTrue(cap in 0..400)
    }
}
