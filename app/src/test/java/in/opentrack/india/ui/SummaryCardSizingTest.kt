package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryCardSizingTest {
    @Test fun `website hint and timeout note expand an already fitted report`() {
        val chrome = 150 // title, delay, next stop, source and card padding
        val initial = SummaryCardSizing.fit(chrome, 200, 640) // chip and headline
        val withHint = SummaryCardSizing.fit(chrome, 200 + 54, 640)
        val withBoth = SummaryCardSizing.fit(chrome, 200 + 54 + 78, 640)
        assertEquals(350, initial.cardPx)
        assertEquals(404, withHint.cardPx)
        assertEquals(482, withBoth.cardPx)
        assertEquals(withBoth.cardPx, chrome + withBoth.detailsPx)
        assertFalse(withBoth.overflows)
        assertEquals("a dismissed note must not leave a blank card", initial,
            SummaryCardSizing.fit(chrome, 200, 640))
    }

    @Test fun `small screens keep the source controls below a scrollable report`() {
        val fit = SummaryCardSizing.fit(158, 490, 430)
        assertEquals(430, fit.cardPx)
        assertEquals(272, fit.detailsPx)
        assertTrue(fit.overflows)
        assertEquals(SummaryCardSizing.Fit(290, 0, true),
            SummaryCardSizing.fit(290, 70, 270))
    }

    @Test fun `a large footer cannot clip the schedule badge on a dense offline phone`() {
        // Published-APK acceptance observed a 577px card with 542px of footer
        // and a 75px badge. The old algorithm left 35px of inner viewport.
        val cap = SummaryCardSizing.readableCap(577, 542, 75 + 95)
        val fit = SummaryCardSizing.fit(542, 656, cap)
        assertEquals(712, cap)
        assertTrue(fit.detailsPx >= 170)
        assertEquals(cap, fit.cardPx)
        assertTrue(fit.overflows)
    }

    @Test fun `same source and same number of lines never resize on refresh`() {
        val before = SummaryCardSizing.fit(176, 290, 680)
        val after = SummaryCardSizing.fit(176, 290, 680)
        assertEquals(before, after)
    }
}
