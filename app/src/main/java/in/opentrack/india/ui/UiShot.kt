package `in`.opentrack.india.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import `in`.opentrack.india.core.Diary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The picture half of the owner's ask: the app photographs its own window - no permission, no
 * media store, the app's own private files dir - on a per-screen throttle, keeping only the newest
 * few. The diary line names the file, so a paste says what the screen showed AND that a picture of
 * it exists on the device if anyone ever needs one. The app itself uploads nothing, anywhere.
 */
object UiShot {

    private const val MIN_INTERVAL_MILLIS = 45_000
    private const val KEEP = 6
    private val last = ConcurrentHashMap<String, Long>()

    fun snap(activity: Activity, tag: String) {
        if (`in`.opentrack.india.data.Prefs(activity).batteryMode) return // no PNG render/compress per bind
        runCatching {
            val now = System.currentTimeMillis()
            if (now - (last[tag] ?: 0L) < MIN_INTERVAL_MILLIS) return
            val v = activity.window.decorView
            if (v.width == 0 || v.height == 0) return
            last[tag] = now
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            val dir = File(activity.filesDir, "uistate").apply { mkdirs() }
            val stamp = SimpleDateFormat("HHmmss", Locale.ENGLISH).format(now)
            val f = File(dir, "$tag-$stamp.png")
            f.outputStream().use { out -> b.compress(Bitmap.CompressFormat.PNG, 90, out) }
            b.recycle()
            Diary.get()?.log("UISTATE") {
                "screenshot $tag -> " + f.name + " (" + f.length() + " B, on this device only)"
            }
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        }.onFailure {
            Diary.get()?.log("UISTATE") { "screenshot $tag failed: ${it.javaClass.simpleName}" }
        }
    }
}
