package `in`.opentrack.india.e2e

import android.content.Context
import android.content.Intent
import android.os.Build
import `in`.opentrack.india.R
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File

/**
 * The one way this suite touches the app: pixels, resource ids and the accessibility tree — what a person
 * sees and what a screen reader reads. No `ActivityScenario`, no reflection, no `App.getInstance()`.
 *
 * That is a deliberate restriction with a reason. The JVM tests in this repo already reach inside; the
 * device suite exists to answer "does the built APK, driven by hand, do what the traveller expects", and
 * the moment it imports a class from `app/src/main` it stops testing the app and starts testing the build.
 * It also means these tests survive a refactor the way a user does: they break when the *screen* changes.
 *
 * Timeouts are generous in one place only — cold start, which on a first run unpacks a 14 MB SQLite file
 * before anything is clickable. Everywhere else a short wait is a real failure, because a spinner that
 * needs 20 s is a bug on a phone, not a flake.
 */
object E2E {

    const val PKG = "in.opentrack.india"

    /** First launch after `pm clear`: extraction of the offline timetable, on a slow CI disk. */
    const val COLD_START_MS = 120_000L
    /** Anything that only reads the device database. */
    const val LOCAL_MS = 10_000L
    /** Anything that may wait on the network before the screen settles. */
    const val NETWORK_MS = 45_000L

