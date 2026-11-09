package `in`.opentrack.india.ui

import android.view.View
import `in`.opentrack.india.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Both resource-qualified palettes paint the real home, settings and tracking layouts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceRenderTest {
    private fun luminance(c: Int): Double {
        fun channel(v: Int): Double {
            val x = v / 255.0
            return if (x <= .04045) x / 12.92 else Math.pow((x + .055) / 1.055, 2.4)
        }
        return .2126 * channel(c shr 16 and 255) + .7152 * channel(c shr 8 and 255) +
            .0722 * channel(c and 255)
    }

    private fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }

    private fun check(dark: Boolean) {
        for ((layout, name) in listOf(R.layout.activity_main to "home",
            R.layout.activity_settings to "settings", R.layout.activity_train to "tracking")) {
            val shot = Render.render(layout)
            val res = shot.context
            val bg = res.getColor(R.color.bg)
            val surface = res.getColor(R.color.surface)
            val title = res.getColor(R.color.text1)
            val secondary = res.getColor(R.color.text2)
            val caption = res.getColor(R.color.text3)
            val accent = res.getColor(R.color.accent)
            assertEquals("wrong $name palette", dark, luminance(bg) < .2)
            for (ink in listOf(title, secondary, caption)) {
                assertTrue("$name text/background contrast=${contrast(ink, bg)}",
                    contrast(ink, bg) >= 4.5)
                assertTrue("$name text/card contrast=${contrast(ink, surface)}",
                    contrast(ink, surface) >= 4.5)
            }
            assertTrue("$name CTA ink unreadable", contrast(accent,
                res.getColor(R.color.ink_on_accent)) >= 4.5)
            for (id in if (layout == R.layout.activity_main)
                listOf(R.id.homeBrand, R.id.modeRow, R.id.fromBox, R.id.toBox,
                    R.id.swap, R.id.dayNow, R.id.find, R.id.editSearch)
                else if (layout == R.layout.activity_settings)
                    listOf(R.id.defaultTrackingSource, R.id.appearance, R.id.overrideUrl)
                else listOf(R.id.headline, R.id.provenance, R.id.stops)) {
                assertNotNull("$name missing $id", shot.root.findViewById<View>(id))
            }
            Render.save("r125-${if (dark) "dark" else "light"}-$name", shot.bitmap)
        }
    }

    @Test @Config(sdk = [34], qualifiers = "w360dp-h760dp-notnight-xhdpi")
    fun lightScreensReadable() = check(false)

    @Test @Config(sdk = [34], qualifiers = "w360dp-h760dp-night-xhdpi")
    fun darkScreensReadable() = check(true)
}
