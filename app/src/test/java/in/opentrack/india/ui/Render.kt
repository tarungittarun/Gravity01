package `in`.opentrack.india.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import `in`.opentrack.india.R
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.TrainPosition
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment

/**
 * Renders a screen the way a device would, on the JVM, and writes the result out as an image.
 *
 * There is no emulator available for this project (no KVM, no system images) and no way to look at the app
 * from CI, which left the one thing a reviewer can judge only by looking completely unverified. Robolectric
 * runs the real framework: the actual layout XML is inflated against the actual theme, measured, laid out
 * and painted by the same drawing code a phone runs.
 *
 * `prepare` deliberately runs *between two* measure passes. The custom views here compute geometry from
 * their own `width` when they are handed data (`TrackInsightView.setJourney`), so binding before the first
 * layout would paint into a zero-size canvas — an artefact of the harness, not of the app.
 */
object Render {

    const val WIDTH_DP = 360f
    const val HEIGHT_DP = 760f

    /** Ceiling for a captured screen, so a 140-row route table cannot ask for a 30 000 px bitmap. */
    const val MAX_CONTENT_DP = 2400f

    class Shot(val root: View, val bitmap: Bitmap, val context: Context) {

        fun text(id: Int): String = (root.findViewById<TextView>(id))?.text?.toString().orEmpty()

        /**
         * A view's box in the *bitmap's* coordinate space, which is the host's top-left.
         *
         * `getLocationInWindow` looks right and is wrong here: nothing in a Robolectric render is attached
         * to a window, so it returns each view's offset within its immediate parent — which silently turned
         * "sample the chart" into "sample the card that contains it", and made an assertion about the live
         * polyline pass by reading the accent-coloured "next stop" label three rows above it. The chain is
         * walked by hand instead, minus every parent's scroll, so the rect is the pixels being painted.
         */
        fun bounds(id: Int): Rect {
            val target = root.findViewById<View>(id) ?: return Rect()
            var x = 0
            var y = 0
            var v: View = target
            while (v !== root) {
                val parent = v.parent as? View ?: break
                x += v.left - parent.scrollX
                y += v.top - parent.scrollY
                v = parent
            }
            return Rect(x, y, x + target.width, y + target.height)
        }

        /** Distinct colours painted inside `area`; a screen or chart that drew nothing scores 1. */
        fun distinct(area: Rect = Rect(0, 0, bitmap.width, bitmap.height)): Int {
            val seen = HashSet<Int>(4096)
            var y = maxOf(0, area.top)
            while (y < minOf(bitmap.height, area.bottom)) {
                var x = maxOf(0, area.left)
                while (x < minOf(bitmap.width, area.right)) {
                    seen.add(bitmap.getPixel(x, y) and 0x00FFFFFF)
                    x += 2
                }
                y += 2
            }
            return seen.size
        }

        /** How many sampled pixels are exactly `color`; the count, so a failure message can carry it. */
        fun countWith(color: Int, area: Rect = Rect(0, 0, bitmap.width, bitmap.height)): Int {
            val target = color and 0x00FFFFFF
            var n = 0
            var y = maxOf(0, area.top)
            while (y < minOf(bitmap.height, area.bottom)) {
                var x = maxOf(0, area.left)
                while (x < minOf(bitmap.width, area.right)) {
                    if (bitmap.getPixel(x, y) and 0x00FFFFFF == target) n++
                    x += 2
                }
                y += 2
            }
            return n
        }

        /** Where the pixels of `color` are, so a failure message can name the element that painted them. */
        fun regionOf(color: Int, area: Rect = Rect(0, 0, bitmap.width, bitmap.height)): Rect? {
            val target = color and 0x00FFFFFF
            var left = Int.MAX_VALUE; var top = Int.MAX_VALUE; var right = -1; var bottom = -1
            var y = maxOf(0, area.top)
            while (y < minOf(bitmap.height, area.bottom)) {
                var x = maxOf(0, area.left)
                while (x < minOf(bitmap.width, area.right)) {
                    if (bitmap.getPixel(x, y) and 0x00FFFFFF == target) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                    x += 2
                }
                y += 2
            }
            return if (right < 0) null else Rect(left, top, right + 2, bottom + 2)
        }

        fun paintedWith(color: Int, area: Rect = Rect(0, 0, bitmap.width, bitmap.height)): Boolean {
            val target = color and 0x00FFFFFF
            var y = maxOf(0, area.top)
            while (y < minOf(bitmap.height, area.bottom)) {
                var x = maxOf(0, area.left)
                while (x < minOf(bitmap.width, area.right)) {
                    if (bitmap.getPixel(x, y) and 0x00FFFFFF == target) return true
                    x += 2
                }
                y += 2
            }
            return false
        }
    }

