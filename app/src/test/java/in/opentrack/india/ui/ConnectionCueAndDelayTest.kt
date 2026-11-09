package `in`.opentrack.india.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.TrackModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionCueAndDelayTest {
    @Test fun onlyEarlyIsGreenAndPositiveDelayIsNeverGreen() {
        val tones = TrackModel.Tone.entries
        assertEquals(R.color.early, DelayPresentation.color(TrackModel.Tone.LIVE_EARLY))
        for (tone in tones.filter { it != TrackModel.Tone.LIVE_EARLY }) {
            assertFalse("$tone must not be green", DelayPresentation.color(tone) == R.color.early)
        }
        assertEquals(R.color.delay_neutral, DelayPresentation.color(TrackModel.Tone.LIVE_OK))
        assertEquals(R.color.late, DelayPresentation.color(TrackModel.Tone.LIVE_OK, 1))
        assertEquals(R.color.late, DelayPresentation.color(TrackModel.Tone.LIVE_LATE, 30))
        assertEquals(R.color.delay_neutral, DelayPresentation.color(TrackModel.Tone.LIVE_OK, 0))
        assertEquals(R.color.early, DelayPresentation.color(TrackModel.Tone.LIVE_OK, -1))
        assertEquals("1 min early", DelayPresentation.mirrorLabel(-1, "unknown"))
        assertEquals("1 min late", DelayPresentation.mirrorLabel(1, "unknown"))
        assertEquals("on time", DelayPresentation.mirrorLabel(0, "unknown"))
    }

    @Test @Config(qualifiers = "w360dp-h760dp-notnight-xhdpi")
    fun lightPaletteMakesConnectionAndDelayLegible() = palette()

    @Test @Config(qualifiers = "w360dp-h760dp-night-xhdpi")
    fun nightPaletteMakesConnectionAndDelayLegible() = palette()

    private fun palette() {
        val ctx = RuntimeEnvironment.getApplication()
        val bg = ctx.getColor(R.color.summary_surface)
        for (name in listOf(R.color.early, R.color.delay_neutral, R.color.late)) {
            assertTrue("delay color $name must read on the summary in this palette",
                ColorUtils.calculateContrast(ctx.getColor(name), bg) >= 4.5)
        }
        assertTrue("failed-connection explanation is unreadable",
            ColorUtils.calculateContrast(ctx.getColor(R.color.connection_ink),
                ctx.getColor(R.color.connection_surface)) >= 4.5)
        val card = LinearLayout(ctx)
        val label = TextView(ctx)
        val evidence = LinearLayout(ctx)
        val cue = ConnectionIssueCue(card, label)
        assertEquals(333L, ConnectionIssueCue.PULSE_MILLIS)
        assertEquals(6, ConnectionIssueCue.PULSES)
        cue.show("Source timed out", "NTES · official")
        assertEquals(View.VISIBLE, label.visibility)
        assertEquals("the last report must remain readable during a failure", 1f,
            evidence.alpha, .001f)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
            androidx.core.view.ViewCompat.getImportantForAccessibility(evidence))
        assertTrue(label.text.contains("Source timed out · NTES"))
        assertTrue(label.text.contains("Previous report unchanged"))
        cue.show("Website refused request (HTTP 403)", "whereismytrain.org.in (website)",
            "NTES · official")
        assertTrue(label.text.contains("Showing NTES · official instead"))
        assertFalse("fallback may have updated NTES, so do not say its report is unchanged",
            label.text.contains("Previous report unchanged"))
        cue.pause()
        assertEquals(1f, card.alpha, .001f)
        assertEquals(0f, card.translationX, .001f)
        cue.clear()
        assertEquals(View.GONE, label.visibility) // warning takes no space after clearing
        assertEquals(1f, evidence.alpha, .001f)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
            androidx.core.view.ViewCompat.getImportantForAccessibility(evidence))
        assertEquals(null, cue.reason)
    }
}
