package fr.arichard.lastlauncher.settings

import android.content.Context
import androidx.preference.PreferenceManager

/** Typed access to the app's settings. All defaults live here. */
class Prefs(context: Context) {

    private val sp = PreferenceManager.getDefaultSharedPreferences(context)

    val keyboardAlways: Boolean get() = sp.getBoolean(KEY_KEYBOARD_ALWAYS, true)
    val predictions: Boolean get() = sp.getBoolean(KEY_PREDICTIONS, true)
    val btSignal: Boolean get() = sp.getBoolean(KEY_BT_SIGNAL, true)
    val doubleTapLock: Boolean get() = sp.getBoolean(KEY_DOUBLE_TAP_LOCK, true)
    val swipeDownNotifications: Boolean get() = sp.getBoolean(KEY_SWIPE_DOWN, true)
    val showGestureHints: Boolean get() = sp.getBoolean(KEY_GESTURE_HINTS, true)

    /** Raw encoded binding for an edge-swipe slot; see GestureBinding. */
    fun gestureBinding(key: String): String =
        sp.getString(key, GESTURE_DEFAULTS[key]) ?: "none"
    val haptics: Boolean get() = sp.getBoolean(KEY_HAPTICS, true)
    val animations: Boolean get() = sp.getBoolean(KEY_ANIMATIONS, true)
    val showClock: Boolean get() = sp.getBoolean(KEY_SHOW_CLOCK, true)
    val showStatusLine: Boolean get() = sp.getBoolean(KEY_SHOW_STATUS_LINE, true)

    /** Which status-line tokens are enabled (battery/network/alarm/launches/storage). */
    val statusTokens: Set<String>
        get() = sp.getStringSet(KEY_STATUS_TOKENS, null)
            ?: fr.arichard.lastlauncher.ui.StatusLine.DEFAULT_TOKENS
    val accent: String get() = sp.getString(KEY_ACCENT, "cyan") ?: "cyan"

    /** Wallpaper dim, 0–80 percent. */
    val dim: Int get() = sp.getInt(KEY_DIM, 25)

    val autoUpdate: Boolean get() = sp.getBoolean(KEY_AUTO_UPDATE, true)
    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()
    var updateDeferred: Boolean
        get() = sp.getBoolean(KEY_UPDATE_DEFERRED, false)
        set(value) = sp.edit().putBoolean(KEY_UPDATE_DEFERRED, value).apply()

    /** App opened by tapping the clock; empty = the system clock app. */
    val clockTapTarget: String get() = sp.getString(KEY_CLOCK_TAP, "") ?: ""

    // ------------------------------------------------------------------ weather

    /** Show a weather chip by the clock (needs location + network). Off by default. */
    val weatherEnabled: Boolean get() = sp.getBoolean(KEY_WEATHER_ENABLED, false)

    /** "c" or "f" — display units for the weather temperature. */
    val weatherUnits: String get() = sp.getString(KEY_WEATHER_UNITS, "c") ?: "c"

    /** What the chip shows: "icon_temp", "temp", or "icon". */
    val weatherStyle: String get() = sp.getString(KEY_WEATHER_STYLE, "icon_temp") ?: "icon_temp"

    /** App opened when the weather chip is tapped; empty = a weather web search. */
    val weatherTapTarget: String get() = sp.getString(KEY_WEATHER_TAP, "") ?: ""

