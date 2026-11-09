package `in`.opentrack.india.data

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

/**
 * All persisted user state, in one file.
 *
 * Two reasons this is hand-rolled on SharedPreferences rather than DataStore: DataStore would add a
 * kotlinx-serialization/protobuf dependency to a 5 MB app for four scalars, and SharedPreferences'
 * synchronous read is genuinely what we want here — the values are consulted on the UI thread when a
 * screen binds, and they are small.
 *
 * The *reason these specific keys exist* is the resilience plan: `overrideUrl` and `columnHint` are
 * what let a user keep a working app after an upstream format or policy change, with no new release.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("opentrack", Context.MODE_PRIVATE)

    /** Stored separately from the installed Android theme so Follow system reacts to OS changes. */
    var appearance: Int
        get() = sp.getInt("appearance", 0).coerceIn(0, 2)
        set(value) = sp.edit().putInt("appearance", value.coerceIn(0, 2)).apply()

    fun nightMode(): Int = if (batteryMode) AppCompatDelegate.MODE_NIGHT_YES else when (appearance) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2 -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Independent of Appearance. OLED-only visuals and reduced background work; off by default. */
    var batteryMode: Boolean
        get() = sp.getBoolean(KEY_BATTERY_MODE, false)
        set(value) = sp.edit().putBoolean(KEY_BATTERY_MODE, value).apply()

    var overrideUrl: String
        get() = sp.getString(KEY_OVERRIDE, "").orEmpty()
        set(v) = sp.edit().putString(KEY_OVERRIDE, v).apply()

    /** Positional field map for the live feed, e.g. `1:code,2:name,3:sa,4:sd,5:aa,6:ad`. */
    var columnHint: String
        get() = sp.getString(KEY_COLUMN_HINT, "").orEmpty()
        set(v) = sp.edit().putString(KEY_COLUMN_HINT, v).apply()

    /**
     * Whether the user has asked for a diagnostic log. Persisted, because a log left on across a restart is
     * how a bug that only happens on the first request after boot ever gets caught.
     */
    var diaryEnabled: Boolean
        get() = sp.getBoolean(KEY_DIARY, false)
        set(v) = sp.edit().putBoolean(KEY_DIARY, v).apply()

    /** The mirror's last known actuals, one JSON object keyed by train number - stale-while-revalidate. */
    var mirrorCache: String
        get() = sp.getString(KEY_MIRROR_CACHE, "{}").orEmpty().ifBlank { "{}" }
        set(v) = sp.edit().putString(KEY_MIRROR_CACHE, v).apply()

    /** The full-route halt list per train (erail's page), cached for a day - same SWR discipline. */
    var erailRouteCache: String
        get() = sp.getString(KEY_ERAIL_ROUTE_CACHE, "{}").orEmpty().ifBlank { "{}" }
        set(v) = sp.edit().putString(KEY_ERAIL_ROUTE_CACHE, v).apply()

    var lastFromCode: String
        get() = sp.getString(KEY_LAST_FROM, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_FROM, v).apply()

    var lastToCode: String
        get() = sp.getString(KEY_LAST_TO, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_TO, v).apply()

    /** Most recently opened train, as `trainNo|fromCode|toCode|originEpochDay`. */
    var lastTrain: String
        get() = sp.getString(KEY_LAST_TRAIN, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_TRAIN, v).apply()

    /** True once the user has seen (or dismissed) the first-launch data screen. */
    var onboarded: Boolean
        get() = sp.getBoolean(KEY_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(KEY_ONBOARDED, v).apply()

    /**
     * ADR-006 traveller reports. Shipped off-by-default per the ADR; flipped ON-by-default the same
     * day on the acceptance authority's explicit direction ("Priority is to get actual arrival and
     * departure time. Work for that, do anything") - reading is passive and renders nothing until a
     * real report exists, and the toggle still governs the publish controls outright.
     */
    var crowdReports: Boolean
        get() = sp.getBoolean(KEY_CROWD, true)
        set(v) = sp.edit().putBoolean(KEY_CROWD, v).apply()

    /** Default for the tracking screen; the train screen may change it as well. */
    var defaultTrackingSource: String
        get() = sp.getString(KEY_TRACK_SOURCE, "NTES (official)").orEmpty()
        set(v) = sp.edit().putString(KEY_TRACK_SOURCE, v).apply()

    var useLocation: Boolean
        get() = sp.getBoolean(KEY_USE_LOCATION, false)
        set(v) = sp.edit().putBoolean(KEY_USE_LOCATION, v).apply()

    fun recentTrains(): List<String> =
        sp.getString(KEY_RECENT, "")?.split(';')?.filter { it.isNotBlank() } ?: emptyList()

    fun pushRecent(trainNo: String, from: String, to: String, originEpochDay: Long) {
        val entry = "$trainNo|$from|$to|$originEpochDay"
        val list = (listOf(entry) + recentTrains()).distinct().take(6)
        sp.edit().putString(KEY_RECENT, list.joinToString(";")).apply()
    }

    fun clearCaches() = sp.edit()
        .remove(KEY_LAST_FROM).remove(KEY_LAST_TO).remove(KEY_LAST_TRAIN).remove(KEY_RECENT).apply()

    companion object {
        private const val KEY_BATTERY_MODE = "batteryMode"
        private const val KEY_OVERRIDE = "overrideUrl"
        private const val KEY_COLUMN_HINT = "columnHint"
        private const val KEY_DIARY = "diary"
        private const val KEY_LAST_FROM = "lastFrom"
        private const val KEY_LAST_TO = "lastTo"
        private const val KEY_LAST_TRAIN = "lastTrain"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_USE_LOCATION = "useLocation"
        private const val KEY_TRACK_SOURCE = "defaultTrackingSource"
        private const val KEY_RECENT = "recent"
        private const val KEY_CROWD = "crowdReports"
        private const val KEY_MIRROR_CACHE = "mirrorCache"
        private const val KEY_ERAIL_ROUTE_CACHE = "erailRouteCache"
    }
}
