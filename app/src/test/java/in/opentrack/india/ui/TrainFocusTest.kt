package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainFocusTest {
    @Test fun expandedLegFocusesIconRatherThanRowStart() {
        val viewport = 700
        val iconInsideExpandedRow = 620f
        val offset = TrainFocus.rowOffset(viewport, iconInsideExpandedRow)
        assertEquals(-396, offset)
        assertEquals("train must be visible around a third of the way down",
            224f, iconInsideExpandedRow + offset, .001f)
        assertTrue(TrainFocus.rowOffset(viewport, 20f) > 0)
    }
}
