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
        const val KEY_ACCENT = "accent"
        const val KEY_DIM = "dim"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_UPDATE_DEFERRED = "update_deferred"
        const val KEY_HIDDEN_APPS = "hidden_apps"
        const val KEY_CLOCK_TAP = "clock_tap_app"
        const val KEY_FAVORITES = "favorite_apps"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_BT_PERM_ASKED = "bt_permission_asked"
        const val KEY_LAST_LAUNCHED = "last_launched_pkg"
    }
}
