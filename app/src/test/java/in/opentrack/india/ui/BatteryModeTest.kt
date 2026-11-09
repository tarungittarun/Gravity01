package `in`.opentrack.india.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import `in`.opentrack.india.data.Prefs
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.domain.RouteBetween
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatteryModeTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @After fun restore() { Prefs(ctx).batteryMode = false }

    @Test fun preferenceIsIndependentOfAppearanceAndOffByDefault() {
        ctx.getSharedPreferences("opentrack", 0).edit().remove("batteryMode").commit()
        val prefs = Prefs(ctx)
        assertFalse(prefs.batteryMode)
        val appearance = prefs.appearance
        prefs.batteryMode = true
        assertTrue(Prefs(ctx).batteryMode)
        assertEquals(appearance, Prefs(ctx).appearance)
        prefs.batteryMode = false
        assertEquals(appearance, Prefs(ctx).appearance)
    }

    @Test fun onlyEnabledModeBlacksSurfacesAndKeepsTextLegible() {
        val prefs = Prefs(ctx)
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.RED) }
        val text = TextView(ctx).apply {
            text = "Delay · next · NTES"
            setTextColor(Color.BLUE)
            layoutParams = FrameLayout.LayoutParams(190, 40)
        }
        root.addView(text)
        root.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 200, 100)
        prefs.batteryMode = false
        BatteryMode.paint(root)
        assertEquals(Color.RED, pixel(root, 190, 80))
        assertEquals(Color.BLUE, text.currentTextColor)
        prefs.batteryMode = true
        BatteryMode.paint(root)
        assertEquals(Color.BLACK, pixel(root, 190, 80))
        assertEquals(Color.WHITE, text.currentTextColor)
        assertEquals("Delay · next · NTES", text.text.toString())
    }

    @Test fun homeSettingsAndTrackingLayoutsAreBlackWithReadablePrimaryText() {
        Prefs(ctx).batteryMode = true
        for ((layout, labelId) in listOf(
            R.layout.activity_main to R.id.dataChip,
            R.layout.activity_settings to R.id.batteryMode,
            R.layout.activity_train to R.id.delay,
        )) {
            val shot = Render.render(layout, prepare = { BatteryMode.paint(it) })
            // The extreme corner can be anti-aliased by the permitted outline.
            assertEquals("$layout left an illuminated surface", Color.BLACK,
                shot.bitmap.getPixel(15, 15))
            assertEquals("$layout lost its text contrast", Color.WHITE,
                shot.root.findViewById<TextView>(labelId).currentTextColor)
        }
    }

    @Test fun batteryRowKeepsScheduleActualProvenanceAndWebsiteGapWithoutAnimatedArrow() {
        Prefs(ctx).batteryMode = true
        val adapter = StopAdapter().apply { batteryMode = true }
        val stop = TrackModel.Stop(1, "ASK", "Arsikere Jn", 1543, 1545, 1538, null, null,
            TrackModel.MinuteSource.REPORTED, "2", TrackModel.State.HERE,
            true, 304, 0, 200, false, false, false, null)
        adapter.haltMarker = TableMarker.Halt("ASK", "Last NTES arrival at ASK", false)
        adapter.legAgeSeconds = 9 * 60L
        adapter.submitList(listOf(stop))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals("A 01:43", holder.schedArr.text.toString())
        assertEquals("A 01:38", holder.liveArr.text.toString())
        // This halt has a scheduled departure, so missing actual stays blank, not END.
        assertEquals("D —", holder.liveDep.text.toString())
        assertTrue(holder.liveArr.contentDescription.contains("Reported arrival"))
        assertEquals(Color.WHITE, holder.liveArr.currentTextColor)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        assertTrue(holder.trainMarker.batteryOutline)
        assertEquals(View.GONE, holder.marker.visibility)
        assertTrue(holder.eventCaption.text.toString().contains("At Arsikere Jn · as of 01:38"))
        assertTrue(holder.eventCaption.text.toString().contains("9m ago"))
        assertEquals("9m ago", holder.markerAge.text.toString())
        assertTrue(holder.trainMarker.contentDescription.contains("present position unknown"))
        assertEquals(null, holder.liveArr.compoundDrawables[0])
    }

    @Test fun lastWebsiteIntermediateHasOneWhiteSketchAndAnAgedSiteCaption() {
        Prefs(ctx).batteryMode = true
        val adapter = StopAdapter().apply { batteryMode = true }
        val kadur = TrackModel.Stop(1, "DRU", "Kadur", 501, 503, 685, 690, null,
            TrackModel.MinuteSource.SITE_REPORTED, "1", TrackModel.State.PASSED,
            false, 167, 0, 39, false, false, false, null)
        val arsikere = kadur.copy(seq = 2, code = "ASK", name = "Arsikere Jn",
            schedArr = 530, schedDep = 535, showArr = null, showDep = null,
            source = TrackModel.MinuteSource.SCHEDULE, isAnchor = false,
            kmFromOrigin = 206)
        adapter.legAgeSeconds = 9 * 60L
        adapter.legWebsiteEventMinute = 707 // 11:47 is a site card, not a page-update clock
        adapter.setProjectedLeg(RouteBetween.LegProjection("Kadur", "Arsikere Jn",
            0.5f, "Banavar", 186, "DRU", "ASK", emptyList(),
            RouteBetween.MarkerKind.SITE_INTERMEDIATE))
        adapter.submitList(listOf(kadur, arsikere))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.inlineLegGroup.visibility)
        assertEquals(View.VISIBLE, holder.inlineLeg.visibility)
        assertTrue(holder.inlineLeg.batteryOutline)
        assertEquals(View.GONE, holder.trainMarker.visibility) // one source event, one logo
        assertTrue(holder.inlineLeg.contentDescription.contains("Banavar"))
        assertTrue(holder.inlineLeg.contentDescription.contains("11:47"))
        assertTrue(holder.inlineLeg.contentDescription.contains("9 min ago"))
        assertTrue(holder.inlineLeg.contentDescription.contains("present position unknown"))
        val width = (360f * ctx.resources.displayMetrics.density).toInt()
        holder.inlineLeg.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        holder.inlineLeg.layout(0, 0, width, holder.inlineLeg.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, holder.inlineLeg.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        holder.inlineLeg.paintForTest(canvas)
        var white = 0
        for (x in 0 until bitmap.width step 2) for (y in 0 until bitmap.height step 2)
            if (bitmap.getPixel(x, y) == Color.WHITE) white++
        assertTrue("white point-pen train and label were not painted", white > 35)
        bitmap.recycle()
        adapter.setProjectedLeg(null)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.inlineLeg.visibility)
        assertFalse(holder.inlineLeg.batteryOutline)
    }

    @Test fun officialDepartureCueStaysAtLastTimedStopOnlyInBatteryMode() {
        Prefs(ctx).batteryMode = true
        val departed = TrackModel.Stop(1, "SMET", "Shimoga Town", 425, 430, 580, 588, null,
            TrackModel.MinuteSource.REPORTED, null, TrackModel.State.PASSED,
            true, 98, 0, 20, false, false, false, null)
        val upcoming = departed.copy(seq = 2, code = "SME", name = "Shimoga",
            showArr = null, showDep = null, source = TrackModel.MinuteSource.SCHEDULE,
            schedArr = 435, schedDep = 436, isAnchor = false)
        val leg = RouteBetween.LegProjection("Shimoga Town", "Shimoga", 0.2f,
            "Shimoga", 98, "SMET", "SME", emptyList(),
            RouteBetween.MarkerKind.REPORTED_DEPARTURE_CUE)
        val adapter = StopAdapter().apply { batteryMode = true }
        adapter.setProjectedLeg(leg)
        adapter.legAgeSeconds = 9 * 60L
        adapter.submitList(listOf(departed, upcoming))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.VISIBLE, holder.trainMarker.visibility)
        assertEquals(View.GONE, holder.inlineLeg.visibility)
        assertTrue(holder.eventCaption.text.toString().contains("as of 09:48"))
        assertTrue(holder.eventCaption.text.toString().contains("NTES departure"))
        assertTrue(holder.trainMarker.contentDescription.contains("present position unknown"))
        Prefs(ctx).batteryMode = false
        val normal = StopAdapter().apply {
            batteryMode = false
            setProjectedLeg(leg)
            submitList(listOf(departed, upcoming))
        }
        val standard = normal.onCreateViewHolder(FrameLayout(ctx), 0)
        normal.onBindViewHolder(standard, 0)
        assertEquals(View.GONE, standard.trainMarker.visibility)
        assertEquals(View.VISIBLE, standard.inlineLeg.visibility)
        assertFalse(standard.inlineLeg.batteryOutline)
    }

    @Test fun aTimetableOrUnagedClaimNeverGetsABatteryLocomotive() {
        Prefs(ctx).batteryMode = true
        val adapter = StopAdapter().apply { batteryMode = true }
        val stop = TrackModel.Stop(1, "ASK", "Arsikere Jn", 530, 535, null, null, null,
            TrackModel.MinuteSource.SCHEDULE, null, TrackModel.State.NEXT,
            false, 206, 0, 50, false, false, false, null)
        adapter.haltMarker = TableMarker.Halt("ASK", "No accepted event", true)
        adapter.submitList(listOf(stop))
        val holder = adapter.onCreateViewHolder(FrameLayout(ctx), 0)
        adapter.onBindViewHolder(holder, 0)
        assertEquals(View.GONE, holder.trainMarker.visibility)
        assertEquals(View.GONE, holder.inlineLeg.visibility)
        assertEquals(View.GONE, holder.marker.visibility)
    }

    private fun pixel(v: View, x: Int, y: Int): Int {
        val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bitmap))
        return bitmap.getPixel(x, y).also { bitmap.recycle() }
    }
}
