package fr.arichard.lastlauncher.gesture

import fr.arichard.lastlauncher.R

/**
 * An action bindable to an edge swipe. The four slots are the one- and two-finger
 * horizontal swipes in each direction — in the launcher's CLI notation:
 *  `>`  left→right, one finger      `>>` left→right, two fingers
 *  `<`  right→left, one finger      `<<` right→left, two fingers
 */
enum class GestureAction(val id: String, val labelRes: Int, val iconRes: Int = 0) {
    NONE("none", R.string.gesture_none),
    NOTIFICATIONS("notifications", R.string.cmd_notifications, R.drawable.ic_bell),
    QUICK_SETTINGS("quick_settings", R.string.cmd_quick_settings, R.drawable.ic_tiles),
    LOCK("lock", R.string.cmd_lock, R.drawable.ic_lock),
    FLASHLIGHT("flashlight", R.string.cmd_flashlight, R.drawable.ic_flashlight),
    ASSISTANT("assistant", R.string.cmd_assistant, R.drawable.ic_assistant),
    ALL_APPS("all_apps", R.string.cmd_all_apps, R.drawable.ic_apps),
    APP_DRAWER("app_drawer", R.string.gesture_app_drawer, R.drawable.ic_apps),
    SEARCH("search", R.string.gesture_search, R.drawable.ic_search),
    CAMERA("camera", R.string.gesture_camera, R.drawable.ic_camera),
    DIALER("dialer", R.string.gesture_dialer, R.drawable.ic_phone),
    OPEN_APP("open_app", R.string.gesture_open_app, R.drawable.ic_apps);

    companion object {
        fun byId(id: String): GestureAction =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * A resolved gesture binding: an action plus an optional payload — the target app's
 * component key for [GestureAction.OPEN_APP], the drawer index for
 * [GestureAction.APP_DRAWER]. Serialised to a single preference string.
 */
data class GestureBinding(val action: GestureAction, val appKey: String? = null) {

    /** Which configured drawer an APP_DRAWER binding opens (default: the first). */
    val drawerIndex: Int get() = appKey?.toIntOrNull() ?: 0

    fun encode(): String =
        if (appKey != null) "${action.id}:$appKey" else action.id

    companion object {
        fun decode(raw: String?): GestureBinding {
            if (raw.isNullOrEmpty()) return GestureBinding(GestureAction.NONE)
            val sep = raw.indexOf(':')
            return if (sep >= 0) {
                GestureBinding(GestureAction.byId(raw.substring(0, sep)), raw.substring(sep + 1))
            } else {
                GestureBinding(GestureAction.byId(raw))
            }
        }
    }
}