    fun theme(): Context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_OpenTrack)

    /**
     * Multiply every resolved text size in the tree, which is what a big system font does to a layout.
     *
     * The obvious route — a `Configuration` with `fontScale = 1.3`, optionally with `scaledDensity` pinned —
     * was written first and measured as a *lie*: Robolectric resolves `21sp` to 42 px at density 2.0 with
     * `fontScale` at 1.3 as well as at 1.0, i.e. it treats sp as dp, so the Configuration never reached the
     * text. Scaling the sizes themselves is honest and it is the same arithmetic the framework does; what it
     * does not reproduce is sp-based `lineSpacingExtra` and sp padding, which stay put. `LargeScreenRenderTest`
     * asserts the factor actually landed before any picture from it is believed.
     */
    fun applyTextScale(view: View, factor: Float, seen: MutableSet<TextView> = HashSet()) {
        if (view is TextView && seen.add(view)) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.textSize * factor)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTextScale(view.getChildAt(i), factor, seen)
        }
    }

    fun dp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun render(
        layoutRes: Int,
        widthDp: Float = WIDTH_DP,
        heightDp: Float = 0f,
        textScale: Float = 1f,
        prepare: (View) -> Unit = {},
    ): Shot {
        val ctx = theme()
        val w = dp(ctx, widthDp).toInt()
        val device = dp(ctx, if (heightDp > 0f) heightDp else HEIGHT_DP).toInt()
        val cap = dp(ctx, MAX_CONTENT_DP).toInt()
        val host = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        host.layoutParams = ViewGroup.LayoutParams(w, device)
        host.addView(LayoutInflater.from(ctx).inflate(layoutRes, host, false))

        fun measure(heightPx: Int, mode: Int) = host.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, mode),
        )

        // Pass 1, at the device height: this is what gives the custom views a real width to compute their
        // geometry from, which is why `prepare` cannot run before it.
        measure(device, View.MeasureSpec.EXACTLY)
        host.layout(0, 0, w, device)
        prepare(host)

        // Rows that a list creates do not exist until something measures them, so the scale is applied again
        // after the height has been learned and the content laid out once more at the scaled sizes. `seen`
        // makes that second walk safe: a view already multiplied is not multiplied twice.
        val seen = HashSet<TextView>()
        if (textScale != 1f) applyTextScale(host, textScale, seen)

        // Pass 2, unbounded, to learn what the content actually wants. Skipping this is how the first
        // version of this harness "found" a route table with one row in it: a RecyclerView lays out only as
        // many rows as it is *given*, so measuring at 760dp clipped the list to what fit on the fold and
        // the assertions would have been written against a half-painted screen. Screens are therefore
        // captured whole — which is also the only way to review spacing in a table.
        measure(0, View.MeasureSpec.UNSPECIFIED)
        var h = host.measuredHeight.coerceIn(device, cap)

        measure(h, View.MeasureSpec.EXACTLY)
        host.layout(0, 0, w, h)
        if (textScale != 1f) {
            applyTextScale(host, textScale, seen)
            measure(0, View.MeasureSpec.UNSPECIFIED)
            val grown = host.measuredHeight.coerceIn(device, cap)
            if (grown != h) {
                h = grown
                measure(h, View.MeasureSpec.EXACTLY)
            }
            host.layout(0, 0, w, h)
        }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(ctx.getColor(R.color.bg))
        host.draw(canvas)
        return Shot(host, bmp, ctx)
    }

    /**
     * The tracking screen's core bindings, in one place so the layout and shipped-data tests paint
     * the same table and headline. LiveViewModel-only source states belong to the Activity's Info dialog.
     */
    fun track(root: View, m: TrackModel.View, pos: TrainPosition?, now: Int, note: String? = null) {
        text(root, R.id.trainNo, m.trainNo)
        text(root, R.id.routeLabel, m.routeLabel)
        text(root, R.id.runDate, m.runDateLabel)
        val hero = if (m.isLive) JourneyHero.official(m.rows,
            m.rows.firstOrNull { it.isAnchor && it.source == TrackModel.MinuteSource.REPORTED }?.code)
            else null
        text(root, R.id.headline, hero?.let { "Last NTES report: ${it.title} · now unknown" } ?: m.headline)
        text(root, R.id.heroEyebrow, if (m.rows.any {
                it.source == TrackModel.MinuteSource.REPORTED
            }) "NTES REPORT" else "TIMETABLE ONLY")
        text(root, R.id.provenance, m.provenanceLabel)
        text(root, R.id.age, m.ageLabel)
        // The same composition the Activity uses, so no fixture has to invent this sentence (and cannot
        // quietly put facts on a screen that the app never shows there).
        text(root, R.id.delay, m.delayLabel)
        text(root, R.id.next, hero?.next ?: m.nextLabel)
        // progress bar and chart removed (round 42): the owner judged them clutter; the render
        // harness paints what the screen actually carries now.
        root.findViewById<View>(R.id.note)?.let { v ->
            v.visibility = if (note == null && m.note == null) View.GONE else View.VISIBLE
            (note ?: m.note)?.let { (v as TextView).text = it }
        }
        root.findViewById<RecyclerView>(R.id.stops)?.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = StopAdapter().apply { submitList(m.rows) }
            isNestedScrollingEnabled = false
            Render.settle(this, m.rows.size)
        }
    }

    /**
     * Let a `ListAdapter`'s DiffUtil finish. `submitList` computes the update on its own handler thread,
     * which Robolectric does not own, so the list is empty until that thread hands the result back to the
     * main looper. This idles the main looper until `expected` rows are in the adapter — and if they never
     * arrive it returns anyway, leaving the caller's assertion to fail with the count that is visible.
     *
     * There is no synchronous setter to use instead: recyclerview 1.3 exposes `getCurrentList()` only, and
     * `currentList =` is not assignable (the getter is `@NonNull`, so Kotlin sees a val).
     */
    /**
     * Nothing about this text was cut: neither an ellipsis, nor the silent kind.
     *
     * This is the check no unit test can fake, because it reads the `Layout` the real `TextView` built and so
     * only passes if the text fitted *at this width, in this font size*. Asserting on the string instead is
     * what let "SBC → SMET · 274 km · …" ship: the value was right, the pixels were not.
     *
     * `getEllipsisCount` alone is not enough. A `singleLine` `TextView` with no `ellipsize` — which is what
     * `@style/Pill` is — does not draw an ellipsis at all, it simply stops drawing, so a badge reading
     * "OFFLINE · schedule onl" would pass an ellipsis check while losing letters off the right edge. Every
     * line's measured width is therefore compared with the width the view was given.
     */
    fun assertNothingCut(view: View?, what: String = "view") {
        val tv = view as? TextView
        assertNotNull("no $what view to measure", tv)
        val layout = tv!!.layout
        assertNotNull("$what was never laid out", layout)
        for (line in 0 until layout.lineCount) {
            assertEquals("$what was cut with an ellipsis on line $line of '${tv.text}'",
                0, layout.getEllipsisCount(line))
            val need = Math.round(layout.getLineWidth(line))
            val have = layout.width
            // Tolerant by ~3%, and only here: Robolectric draws with substituted font metrics, and a line it
            // measures 4 px wider than the space it was given (698 against 694) is a rounding difference in
            // those metrics rather than a missing glyph — a device would have wrapped it. A truncation big
            // enough to hide a word is still far outside this band, and the ellipsis check above is exact.
            assertTrue("$what needs $need px on line $line but only has $have px: '${tv.text}'",
                have <= 0 || need <= have + maxOf(4, have / 32))
        }
    }

    fun settle(list: RecyclerView?, expected: Int) {
        val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
        var waited = 0
        while (list?.adapter != null && (list.adapter?.itemCount ?: -1) != expected && waited < 2_000) {
            main.idle()
            Thread.sleep(20)
            waited += 20
        }
        main.idle()
    }

    fun text(root: View, id: Int, value: String?) {
        (root.findViewById<TextView>(id))?.text = value.orEmpty()
    }

    fun read(root: View, id: Int): String = (root.findViewById<TextView>(id))?.text?.toString().orEmpty()

    /** Row views a RecyclerView laid out are its children; a collapsed list is the classic bug. */
    /** Route rows in the adapter, not only the viewport's attached children. A bounded
     * source card keeps the list usable at large fonts; RecyclerView then correctly
     * recycles off-screen rows even in Robolectric's tall screenshot render. */
    fun rowCount(root: View): Int =
        (root.findViewById<RecyclerView>(R.id.stops))?.adapter?.itemCount ?: -1

    fun save(name: String, bmp: Bitmap) {
        val dir = File(System.getProperty("preview.dir") ?: "app/build/reports/previews")
        dir.mkdirs()
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The full PNG is for the artifact a human downloads. The small JPEG is what CI can put in a
        // commit comment as base64 (65 536 characters), which is the only way these pixels get reviewed.
        val scale = (System.getProperty("preview.scale") ?: "55").toInt()
        val quality = (System.getProperty("preview.quality") ?: "62").toInt()
        val tileH = (System.getProperty("preview.tile") ?: "1500").toInt()
        // Tiles rather than one tall image: a whole tracking screen is ~2 500 px, and a single JPEG of it
        // is over the comment budget — so the tall screens were silently skipped while short ones got
        // posted. A harness that reviews only what happens to fit is worse than no harness.
        var from = 0
        var page = 0
        while (from < bmp.height) {
            val h = minOf(tileH, bmp.height - from)
            val slice = Bitmap.createBitmap(bmp, 0, from, bmp.width, h)
            val small = Bitmap.createScaledBitmap(
                slice, (slice.width * scale / 100).coerceAtLeast(1),
                (slice.height * scale / 100).coerceAtLeast(1), true
            )
            val out = ByteArrayOutputStream(1 shl 15)
            small.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val suffix = if (bmp.height <= tileH) "" else "-${++page}"
            File(dir, "$name$suffix.jpg").writeBytes(out.toByteArray())
            small.recycle()
            slice.recycle()
            from += h
        }
    }
}
