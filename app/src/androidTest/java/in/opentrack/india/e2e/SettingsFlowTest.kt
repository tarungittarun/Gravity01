package `in`.opentrack.india.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The data-source screen: the escape hatch that keeps this app alive when the public feed changes.
 *
 * Every control on it is load-bearing in an outage — a column map the user can type, an endpoint they can
 * repoint, a cache they can clear — so each is driven and its effect read back in the UI rather than in
 * SharedPreferences. A setting that persists but is not visible anywhere is a setting the user will not
 * find at 6 a.m. with a train to catch, and `junkEndpointSaysSo` is here for exactly that reason.
 */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {

    @Before
    fun up() {
        E2E.launch()
        E2E.awaitSearchScreen()
    }

    private fun saveSettings(expectFinish: Boolean = true): Boolean {
        // UiAutomator can report a click while the keyboard/scroll intercepts it
        // on a 440dpi large-font phone. Success is the destination screen, not a
        // tapped pixel. The invalid-endpoint flow intentionally stays in Settings.
        // Inspect the IME before tapping. Pressing Back AFTER a swallowed click
        // can close Settings (the IME already dismissed) without persisting.
        // This covers both AOSP Latin IME (API 24) and Gboard (API 34).
        val ime = runCatching { E2E.ui.executeShellCommand("dumpsys input_method") }
            .getOrDefault("")
        if (Regex("""\bmInput(?:View)?Shown=true""").containsMatchIn(ime) ||
            E2E.ui.hasObject(By.pkg("com.android.inputmethod.latin")) ||
            E2E.ui.hasObject(By.pkg("com.google.android.inputmethod.latin"))) {
            E2E.ui.pressBack()
        }
        repeat(2) { attempt ->
            if (E2E.reveal("save", "settingsScroll") && E2E.tap("save", 500)) {
                if (!expectFinish) return true
                if (E2E.visible("settings", E2E.LOCAL_MS)) return true
            }
            if (attempt == 0 && E2E.visible("settingsScroll", 500)) {
                // Retry only on the editable form; do not mistake a delivered
                // accessibility click for a finished Save.
                E2E.shot("settings-save-still-on-form")
            }
        }
        return false
    }

    private fun openSettings(): Boolean {
        // A valid Save finishes Settings asynchronously. Wait for the Home button
        // to become visible before reopening; a tap on the departing Activity is
        // not evidence that the saved form persisted on a fresh screen.
        if (!E2E.visible("settings", E2E.LOCAL_MS) || !E2E.tap("settings")) return false
        // The state is populated by an asynchronous repository check; a mounted but empty
        // TextView is not yet an answer to the traveller.
        val deadline = System.currentTimeMillis() + E2E.LOCAL_MS
        while (System.currentTimeMillis() < deadline) {
            if (E2E.text("state").isNotBlank()) return true
            Thread.sleep(150)
        }
        return false
    }

    // @flow settings-entry
    // @flow settings-open
    @Test
    fun everyControlIsReachableAndBackReturns() {
        assertTrue("the Settings button does not open settings", openSettings())
        assertTrue("the install state is blank: '${E2E.text("state")}'",
            E2E.text("state").isNotBlank())
        for (
            id in listOf(
                "state", "defaultTrackingSource", "appearance",
                "overrideUrl", "columnHint", "save", "clear",
                "diagToggle", "diagShare", "diagSave", "diagClear", "diagState", "about",
            )
        ) {
            assertTrue("settings screen is missing or cannot scroll to '$id'",
                E2E.reveal(id, "settingsScroll"))
        }
        E2E.shot("settings")
        // The about text is below the fold on large-font phones: Back remains reachable
        // through Android navigation without pretending the top button is still visible.
        E2E.ui.pressBack()
        // On API 24 the first system Back can dismiss an IME that retained focus
        // while the settings scroller was moved. One more Back must leave Settings.
        if (!E2E.visible("settings", 750)) E2E.ui.pressBack()
        assertTrue("back did not return to search", E2E.visible("settings"))
    }

    // @flow tracking-default-settings
    @Test
    fun trackingSourceDefaultMovesToSettingsAndPersists() {
        assertTrue(openSettings())
        assertTrue("default source is not reachable in Settings",
            E2E.reveal("defaultTrackingSource", "settingsScroll"))
        assertTrue("source picker cannot open", E2E.tap("defaultTrackingSource"))
        val website = E2E.ui.wait(Until.findObject(
            androidx.test.uiautomator.By.text("Where Is My Train · website")), E2E.LOCAL_MS)
        assertTrue("the site option is missing", website != null)
        website!!.click()
        assertTrue("selected default not labelled for TalkBack",
            E2E.find("defaultTrackingSource")?.contentDescription.orEmpty().contains("Where Is My Train · website"))
        assertTrue("Settings Home button cannot be reached", E2E.reveal("back", "settingsScroll", up = false))
        assertTrue("Settings Home button did not return Home", E2E.tap("back") && E2E.visible("settings"))
        assertTrue("Settings did not reopen", openSettings())
        assertTrue("default source did not persist",
            E2E.find("defaultTrackingSource")?.contentDescription.orEmpty().contains("Where Is My Train · website"))
        assertTrue(E2E.tap("defaultTrackingSource"))
        val official = E2E.ui.wait(Until.findObject(
            androidx.test.uiautomator.By.text("NTES · official")), E2E.LOCAL_MS)
        assertTrue("could not restore official default", official != null)
        official!!.click()
    }

    // @flow appearance-modes
    @Test
    fun lightDarkAndSystemSettingsRemainReadableAndPersist() {
        assertTrue(openSettings())
        val spinner = "appearance"
        for (mode in listOf("Light", "Dark", "Follow system")) {
            assertTrue("appearance selector missing", E2E.reveal(spinner, "settingsScroll"))
            assertTrue("appearance selector not tappable", E2E.tap(spinner))
            val option = E2E.ui.wait(Until.findObject(
                androidx.test.uiautomator.By.text(mode)), E2E.LOCAL_MS)
            assertTrue("missing $mode appearance option", option != null)
            option!!.click()
            // A night-mode change recreates the Activity. Assert the *new* Settings
            // window owns the same chosen value; selection alone is not persistence.
            assertTrue("$mode mode failed to repaint Settings", E2E.reveal(spinner, "settingsScroll"))
            assertTrue("$mode not selected on rebuilt screen: '${E2E.text(spinner)}'",
                E2E.find(spinner)?.contentDescription.orEmpty().contains(mode, true))
            assertTrue("$mode hid the source preference", E2E.visible("defaultTrackingSource"))
            E2E.shot("settings-appearance-${mode.replace(' ', '-')}")
        }
        assertTrue("Settings Home button cannot be reached after theme change",
            E2E.reveal("back", "settingsScroll", up = false))
        assertTrue("theme change did not restore Home", E2E.tap("back") && E2E.visible("settings"))
        assertTrue("Settings could not reopen with saved appearance", openSettings())
        assertTrue("follow-system choice was not persisted",
            E2E.find(spinner)?.contentDescription.orEmpty().contains("Follow system", true))
    }

    /**
     * The diagnostic collector, driven end to end: off by default, visibly live once enabled, and emptied by
     * Clear. The reason this is a device flow rather than a unit test is that the whole feature exists to be
     * handed to a stranger in distress — if the state line never changes when the switch is flipped, the
     * feature is worthless no matter how correct the file writing is.
     */
    // @flow diag-log-collector
    @Test
    fun diagnosticCollectorIsInertThenLiveThenEmpty() {
        assertTrue(openSettings())
        assertTrue("diagnostic card cannot be reached by scrolling",
            E2E.reveal("diagState", "settingsScroll"))
        val idle = E2E.text("diagState")
        assertTrue(
            "the card does not explain how to start collecting: '" + idle + "'",
            idle.contains("reproduce", ignoreCase = true) || idle.contains("Turn on", ignoreCase = true),
        )
        if (!E2E.visible("diagToggle", 300)) E2E.swipeIn("settingsScroll", up = false)
        assertTrue("collection cannot be turned on", E2E.tap("diagToggle"))
        E2E.ui.wait(Until.hasObject(E2E.res("diagState")), E2E.LOCAL_MS)
        val live = E2E.text("diagState") + " " + E2E.text("diagToggle")
        assertTrue(
            "enabling collection left no visible trace: '" + live + "'",
            live.contains("bytes", ignoreCase = true) || live.contains("Turn off", ignoreCase = true),
        )
        // Clear must empty it while leaving collection on — a user mid-repro must not have to re-enable.
        assertTrue("clear is unreachable", E2E.tap("diagClear"))
        E2E.ui.wait(Until.hasObject(E2E.res("diagState")), E2E.LOCAL_MS)
        val cleared = E2E.text("diagState")
        assertTrue(
            "after Clear the card still claims content: '" + cleared + "'",
            cleared.contains("reproduce", ignoreCase = true) || cleared.isBlank(),
        )
        assertTrue("collection cannot be turned off", E2E.tap("diagToggle"))
        E2E.shot("settings-diary")
    }

    // @flow endpoint-save-reload
    @Test
    fun endpointPersistsAcrossReopen() {
        assertTrue(openSettings())
        val probe = "https://example.invalid/{trainNo}/{date}"
        assertTrue("the endpoint field cannot be typed into", typeInBoxField("overrideUrl", probe))
        assertTrue("Save did not run", saveSettings())
        assertTrue("settings will not reopen", openSettings())
        val seen = fieldText("overrideUrl")
        assertTrue("the saved endpoint did not come back ('$seen')", seen.contains("example.invalid"))
        // Restore the default so a later row in this run is not testing the test.
        typeInBoxField("overrideUrl", "")
        assertTrue("Save did not run", saveSettings())
    }

    // @flow column-hint
    @Test
    fun columnHintPersists() {
        assertTrue(openSettings())
        val hint = "1:code,2:name,3:sa,4:sd,5:aa,6:ad"
        assertTrue("the column map cannot be typed into", typeInBoxField("columnHint", hint))
        assertTrue("Save did not run", saveSettings())
        assertTrue(openSettings())
        assertEquals("the column map did not round-trip", hint, fieldText("columnHint"))
        typeInBoxField("columnHint", "")
        assertTrue("Save did not run", saveSettings())
    }

    // @flow endpoint-rejects-non-http
    @Test
    fun junkEndpointSaysSo() {
        assertTrue(openSettings())
        assertTrue(typeInBoxField("overrideUrl", "not-a-url"))
        assertTrue("save could not be tapped", saveSettings(expectFinish = false))
        // Invalid input must keep this screen open with visible feedback, not silently persist
        // a broken live source. The old test pressed Back before reading this screen's state.
        assertTrue("the invalid-endpoint explanation is not reachable",
            E2E.reveal("state", "settingsScroll", up = false))
        val state = E2E.text("state")
        assertTrue("invalid endpoint did not explain what to fix ('$state')",
            state.contains("http", ignoreCase = true) && state.contains("Nothing was saved"))
        assertTrue("an invalid endpoint closed Settings instead of letting it be corrected",
            E2E.visible("overrideUrl", 500))
        typeInBoxField("overrideUrl", "")
        saveSettings()
    }

    // @flow clear-cache
    @Test
    fun clearingCacheIsVisibleOnTheTrackScreen() {
        assertTrue(openSettings())
        assertTrue("no clear control", E2E.reveal("clear", "settingsScroll") &&
            E2E.tap("clear"))
        // Clear is near the bottom, but its result is in ON THIS DEVICE near
        // the top. Off-screen nodes are absent from UiAutomator on API 24: a
        // blank read is not evidence that the cache-clear callback failed.
        assertTrue("cache-clear state line cannot be reached from the clear button",
            E2E.reveal("state", "settingsScroll", up = false))
        val deadline = System.currentTimeMillis() + E2E.LOCAL_MS
        while (!E2E.text("state").contains("cache cleared", true) && System.currentTimeMillis() < deadline) {
            Thread.sleep(150)
        }
        val state = E2E.text("state")
        assertTrue("cache clearing never updated the state line: '$state'",
            state.contains("cache cleared", true))
        E2E.tap("back")
        // Then check the *consequence* the user cares about: an offline screen after a clear must not present
        // anything as a recent live report.
        if (E2E.tap("modeTrain") && E2E.typeInBox("trainBox", "12627") &&
            E2E.ui.wait(Until.hasObject(E2E.res("sugPrimary")), E2E.LOCAL_MS) != null
        ) {
            assertTrue("train suggestion cannot be selected", E2E.tapSuggestion("trainBox", 0))
            assertTrue("Find missing after train suggestion",
                E2E.reveal("find", "searchCardScroll") && E2E.tap("find"))
            if (E2E.ui.wait(Until.hasObject(E2E.res("hitGo")), E2E.NETWORK_MS)) {
                E2E.openTrackFromFirstResult()
                val provenance = E2E.text("provenance")
                val age = E2E.text("age")
                assertNotEquals(
                    "the cache was cleared but the screen still claims '$provenance' / '$age'",
                    "LIVE · cached", "$provenance · $age",
                )
            }
        }
        E2E.shot("after-clear")
    }

    // @flow manual-refresh-only-settings
    @Test
    fun settingsHasNoAutoRefreshChoice() {
        assertTrue(openSettings())
        assertTrue("Auto chooser was meant to be removed", E2E.find("refreshChoices", 1_000) == null)
        assertTrue("the source override must remain available", E2E.visible("overrideUrl"))
    }

    // Settings fields are plain EditTexts, not SuggestBoxes, so they need their own accessors.
    private fun fieldOf(id: String): androidx.test.uiautomator.UiObject2? {
        if (!E2E.visible(id, 300) && !E2E.reveal(id, "settingsScroll")) return null
        return E2E.find(id, E2E.LOCAL_MS)
    }

    private fun typeInBoxField(id: String, value: String): Boolean {
        // A node can be replaced while Settings refreshes its install-state line.
        // Reacquire it once on UiAutomator's explicit stale-node signal, but never
        // treat a missing field or a rejected value as successful input.
        repeat(2) { attempt ->
            val field = fieldOf(id) ?: return false
            try {
                E2E.setTextIn(field, value)
                return true
            } catch (e: androidx.test.uiautomator.StaleObjectException) {
                if (attempt == 1) throw e
            }
        }
        return false
    }

    private fun fieldText(id: String): String = fieldOf(id)?.text.orEmpty().trim()
}