    val ui: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }

    private val args: android.os.Bundle
        get() = InstrumentationRegistry.getArguments()

    /** `-e offline true`, `-e location denied`, `-e rotation 90`: set by CI, read by the tests. */
    fun arg(key: String, default: String = ""): String = args.getString(key) ?: default
    fun flag(key: String): Boolean = arg(key).equals("true", ignoreCase = true)

    // By.res(package, id) adds `:id/` itself. Passing `id/fromBox` searches for
    // `in.opentrack.india:id/id/fromBox` and made every device flow miss the entire UI.
    fun res(name: String): BySelector = By.res(PKG, name)
    fun byText(text: String): BySelector = By.text(text)
    fun find(name: String, timeoutMs: Long = LOCAL_MS): UiObject2? =
        ui.wait(Until.findObject(res(name)), timeoutMs)

    /** Accessibility nodes can be replaced between a successful find and the text read. Re-find once. */
    fun text(name: String, timeoutMs: Long = LOCAL_MS): String {
        repeat(2) {
            val value = runCatching { find(name, timeoutMs)?.text.orEmpty().trim() }.getOrNull()
            if (value != null) return value
        }
        return ""
    }

    fun visible(name: String, timeoutMs: Long = LOCAL_MS): Boolean = find(name, timeoutMs) != null

    /** One real finger swipe *inside* a scrolling viewport, never on the whole window.
     * On a large-font device both Settings and the train's source card exceed one screen;
     * off-screen children are not accessibility nodes until a traveller scrolls there. */
    fun swipeIn(container: String, up: Boolean = true): Boolean {
        val bounds = find(container, 500)?.visibleBounds ?: return false
        if (bounds.width() < 50 || bounds.height() < 90) return false
        // The search card nests suggestions and the tracking summary nests its
        // evidence viewport. A centre swipe can scroll only the child; use the
        // outer viewport's right gutter to reach off-screen form/route controls.
        val x = if (container == "searchCardScroll" || container == "scroller")
            bounds.right - 8 else bounds.centerX()
        val top = bounds.top + bounds.height() / 5
        val bottom = bounds.bottom - bounds.height() / 5
        ui.swipe(x, if (up) bottom else top, x, if (up) top else bottom, 28)
        ui.waitForIdle()
        return true
    }

    fun reveal(name: String, container: String, swipes: Int = 6, up: Boolean = true): Boolean {
        if (visible(name, 300)) return true
        repeat(swipes) {
            if (!swipeIn(container, up) || !visible(container, 300)) return false
            if (visible(name, 300)) return true
        }
        return false
    }

    /** A tap on a control's centre. Works whether or not the node reports itself clickable. */
    fun tap(name: String, timeoutMs: Long = LOCAL_MS): Boolean {
        repeat(2) {
            val o = find(name, timeoutMs) ?: return false
            try {
                o.click()
                ui.waitForIdle()
                return true
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                // A Save/Back click can finish its Activity while UiAutomator is still
                // reading the clicked node. If it disappeared, the tap DID land.
                ui.waitForIdle()
                if (find(name, 250) == null) return true
            }
        }
        return false
    }

    /** Types into a field the way a hardware keyboard does, after focusing it the way a finger does. */
    fun type(name: String, value: String, timeoutMs: Long = LOCAL_MS): Boolean {
        val o = find(name, timeoutMs) ?: return false
        setTextIn(o, value)
        return true
    }

    /** ACTION_SET_TEXT replaces the value atomically. Key events tried to select/delete first,
     * but the emulator IME swallowed those and left every non-empty box unchanged. Verify the
     * returned node rather than assuming UIAutomator's setText always succeeded. */
    fun setTextIn(o: UiObject2, value: String) {
        o.click()
        o.setText(value)
        ui.waitForIdle()
        val actual = o.text.orEmpty()
        // UiAutomator's UiObject2.hint calls AccessibilityNodeInfo.getHintText(), which
        // does not exist before API 26. API 24 exposes a blank edit field's hint as its
        // text instead; compare only against the app's actual displayed hint strings.
        val hintText = if (value.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 26) listOf(runCatching { o.hint.orEmpty() }.getOrDefault(""))
            else listOf(R.string.hint_from, R.string.hint_to, R.string.hint_train)
                .map { context().getString(it) } +
                // These two Settings placeholders are inline XML strings, not saved values.
                listOf("https://…", "1:code,3:sa,4:sd")
        } else emptyList()
        check(actual == value || (value.isEmpty() && actual in hintText)) {
            "the field did not accept the replacement: expected '$value', got '$actual'"
        }
    }

    fun clearField(name: String) {
        val o = find(name) ?: return
        setTextIn(o, "")
    }

    fun count(where: BySelector): Int = try {
        ui.findObjects(where).size
    } catch (e: IllegalStateException) {
        0
    }

    fun all(where: BySelector): List<UiObject2> = try {
        ui.findObjects(where)
    } catch (e: IllegalStateException) {
        emptyList()
    }

    /**
     * Launch (or relaunch) the app on its own task, waiting for its window rather than sleeping.
     *
     * `cold` is asserted, not performed: `pm clear` has to come from the harness, because UiAutomator
     * exposes no shell to a test process. The CI job therefore runs `adb shell pm clear` before the cold
     * row, which is what makes the first-run extraction observable at all — and the test fails if the app
     * somehow starts with an already-installed database, so the harness cannot silently skip the case.
     */
    fun launch(cold: Boolean = false) {
        val ctx = context()
        if (cold && flag("requireCleared")) {
            // The harness promised `pm clear` before this launch. If the timetable database is already
            // there, the "first run" this row is reporting on is a warm run, and every cold-start
            // assertion below it would be measuring the wrong thing.
            check(installedDbMissing()) {
                "cold row launched against an app that already has its timetable installed"
            }
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(PKG)
            ?: error("no launcher activity in $PKG — the APK is not the one this suite was built for")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        // The package root can match a departing activity or splash window. The Settings
        // button belongs to MainActivity and proves the actual search screen was drawn.
        // A cold extraction gets the full budget; ordinary launches should be prompt.
        // Some API 24 emulator launches are lost behind a still-starting system launcher;
        // retry the explicit intent once instead of wedging a whole instrument pass for
        // 120 seconds per test. A genuine app startup failure still fails visibly.
        val appeared = ui.wait(Until.hasObject(res("settings")), if (cold) COLD_START_MS else 20_000L)
        if (!appeared) {
            ctx.startActivity(intent)
            if (!ui.wait(Until.hasObject(res("settings")), 20_000L)) {
                // Some API 24 emulators leave the instrumented target process
                // behind the launcher even after two context starts. Drive the
                // same installed APK through Android's activity manager, as the
                // phone launcher does, then still require an actual rendered UI.
                // This is not a test pass unless the Settings control appears.
                val shellStart = runCatching {
                    ui.executeShellCommand("am start -W -n $PKG/.ui.MainActivity")
                }.getOrElse { "activity-manager error: ${it.message}" }
                if (!ui.wait(Until.hasObject(res("settings")), 10_000L)) {
                    // On a minority of API 24 cold boots Android says the task was
                    // brought forward, yet its window remains behind the launcher.
                    // Return to Home, then make a new app task. Never force-stop
                    // the target: instrumentation itself runs in its process on
                    // some Android versions, and pm clear would erase cold evidence.
                    ui.pressHome()
                    val freshStart = ui.executeShellCommand(
                        "am start -W -f 0x10008000 -n $PKG/.ui.MainActivity")
                    check(ui.wait(Until.hasObject(res("settings")), 20_000L)) {
                        "search activity absent after context, shell and clean task launch; " +
                            "package=${ui.currentPackageName}; first=${shellStart.take(110)}; " +
                            "retry=${freshStart.take(110)}"
                    }
                }
            }
        }
    }

    fun context(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Waits for the search screen to be interactive: the box exists AND the install progress is gone. */
    fun awaitSearchScreen() {
        // Returning from tracking can leave search intentionally compact. Reveal it through the
        // same Edit search control a traveller sees, not through an internal Activity hook.
        if (visible("editSearch", 500)) tap("editSearch", 1_000)
        check(visible("fromBox", COLD_START_MS)) { "no board-at field on the search screen" }
        val deadline = System.currentTimeMillis() + COLD_START_MS
        while (System.currentTimeMillis() < deadline) {
            // The old test treated an EMPTY status line as "not Installing", so it raced the
            // first asset extraction and tested search while MainViewModel still refused queries.
            val chip = text("dataChip", 500)
            // The opening placeholder belongs in statusLine, not in this readiness chip.
            if (chip.isNotBlank() && !chip.contains("Opening", true)) return
            val status = text("statusLine", 500)
            check(!status.contains("could not be installed", true)) { status }
            Thread.sleep(500)
        }
        error("the offline timetable never became ready within ${COLD_START_MS} ms; " +
            "status='${text("statusLine")}' chip='${text("dataChip")}'")
    }

    fun openTrackFromFirstResult(): Boolean {
        val go = ui.findObjects(By.res(PKG, "hitGo")).firstOrNull() ?: return false
        go.click()
        return visible("headline", NETWORK_MS) || visible("routeLabel", NETWORK_MS)
    }

    /** Legacy screenshots used by the broader manual E2E suite. */
    fun shot(tag: String) {
        val dir = evidenceDir() ?: return
        runCatching { ui.takeScreenshot(File(dir, "$tag.png")) }
        runCatching { ui.dumpWindowHierarchy(File(dir, "$tag.xml")) }
    }

    private fun evidenceDir(): File? {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "evidence")
        return if (dir.mkdirs() || dir.isDirectory) dir else null
    }

    /** Strict WIMT capture: fail before the provider GET if app pixels cannot be saved.
     * Target-app storage is writable from instrumentation and readable via adb run-as. */
    fun shotWimt(tag: String) {
        // Instrumentation executes under the TARGET app UID. The test APK's
        // context.filesDir is not writable here; targetContext.filesDir is.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.filesDir, "evidence")
        check(dir.mkdirs() || dir.isDirectory) { "Cannot create test screenshot directory" }
        val image = File(dir, "$tag.png")
        check(ui.takeScreenshot(image) && image.length() > 1000) {
            "Screenshot $tag failed inside the running app"
        }
        val hierarchy = File(dir, "$tag.xml")
        ui.dumpWindowHierarchy(hierarchy)
        check(hierarchy.length() > 100) { "Hierarchy $tag failed inside the running app" }
        println("WIMT_SHOT $tag bytes=${image.length()} xml=${hierarchy.length()} " +
            "appPackage=${ctx.packageName}")
    }

    /**
     * Display metrics for the geometry assertions: dp is the unit the layouts are written in.
     *
     * Taken from the target app's own `Resources` rather than from `UiDevice`, because the density and size
     * a layout is actually measured against is the one its `Context` reports - which is also the thing a
     * `wm density` override changes, which is what the large-screen runner row is doing.
     */
    fun densityDpi(): Int = context().resources.displayMetrics.densityDpi
    fun widthDp(): Int = dp(context().resources.displayMetrics.widthPixels)
    fun heightDp(): Int = dp(context().resources.displayMetrics.heightPixels)
    private fun dp(px: Int): Int = (px * 160f / densityDpi()).toInt()

    // `RefDb` keeps the extracted timetable at `filesDir/refdb.db`. The name is hardcoded here on
    // purpose: this suite does not compile against the app's classes, so a path rename is a change the
    // cold-start flow should notice rather than absorb.
    private fun installedDbMissing(): Boolean = try {
        !File(context().filesDir, "refdb.db").exists()
    } catch (e: Throwable) {
        true // an unreadable data dir is not evidence that the app is warm
    }

    // ---------------------------------------------------------------- the two suggest boxes
    //
    // Read *inside* the named box rather than by index of `suggestInput`: only the fields the current
    // search mode shows are in the tree at all, so a positional assumption would make a mode switch look
    // like a broken test.

    fun boxField(box: String, timeoutMs: Long = 300): UiObject2? {
        repeat(2) {
            val field = runCatching {
                find(box, timeoutMs)?.findObject(res("suggestInput"))
            }.getOrNull()
            if (field != null) return field
        }
        return null
    }

    /** The box can be above or below the visible part of the search card after a tap.
     * Accessibility reports off-screen fields as absent, not as empty text. */
    private fun reachableBoxField(box: String): UiObject2? {
        var field = boxField(box, 300)
        repeat(8) {
            if (field == null) {
                if (!swipeIn("searchCardScroll", up = box != "fromBox")) return null
                field = boxField(box, 300)
            }
        }
        return field
    }

    fun boxText(box: String): String {
        repeat(2) {
            val value = runCatching { reachableBoxField(box)?.text.orEmpty().trim() }.getOrNull()
            if (value != null) return value
        }
        return ""
    }

    /** One bounded field report in test failures when the IME or suggestion panel disappears. */
    fun debugSearch(box: String): String =
        "input='${boxText(box)}' chip='${find("dataChip", 100)?.text.orEmpty()}' " +
            "status='${find("statusLine", 100)?.text.orEmpty()}' " +
            "panel=${visible("suggestPanel", 100)} row=${visible("sugPrimary", 100)}"

    fun typeInBox(box: String, value: String): Boolean {
        // Suggestions expand inside the scrollable search card. On a large-font phone the
        // previous panel can push the next field out of the accessibility viewport;
        // physically scroll to it before typing rather than silently using old results.
        val input = reachableBoxField(box) ?: return false
        setTextIn(input, value)
        return true
    }

    /** The "Selected  SBC" chip a picked suggestion leaves behind — the resolved code, from the UI. */
    fun boxChip(box: String): String {
        repeat(2) {
            val value = runCatching {
                ui.findObject(res(box))?.findObject(res("suggestChip"))?.text.orEmpty().trim()
            }.getOrNull()
            if (value != null) return value
        }
        return ""
    }

    fun suggestionRows(): List<UiObject2> = all(By.res(PKG, "sugPrimary"))

    /** Taps the nth suggestion row inside a box's own panel, so the two boxes cannot cross wires. */
    fun tapSuggestion(box: String, index: Int = 0): Boolean {
        // Debounced results and accessibility nodes can be replaced after the initial
        // find, especially on a large-font emulator. Reacquire the *same box's* row
        // if the first tap misses; never count a closed panel as a selection.
        fun selected(): Boolean = runCatching {
            ui.findObject(res(box))?.findObject(res("suggestChip")) != null
        }.getOrDefault(false)
        repeat(3) {
            // A station panel is replaced when its debounce/query completes;
            // stale accessibility nodes are not a successful suggestion tap.
            val row = runCatching {
                ui.findObject(res(box))?.findObject(res("suggestPanel"))
                    ?.findObjects(By.res(PKG, "sugPrimary"))?.getOrNull(index)
            }.getOrNull()
            if (row == null) {
                if (selected() || visible("hitGo", 500)) return true
                Thread.sleep(200)
                return@repeat
            }
            runCatching { row.click(); ui.waitForIdle() }
            if ((ui.wait(Until.hasObject(res("suggestChip")), 2_500L) && selected()) ||
                visible("hitGo", 500)) return true
        }
        return false
    }

    fun resultRows(): Int = all(By.res(PKG, "hitNo")).size

    /**
     * The system dialogs that mean the app died. A crash on a device often surfaces as a dialog rather
     * than a failed assertion, and a suite that keeps tapping behind a "keeps stopping" sheet is worse than
     * useless — it reports passes. Checked after each flow.
     */
    fun assertNoSystemDialog(what: String) {
        val dialog = ui.findObject(By.textContains("keeps stopping"))
            ?: ui.findObject(By.textContains("won't respond"))
            ?: ui.findObject(By.textContains("isn't responding"))
            ?: ui.findObject(By.res("android", "aerr_text"))
        if (dialog != null) {
            shot("crash-$what")
            error("$what: the system says the app died (${dialog.text})")
        }
    }
}
