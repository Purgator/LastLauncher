package fr.arichard.lastlauncher.predict

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * Tracks the phone's ambient context — Bluetooth connections, wired headphones,
 * charging. A "trigger event" (e.g. car Bluetooth just connected) stays active for a
 * few minutes; launches during that window are tagged with it, and the prediction
 * engine later boosts apps that historically follow the same trigger.
 */
object ContextSignals {

    const val EVENT_BT_CONNECTED = "bt_connected"
    const val EVENT_HEADSET_PLUGGED = "headset_plugged"
    const val EVENT_POWER_CONNECTED = "power_connected"

    private const val TAG = "ContextSignals"
    private const val EVENT_WINDOW_MS = 5 * 60_000L

    @Volatile private var lastEventType: String? = null
    @Volatile private var lastEventTs: Long = 0

    /** The trigger event currently in effect, or null. */
    fun activeEvent(now: Long = System.currentTimeMillis()): String? =
        lastEventType?.takeIf { now - lastEventTs <= EVENT_WINDOW_MS }

    private fun recordEvent(type: String) {
        lastEventType = type
        lastEventTs = System.currentTimeMillis()
        Log.d(TAG, "Trigger event: $type")
    }

    /**
     * Registers runtime receivers. The launcher process lives as long as the home
     * screen exists, so these follow the app's natural lifetime. Receiving Bluetooth
     * ACL events on Android 12+ additionally requires BLUETOOTH_CONNECT, requested
     * from the home screen; without it the other signals still work.
     */
    fun register(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> recordEvent(EVENT_BT_CONNECTED)
                    Intent.ACTION_POWER_CONNECTED -> recordEvent(EVENT_POWER_CONNECTED)
                    Intent.ACTION_HEADSET_PLUG -> {
                        // 1 = plugged; the sticky initial broadcast is ignored on register
                        // because isInitialStickyBroadcast is true.
                        if (!isInitialStickyBroadcast && intent.getIntExtra("state", 0) == 1) {
                            recordEvent(EVENT_HEADSET_PLUGGED)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        try {
            context.registerReceiver(receiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register context receivers", e)
        }
    }

    /** True while the phone is charging; adds a small ranking signal. */
    fun isCharging(context: Context): Boolean = try {
        val batt = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    } catch (e: Exception) {
        false
    }
}
