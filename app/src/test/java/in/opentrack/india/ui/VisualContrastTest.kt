package `in`.opentrack.india.ui

import android.view.View
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import `in`.opentrack.india.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual light/night resources AND their rendered boxes: strong colours alone do not prove wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualContrastTest {
    @Test
    @Config(qualifiers = "w360dp-h760dp-notnight-xhdpi")
    fun lightPaletteKeepsRefreshAndBothSummariesVisible() = assertPalette("light")

    @Test
    @Config(qualifiers = "w360dp-h760dp-night-xhdpi")
    fun darkPaletteKeepsRefreshAndBothSummariesVisible() = assertPalette("dark")

    private fun assertPalette(mode: String) {
        val track = Render.render(R.layout.activity_train)
        val c = track.context
        val refresh = c.getColor(R.color.refresh_surface)
        val glyph = c.getColor(R.color.refresh_ink)
        val summary = c.getColor(R.color.summary_surface)
        val border = c.getColor(R.color.summary_border)
        val ink = c.getColor(R.color.summary_ink)
        assertTrue("$mode: Refresh must stand out from the screen",
            ColorUtils.calculateContrast(refresh, c.getColor(R.color.bg)) >= 3.0)
        assertTrue("$mode: Refresh icon needs readable contrast",
            ColorUtils.calculateContrast(glyph, refresh) >= 4.5)
        assertTrue("$mode: Summary must have a discernible border",
            ColorUtils.calculateContrast(border, summary) >= 2.4)
        assertTrue("$mode: Summary text needs readable contrast",
            ColorUtils.calculateContrast(ink, summary) >= 7.0)
        assertTrue("$mode: coloured floating Refresh was not painted",
            track.countWith(refresh, track.bounds(R.id.refresh)) > 20)
        assertTrue("$mode: coloured tracking summary was not painted",
            track.countWith(summary, track.bounds(R.id.trackingSummaryCard)) > 20)
        assertEquals("$mode: refresh glyph does not use the intended ink",
            glyph, track.root.findViewById<android.widget.ImageView>(R.id.refreshGlyph).imageTintList?.defaultColor)
        for (id in listOf(R.id.summaryBoxLabel, R.id.headline, R.id.age,
            R.id.delayLabel, R.id.next, R.id.delay)) {
            val text = track.root.findViewById<TextView>(id)
            assertTrue("$mode: $id is too small to read in the summary",
                text.textSize >= 13f * c.resources.displayMetrics.scaledDensity)
            assertTrue("$mode: $id lacks legible contrast on the blue summary",
                ColorUtils.calculateContrast(text.currentTextColor, summary) >= 7.0)
        }
        Render.save("r142-$mode-tracking", track.bitmap)

        val home = Render.render(R.layout.activity_main) { root ->
            root.findViewById<View>(R.id.searchCardScroll).visibility = View.GONE
            root.findViewById<View>(R.id.searchCompact).visibility = View.VISIBLE
            root.findViewById<View>(R.id.statusLine).visibility = View.GONE
            root.findViewById<View>(R.id.resultsSummary).visibility = View.VISIBLE
            Render.text(root, R.id.resultsSummary, "3 trains · on-device timetable")
        }
        assertTrue("$mode: coloured result summary was not painted",
            home.countWith(summary, home.bounds(R.id.resultsSummary)) > 20)
        assertEquals("$mode: result summary text must match its background",
            ink, home.root.findViewById<TextView>(R.id.resultsSummary).currentTextColor)
        Render.save("r131-$mode-results", home.bitmap)
    }
}
