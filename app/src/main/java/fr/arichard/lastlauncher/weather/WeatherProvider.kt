package fr.arichard.lastlauncher.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import fr.arichard.lastlauncher.settings.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Minimal, key-less weather via Open-Meteo (open-meteo.com). Uses the phone's last known
 * coarse location — no continuous location requests, no tracking — and caches the result
 * so the network is touched at most once an hour. Everything is opt-in and off by default.
 */
object WeatherProvider {

    private const val TAG = "WeatherProvider"
    private const val REFRESH_MS = 60L * 60 * 1000
    private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"

    /** A resolved snapshot. [tempC] is Celsius; the UI converts for display. */
    data class Weather(val tempC: Double, val code: Int)

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "weather") }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Last cached snapshot (may be stale), or null if never fetched. */
    fun cached(context: Context): Weather? {
        val prefs = Prefs(context)
        if (!prefs.weatherHasCache) return null
        return Weather(prefs.weatherTempC, prefs.weatherCode)
    }

    /**
     * Delivers weather on the main thread: the cache immediately if present, then a fresh
     * value when a refresh is warranted and succeeds. No-ops without permission.
     */
    fun request(context: Context, onResult: (Weather?) -> Unit) {
        val appContext = context.applicationContext
        val prefs = Prefs(appContext)
        cached(appContext)?.let(onResult)
        if (!hasLocationPermission(appContext)) {
            if (!prefs.weatherHasCache) onResult(null)
            return
        }
        val fresh = System.currentTimeMillis() - prefs.weatherLastFetch < REFRESH_MS
        if (fresh && prefs.weatherHasCache) return
        executor.execute {
            val weather = try {
                fetch(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Weather fetch failed: ${e.message}")
                null
            }
            if (weather != null) {
                prefs.saveWeather(weather.tempC, weather.code)
                mainHandler.post { onResult(weather) }
            }
        }
    }

    private fun fetch(context: Context): Weather? {
        val loc = lastKnownLocation(context) ?: return null
        val url = "$ENDPOINT?latitude=${loc.first}&longitude=${loc.second}" +
            "&current=temperature_2m,weather_code"
        val json = JSONObject(httpGet(url))
        val current = json.optJSONObject("current") ?: return null
        val temp = current.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return null
        return Weather(temp, current.optInt("weather_code", 0))
    }

    /** Best last-known coarse location across providers, as (lat, lon). */
    private fun lastKnownLocation(context: Context): Pair<Double, Double>? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            lm.getProviders(true)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { it.latitude to it.longitude }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "LastLauncher")
            if (connection.responseCode != 200) throw Exception("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Open-Meteo WMO weather code → a compact emoji glyph. */
    fun glyph(code: Int): String = when (code) {
        0 -> "☀"
        1, 2 -> "⛅"
        3 -> "☁"
        in 45..48 -> "🌫"
        in 51..67 -> "🌧"
        in 71..77 -> "❄"
        in 80..82 -> "🌦"
        in 85..86 -> "❄"
        in 95..99 -> "⛈"
        else -> "☁"
    }
}
