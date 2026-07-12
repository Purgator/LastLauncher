package fr.arichard.lastlauncher.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Reads active notifications to power the icon count bubbles and the home-screen
 * message ticker. Purely local: nothing is stored or sent anywhere; state lives in
 * memory and empties when the service disconnects. The user grants access explicitly
 * from the system's notification-access screen.
 */
class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        recompute()
    }

    override fun onListenerDisconnected() {
        connected = false
        counts = emptyMap()
        messages = emptyList()
        notifyChanged()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recompute()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recompute()

    private fun recompute() {
        try {
            val active = activeNotifications ?: return
            val newCounts = HashMap<String, Int>()
            val newMessages = ArrayList<Message>()
            for (sbn in active) {
                val n = sbn.notification ?: continue
                if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
                if (!sbn.isClearable) continue // skip ongoing: media, foreground services
                newCounts.merge(sbn.packageName, 1, Int::plus)
                extractMessage(sbn, n)?.let { newMessages.add(it) }
            }
            newMessages.sortByDescending { it.time }
            counts = newCounts
            messages = newMessages.take(MAX_MESSAGES)
            notifyChanged()
        } catch (e: Exception) {
            Log.w(TAG, "recompute failed", e)
        }
    }

    /** A ticker entry from a messaging-style notification (title = sender/thread). */
    private fun extractMessage(sbn: StatusBarNotification, n: Notification): Message? {
        val isMessage = n.category == Notification.CATEGORY_MESSAGE ||
            n.extras.containsKey(Notification.EXTRA_MESSAGES)
        if (!isMessage) return null
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (title.isNullOrBlank() || text.isNullOrBlank()) return null
        return Message(sbn.packageName, title.trim(), text.trim(), sbn.postTime)
    }

    companion object {
        private const val TAG = "NotifListener"
        private const val MAX_MESSAGES = 10

        data class Message(val pkg: String, val title: String, val text: String, val time: Long)

        @Volatile var connected: Boolean = false
            private set

        /** Active (clearable, non-summary) notification count per package. */
        @Volatile var counts: Map<String, Int> = emptyMap()
            private set

        /** Newest-first unread messages for the ticker. */
        @Volatile var messages: List<Message> = emptyList()
            private set

        private val listeners = CopyOnWriteArrayList<() -> Unit>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addListener(listener: () -> Unit) = listeners.add(listener)
        fun removeListener(listener: () -> Unit) = listeners.remove(listener)

        private fun notifyChanged() {
            mainHandler.post { listeners.forEach { it() } }
        }

        fun count(pkg: String): Int = counts[pkg] ?: 0

        /** True when the user has granted this app notification access. */
        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)

        /** Opens the system screen where notification access is granted. */
        fun requestAccess(context: Context) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not open notification access settings", e)
            }
        }

        /**
         * Nudges the system to (re)bind the service after access is granted, so badges
         * appear without a reboot. Safe no-op when access is missing.
         */
        fun ensureBound(context: Context) {
            if (connected || !hasAccess(context)) return
            try {
                requestRebind(ComponentName(context, NotifListener::class.java))
            } catch (e: Exception) {
                Log.d(TAG, "requestRebind unavailable: ${e.message}")
            }
        }
    }
}
