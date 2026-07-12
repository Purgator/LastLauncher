package fr.arichard.lastlauncher.gesture

import fr.arichard.lastlauncher.R

/**
 * An action bindable to an edge swipe. The four slots are the one- and two-finger
 * horizontal swipes in each direction — in the launcher's CLI notation:
 *  `>`  left→right, one finger      `>>` left→right, two fingers
 *  `<`  right→left, one finger      `<<` right→left, two fingers
 */
enum class GestureAction(val id: String, val labelRes: Int) {
    NONE("none", R.string.gesture_none),
    NOTIFICATIONS("notifications", R.string.cmd_notifications),
    QUICK_SETTINGS("quick_settings", R.string.cmd_quick_settings),
    LOCK("lock", R.string.cmd_lock),
    FLASHLIGHT("flashlight", R.string.cmd_flashlight),
    ASSISTANT("assistant", R.string.cmd_assistant),
    ALL_APPS("all_apps", R.string.cmd_all_apps),
    APP_DRAWER("app_drawer", R.string.gesture_app_drawer),
    SEARCH("search", R.string.gesture_search),
    CAMERA("camera", R.string.gesture_camera),
    DIALER("dialer", R.string.gesture_dialer),
    OPEN_APP("open_app", R.string.gesture_open_app);

    companion object {
        fun byId(id: String): GestureAction =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * A resolved gesture binding: an action plus, for [GestureAction.OPEN_APP], the target
 * app's component key. Serialised to a single preference string.
 */
data class GestureBinding(val action: GestureAction, val appKey: String? = null) {

    fun encode(): String =
        if (action == GestureAction.OPEN_APP && appKey != null) "${action.id}:$appKey"
        else action.id

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