    val weatherHasCache: Boolean get() = sp.contains(KEY_WEATHER_TEMP)
    val weatherTempC: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_WEATHER_TEMP, 0))
    val weatherCode: Int get() = sp.getInt(KEY_WEATHER_CODE, 0)
    val weatherLastFetch: Long get() = sp.getLong(KEY_WEATHER_FETCH, 0)

    fun saveWeather(tempC: Double, code: Int) {
        sp.edit()
            .putLong(KEY_WEATHER_TEMP, java.lang.Double.doubleToRawLongBits(tempC))
            .putInt(KEY_WEATHER_CODE, code)
            .putLong(KEY_WEATHER_FETCH, System.currentTimeMillis())
            .apply()
    }

    // ------------------------------------------------------------ app drawers

    /** One configurable slide-out drawer: a name plus its apps (empty = every app). */
    data class Drawer(val index: Int, val name: String, val apps: List<String>)

    /** All configured drawers; always at least one. Migrates the legacy single list. */
    fun drawers(): List<Drawer> {
        migrateLegacyDrawer()
        val count = sp.getInt(KEY_DRAWER_COUNT, 1).coerceIn(1, MAX_DRAWERS)
        return (0 until count).map { drawer(it) }
    }

    /** A single drawer; out-of-range indexes fall back to drawer 0. */
    fun drawer(index: Int): Drawer {
        migrateLegacyDrawer()
        val count = sp.getInt(KEY_DRAWER_COUNT, 1).coerceIn(1, MAX_DRAWERS)
        val i = if (index in 0 until count) index else 0
        val name = sp.getString("$KEY_DRAWER_NAME_PREFIX$i", null) ?: "Drawer ${i + 1}"
        val apps = (sp.getString("$KEY_DRAWER_APPS_PREFIX$i", "") ?: "")
            .split(',').filter { it.isNotEmpty() }
        return Drawer(i, name, apps)
    }

    fun saveDrawer(index: Int, name: String, apps: List<String>) {
        sp.edit()
            .putString("$KEY_DRAWER_NAME_PREFIX$index", name.trim().ifEmpty { "Drawer ${index + 1}" })
            .putString("$KEY_DRAWER_APPS_PREFIX$index", apps.distinct().joinToString(","))
            .apply()
    }

    /** Appends a drawer and returns its index, or -1 when the cap is reached. */
    fun addDrawer(name: String): Int {
        val count = drawers().size
        if (count >= MAX_DRAWERS) return -1
        saveDrawer(count, name, emptyList())
        sp.edit().putInt(KEY_DRAWER_COUNT, count + 1).apply()
        return count
    }

    /** Removes a drawer (never the last one) and compacts the ones after it. */
    fun deleteDrawer(index: Int) {
        val list = drawers().filter { it.index != index }
        if (list.isEmpty()) return
        val editor = sp.edit()
        list.forEachIndexed { i, d ->
            editor.putString("$KEY_DRAWER_NAME_PREFIX$i", d.name)
            editor.putString("$KEY_DRAWER_APPS_PREFIX$i", d.apps.joinToString(","))
        }
        editor.remove("$KEY_DRAWER_NAME_PREFIX${list.size}")
        editor.remove("$KEY_DRAWER_APPS_PREFIX${list.size}")
        editor.putInt(KEY_DRAWER_COUNT, list.size)
        editor.apply()
    }

    /** One-time move of the pre-1.4 single drawer list into drawer 0. */
    private fun migrateLegacyDrawer() {
        val legacy = sp.getString(KEY_DRAWER_APPS, null) ?: return
        sp.edit()
            .putString("${KEY_DRAWER_APPS_PREFIX}0", legacy)
            .remove(KEY_DRAWER_APPS)
            .apply()
    }

    // -------------------------------------------------------- notifications

    /** Show unread-count bubbles on app icons (needs notification access). */
    val notifBadges: Boolean get() = sp.getBoolean(KEY_NOTIF_BADGES, true)

    /** Home-screen ticker cycling through unread messages. Opt-in (privacy). */
    val messageTicker: Boolean get() = sp.getBoolean(KEY_MESSAGE_TICKER, false)

    /** Ordered go-to apps: cold-start suggestions and the static mode's content. */
    var favorites: List<String>
        get() = (sp.getString(KEY_FAVORITES, "") ?: "")
            .split(',').filter { it.isNotEmpty() }
        set(value) = sp.edit().putString(KEY_FAVORITES, value.distinct().joinToString(",")).apply()

    /** True once the starter-picks prompt has been answered (or declined). */
    var onboardingDone: Boolean
        get() = sp.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = sp.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    var hiddenApps: Set<String>
        get() = sp.getStringSet(KEY_HIDDEN_APPS, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_HIDDEN_APPS, value).apply()

    var btPermissionAsked: Boolean
        get() = sp.getBoolean(KEY_BT_PERM_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_BT_PERM_ASKED, value).apply()

    /** Persisted command-bar mode id (smart/apps/settings/ask). */
    var searchModeId: String
        get() = sp.getString(KEY_SEARCH_MODE, "smart") ?: "smart"
        set(value) = sp.edit().putString(KEY_SEARCH_MODE, value).apply()

    /** Last app launched from the launcher; feeds the app-to-app transition signal. */
    var lastLaunchedPkg: String?
        get() = sp.getString(KEY_LAST_LAUNCHED, null)
        set(value) = sp.edit().putString(KEY_LAST_LAUNCHED, value).apply()

    fun hideApp(pkg: String) {
        hiddenApps = hiddenApps + pkg
    }

    fun unhideApp(pkg: String) {
        hiddenApps = hiddenApps - pkg
    }

    companion object {
        const val KEY_KEYBOARD_ALWAYS = "keyboard_always"
        const val KEY_PREDICTIONS = "predictions"
        const val KEY_BT_SIGNAL = "bt_signal"
        const val KEY_DOUBLE_TAP_LOCK = "double_tap_lock"
        const val KEY_SWIPE_DOWN = "swipe_down_notifications"
        const val KEY_GESTURE_HINTS = "show_gesture_hints"

        // Edge-swipe slots, in CLI notation: LR = left→right (>), RL = right→left (<).
        const val KEY_GESTURE_LR_1 = "gesture_lr_1"
        const val KEY_GESTURE_LR_2 = "gesture_lr_2"
        const val KEY_GESTURE_RL_1 = "gesture_rl_1"
        const val KEY_GESTURE_RL_2 = "gesture_rl_2"

        val GESTURE_DEFAULTS = mapOf(
            KEY_GESTURE_LR_1 to "quick_settings",
            KEY_GESTURE_LR_2 to "assistant",
            KEY_GESTURE_RL_1 to "camera",
            KEY_GESTURE_RL_2 to "flashlight",
        )
        const val KEY_HAPTICS = "haptics"
        const val KEY_ANIMATIONS = "animations"
        const val KEY_SHOW_CLOCK = "show_clock"
        const val KEY_SHOW_STATUS_LINE = "show_status_line"
        const val KEY_STATUS_TOKENS = "status_tokens"
        const val KEY_ACCENT = "accent"
        const val KEY_DIM = "dim"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_UPDATE_DEFERRED = "update_deferred"
        const val KEY_HIDDEN_APPS = "hidden_apps"
        const val KEY_CLOCK_TAP = "clock_tap_app"
        const val KEY_WEATHER_ENABLED = "weather_enabled"
        const val KEY_WEATHER_UNITS = "weather_units"
        const val KEY_WEATHER_STYLE = "weather_style"
        const val KEY_WEATHER_TAP = "weather_tap_app"
        const val KEY_WEATHER_TEMP = "weather_temp_c"
        const val KEY_WEATHER_CODE = "weather_code"
        const val KEY_WEATHER_FETCH = "weather_last_fetch"
        const val KEY_FAVORITES = "favorite_apps"
        const val KEY_DRAWER_APPS = "drawer_apps" // legacy single drawer, migrated
        const val KEY_DRAWER_COUNT = "drawer_count"
        const val KEY_DRAWER_NAME_PREFIX = "drawer_name_"
        const val KEY_DRAWER_APPS_PREFIX = "drawer_apps_"
        const val MAX_DRAWERS = 5
        const val KEY_NOTIF_BADGES = "notif_badges"
        const val KEY_MESSAGE_TICKER = "message_ticker"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_BT_PERM_ASKED = "bt_permission_asked"
        const val KEY_LAST_LAUNCHED = "last_launched_pkg"
        const val KEY_SEARCH_MODE = "search_mode"
    }
}
