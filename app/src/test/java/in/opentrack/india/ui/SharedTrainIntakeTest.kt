package `in`.opentrack.india.ui

import android.content.Intent
import android.view.ViewGroup
import android.widget.TextView
import org.robolectric.Robolectric
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedTrainIntakeTest {
    @Test fun userShareIsPreviewedWithoutInventingStatusFields() {
        val preview = SharedTrainIntake.preview(Intent.ACTION_SEND, "text/plain",
            "  Train 16227 · status as shared by a person  ")!!
        assertEquals("Train 16227 · status as shared by a person", preview.text)
        assertFalse(preview.linkOnly)
        // A share is not a selected, run-identified primary feed or the .org.in site.
        assertFalse(SourceEvidencePolicy.primaryActuals("user-shared text", true, true))
        assertFalse(TrackingSources.options.contains("WIMT phone app"))
        assertFalse(PublicPageChecks.names.contains("WIMT phone app"))
    }

    @Test fun linkOnlyDoesNotBecomeAnEventOrAnAutomaticFetch() {
        val preview = SharedTrainIntake.preview(Intent.ACTION_SEND, "text/plain",
            "https://whereismytrain.in/share/abc?train=16227")!!
        assertTrue(preview.linkOnly)
        assertEquals("https://whereismytrain.in/share/abc?train=16227", preview.text)
    }

    @Test fun androidShareSheetDeliveryDisplaysOnlyLocalPreview() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Train 16227 · user-shared snapshot")
        val activity = Robolectric.buildActivity(SharedTrainActivity::class.java, intent).setup().get()
        fun texts(group: ViewGroup): List<String> = buildList {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is TextView) add(child.text.toString())
                if (child is ViewGroup) addAll(texts(child))
            }
        }
        val displayed = texts(activity.findViewById(android.R.id.content))
        assertTrue(displayed.contains("Train 16227 · user-shared snapshot"))
        assertTrue(displayed.any { it.contains("not a live feed") })
    }

    @Test fun refusesImplicitOrOversizedOrBinaryAndEmptyInput() {
        assertNull(SharedTrainIntake.preview(Intent.ACTION_VIEW, "text/plain", "train"))
        assertNull(SharedTrainIntake.preview(Intent.ACTION_SEND, "image/png", "train"))
        assertNull(SharedTrainIntake.preview(Intent.ACTION_SEND, "text/plain", null))
        assertNull(SharedTrainIntake.preview(Intent.ACTION_SEND, "text/plain", "   "))
        assertNull(SharedTrainIntake.preview(Intent.ACTION_SEND, "text/plain", "x".repeat(8_193)))
        assertNull(SharedTrainIntake.preview(Intent.ACTION_SEND, "text/plain", "x\u0000y"))
        assertEquals(8_192, SharedTrainIntake.preview(Intent.ACTION_SEND, "text/plain",
            "x".repeat(8_192))?.text?.length)
    }
}
