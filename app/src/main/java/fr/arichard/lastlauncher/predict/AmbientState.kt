package fr.arichard.lastlauncher.predict

import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * A compact snapshot of the phone's *state* (as opposed to the trigger *events*
 * [ContextSignals] tracks) stamped on every launch and miss row, so the brain
 * export can later tell whether slow-changing situations — Do Not Disturb /
 * bedtime, on the charger, home Wi-Fi, an alarm set for the morning, earbuds
 * connected — predict apps the 5-minute trigger window structurally misses
 * (the "last video before bed" kind). Collected for OFFLINE analysis only: the
 * scoring ignores it until a backtest earns it a weight.
 *
 * Stored as one `key=value` string (`dnd=2,chg=1,alarm=372,wifi=1,bt=0`); the
 * pure [format]/[parse] pair is unit-tested, [snapshot] is the Android side.
 * Every read is a cheap binder call on the predict executor — no receivers,
 * no sensors, nothing that costs battery while the phone is idle.
 *
 * Keys:
 *  - `dnd`   interruption filter: 1 all, 2 priority only, 3 total silence, 4 alarms only
 *  - `chg`   1 while charging or full, else 0
 *  - `alarm` minutes until the next alarm clock, only when it is within 24 h
 *  - `wifi`  1 when the active network is Wi-Fi, else 0
 *  - `bt`    1 when a headset/A2DP audio device is connected (absent without
 *            the Bluetooth permission)
 */
object AmbientState {

    private const val TAG = "AmbientState"
    private const val ALARM_HORIZON_MS = 24L * 60 * 60 * 1000

    const val KEY_DND = "dnd"
    const val KEY_CHARGING = "chg"
    const val KEY_ALARM_MIN = "alarm"
    const val KEY_WIFI = "wifi"
    const val KEY_BT = "bt"

    /** `k=v,k=v` with keys in insertion order; null for an empty map. */
    fun format(values: Map<String, Int>): String? =
        if (values.isEmpty()) null
        else values.entries.joinToString(",") { "${it.key}=${it.value}" }

    /** Inverse of [format]; malformed fragments are skipped, never thrown. */
    fun parse(state: String?): Map<String, Int> {
        if (state.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (part in state.split(',')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val value = part.substring(eq + 1).toIntOrNull() ?: continue
            out[part.substring(0, eq)] = value
        }
        return out
    }

    /**
     * Whether a headset / A2DP audio device is connected; null when it cannot be
     * known (no BLUETOOTH_CONNECT grant, no adapter). Binder calls — off the UI thread.
     */
    fun btAudioState(context: Context): Boolean? = try {
        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.let { adapter ->
                val connected = BluetoothAdapter.STATE_CONNECTED
                adapter.getProfileConnectionState(BluetoothProfile.A2DP) == connected ||
                    adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == connected
            }
        } else null
    } catch (e: SecurityException) {
        Log.d(TAG, "bluetooth state not permitted", e)
        null
    } catch (e: Exception) {
        Log.d(TAG, "bluetooth unavailable", e)
        null
    }

    fun btAudioConnected(context: Context): Boolean = btAudioState(context) == true

    /** Reads the current state; call off the UI thread. Never throws. */
    fun snapshot(context: Context, now: Long = System.currentTimeMillis()): String? {
        val values = LinkedHashMap<String, Int>()
        try {
            context.getSystemService(NotificationManager::class.java)
                ?.currentInterruptionFilter
                ?.takeIf { it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
                ?.let { values[KEY_DND] = it }
        } catch (e: Exception) {
            Log.d(TAG, "dnd unavailable", e)
        }
        values[KEY_CHARGING] = if (ContextSignals.isCharging(context)) 1 else 0
        try {
            val trigger = context.getSystemService(AlarmManager::class.java)
                ?.nextAlarmClock?.triggerTime
            if (trigger != null && trigger >= now && trigger - now <= ALARM_HORIZON_MS) {
                values[KEY_ALARM_MIN] = ((trigger - now) / 60_000L).toInt()
            }
        } catch (e: Exception) {
            Log.d(TAG, "alarm unavailable", e)
        }
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            values[KEY_WIFI] =
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 1 else 0
        } catch (e: Exception) {
            Log.d(TAG, "network unavailable", e)
        }
        btAudioState(context)?.let { values[KEY_BT] = if (it) 1 else 0 }
        return format(values)
    }
}
