package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPlacementTest {
    @Test fun `default centre sits seven eighths across and one fifth above bottom`() {
        val point = RefreshPlacement.at(400, 800, 48, 48, 12)
        assertEquals(350f, point.x + 24f, 0.1f)
        assertEquals(640f, point.y + 24f, 0.1f)
    }

    @Test fun `drag is confined to lower half and kept off all edges`() {
        val above = RefreshPlacement.drag(400, 800, 48, 48, 12, -900f, -900f)
        assertEquals(12f, above.x, 0.1f)
        assertEquals(400f, above.y, 0.1f)
        val corner = RefreshPlacement.drag(400, 800, 48, 48, 12, 900f, 900f)
        assertEquals(340f, corner.x, 0.1f)
        assertEquals(740f, corner.y, 0.1f)
        val moved = RefreshPlacement.drag(400, 800, 48, 48, 12, 260f, 430f)
        assertEquals(260f, moved.x, 0.1f)
        assertEquals(430f, moved.y, 0.1f)
        assertTrue(moved.y >= 400f)
    }

    @Test fun `drop moves off a visible departure clock but not off a free location`() {
        val occupied = listOf(RefreshPlacement.Box(320f, 510f, 390f, 555f))
        val placed = RefreshPlacement.settle(400, 800, 48, 48, 12, 330f, 520f, occupied)
        assertTrue("drop obscured a clock: $placed", placed.x + 48 <= 308f ||
            placed.x >= 402f || placed.y + 48 <= 498f || placed.y >= 567f)
        assertTrue(placed.y >= 400f)
        assertEquals(RefreshPlacement.drag(400, 800, 48, 48, 12, 180f, 600f),
            RefreshPlacement.settle(400, 800, 48, 48, 12, 180f, 600f, occupied))
    }

    @Test fun `saved relative position remains in allowed half after rotation`() {
        val rotated = RefreshPlacement.at(800, 400, 48, 48, 12, 0.75f, 0.68f)
        assertEquals(576f, rotated.x, 0.1f)
        assertEquals(248f, rotated.y, 0.1f)
    }
}
