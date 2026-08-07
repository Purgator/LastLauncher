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
    const val EVENT_WIFI_ON = "wifi_joined"
    const val EVENT_WIFI_OFF = "wifi_left"
    const val EVENT_ONLINE = "back_online"
    const val EVENT_OFFLINE = "went_offline"
    const val EVENT_MOTION = "started_moving"

    private const val TAG = "ContextSignals"
    private const val EVENT_WINDOW_MS = 5 * 60_000L

    // Network callbacks fire once immediately on registration: those describe the
    // state at process start, not a change — ignore the first seconds.
    private const val STARTUP_GRACE_MS = 5_000L
    @Volatile private var registeredAt = 0L

    @Volatile private var lastEventType: String? = null
    @Volatile private var lastEventTs: Long = 0
    @Volatile private var appContext: Context? = null

    /** The trigger event currently in effect, or null. */
    fun activeEvent(now: Long = System.currentTimeMillis()): String? =
        lastEventType?.takeIf { now - lastEventTs <= EVENT_WINDOW_MS }

    private fun recordEvent(type: String) {
        lastEventType = type
        lastEventTs = System.currentTimeMillis()
        Log.d(TAG, "Trigger event: $type")
    }

    private fun recordEventGuarded(type: String) {
        if (System.currentTimeMillis() - registeredAt > STARTUP_GRACE_MS) recordEvent(type)
    }

    /**
     * Registers runtime receivers. The launcher process lives as long as the home
     * screen exists, so these follow the app's natural lifetime. Receiving Bluetooth
     * ACL events on Android 12+ additionally requires BLUETOOTH_CONNECT, requested
     * from the home screen; without it the other signals still work.
     */
    fun register(context: Context) {
        appContext = context.applicationContext
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
        registeredAt = System.currentTimeMillis()
        registerNetworkSignals(context)
        registerMotionSignal(context)
    }

    /**
     * Wi-Fi joins/leaves stand in for places (home, office, gym); with the
     * ssid_signal option and precise-location permission, the joined network's
     * name is HASHED (SHA-256 with a random device-local salt, 12 hex chars) so
     * the engine can tell places apart while the database never holds a readable
     * network name — and nothing outside this app can reproduce the hash. Losing
     * or regaining ANY connectivity is context too. Push-based — nothing polls.
     */
    private fun registerNetworkSignals(context: Context) {
        try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                ?: return
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                wifiCallback()
            )
            cm.registerDefaultNetworkCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    @Volatile private var hadNetwork = false

                    override fun onAvailable(network: android.net.Network) {
                        if (!hadNetwork) recordEventGuarded(EVENT_ONLINE)
                        hadNetwork = true
                    }

                    override fun onLost(network: android.net.Network) {
                        hadNetwork = false
                        recordEventGuarded(EVENT_OFFLINE)
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network signals", e)
        }
    }

    /** The Wi-Fi transport callback; on 31+ it carries WifiInfo for the SSID. */
    private fun wifiCallback(): android.net.ConnectivityManager.NetworkCallback =
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            object : android.net.ConnectivityManager.NetworkCallback(
                FLAG_INCLUDE_LOCATION_INFO
            ) {
                // The SSID arrives in onCapabilitiesChanged, after onAvailable.
                @Volatile private var pendingJoin = false

                override fun onAvailable(network: android.net.Network) {
                    pendingJoin = true
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities,
                ) {
                    if (pendingJoin) {
                        pendingJoin = false
                        recordEventGuarded(wifiJoinEvent(caps))
                    }
                }

                override fun onLost(network: android.net.Network) =
                    recordEventGuarded(EVENT_WIFI_OFF)
            }
        } else {
            object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) =
                    recordEventGuarded(wifiJoinEvent(null))

                override fun onLost(network: android.net.Network) =
                    recordEventGuarded(EVENT_WIFI_OFF)
            }
        }

    /**
     * The event for a Wi-Fi join: "wifi:<hash>" when the network name is
     * readable (option on + precise location granted), else the generic join.
     */
    private fun wifiJoinEvent(caps: android.net.NetworkCapabilities?): String {
        val ctx = appContext ?: return EVENT_WIFI_ON
        val prefs = fr.arichard.lastlauncher.settings.Prefs(ctx)
        if (!prefs.ssidSignal) return EVENT_WIFI_ON
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return EVENT_WIFI_ON
        }
        val ssid = try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                (caps?.transportInfo as? android.net.wifi.WifiInfo)?.ssid
            } else {
                @Suppress("DEPRECATION")
                (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as? android.net.wifi.WifiManager)?.connectionInfo?.ssid
            }
        } catch (e: Exception) {
            null
        }
        val name = ssid?.trim('"')?.trim()
            ?.takeIf { it.isNotEmpty() && !it.contains("unknown ssid", ignoreCase = true) }
            ?: return EVENT_WIFI_ON
        return "wifi:" + sha256Prefix(prefs.ssidSalt + name)
    }

    /** First 12 hex chars of SHA-256 — opaque but stable place token. */
    private fun sha256Prefix(input: String): String = try {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(java.util.Locale.US, it) }
            .take(12)
    } catch (e: Exception) {
        "opaque"
    }

    // NetworkCallback.FLAG_INCLUDE_LOCATION_INFO, referenced directly to avoid a
    // hard API-31 symbol on older toolchain paths.
    private const val FLAG_INCLUDE_LOCATION_INFO = 1

    /**
     * "Started moving" via the significant-motion sensor: a hardware one-shot
     * designed for ultra-low power (no continuous accelerometer listening, per
     * the battery rule). Re-armed after a rest so a walk isn't logged twice a
     * minute.
     */
    private fun registerMotionSignal(context: Context) {
        try {
            val sm = context.getSystemService(android.hardware.SensorManager::class.java)
                ?: return
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_SIGNIFICANT_MOTION)
                ?: return
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val listener = object : android.hardware.TriggerEventListener() {
                override fun onTrigger(event: android.hardware.TriggerEvent?) {
                    recordEvent(EVENT_MOTION)
                    handler.postDelayed({
                        try {
                            sm.requestTriggerSensor(this, sensor)
                        } catch (e: Exception) {
                            // sensor gone; give up quietly
                        }
                    }, MOTION_REARM_MS)
                }
            }
            sm.requestTriggerSensor(listener, sensor)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register motion signal", e)
        }
    }

    private const val MOTION_REARM_MS = 10 * 60_000L

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
