package fr.arichard.lastlauncher.lock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Tiny accessibility service whose only job is performing the screen-lock and
 * notification-shade global actions. It never inspects window content
 * (canRetrieveWindowContent=false in its config).
 */
class LockService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LockService"

        @Volatile
        private var instance: LockService? = null

        val isRunning: Boolean get() = instance != null

        /** Locks the screen. Returns false if unsupported or the service is off. */
        fun lockScreen(): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            return instance?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) == true
        }

        /**
         * Opens the notification shade: through the service when available, else
         * through the (best-effort) StatusBarManager fallback.
         */
        fun openNotificationShade(context: Context): Boolean {
            instance?.let {
                if (it.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)) return true
            }
            return try {
                val sbm = context.getSystemService("statusbar")
                Class.forName("android.app.StatusBarManager")
                    .getMethod("expandNotificationsPanel")
                    .invoke(sbm)
                true
            } catch (e: Exception) {
                Log.d(TAG, "Shade fallback unavailable: ${e.message}")
                false
            }
        }

        /** Sends the user to the accessibility settings to enable the service. */
        fun openAccessibilitySettings(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not open accessibility settings", e)
            }
        }
    }
}
