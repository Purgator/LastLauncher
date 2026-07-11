package fr.arichard.lastlauncher.predict

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import fr.arichard.lastlauncher.settings.Prefs
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.exp

/**
 * Learns when the user opens which app and predicts the next one.
 *
 * Every launch is scored over the last 60 days with a blend of signals:
 *  - recency-weighted frequency (habits fade with an exponential decay),
 *  - time-of-day affinity (same hour ±1),
 *  - weekday/weekend rhythm,
 *  - app-to-app transitions ("after WhatsApp I open Spotify"),
 *  - context triggers (Bluetooth connected, headphones plugged, charger attached).
 *
 * Everything is computed on-device from a small SQLite table; no data ever leaves
 * the phone.
 */
object PredictionEngine {

    private const val TAG = "PredictionEngine"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val HISTORY_DAYS = 60L
    private const val DECAY_DAYS = 20.0

    // Signal weights, tuned so a strong contextual habit beats raw frequency.
    private const val W_HOUR = 1.2
    private const val W_DAY_TYPE = 0.3
    private const val W_TRANSITION = 2.5
    private const val W_TRIGGER = 3.5

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "predict") }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var db: UsageDb? = null
    private var insertsSincePrune = 0

    private fun db(context: Context): UsageDb =
        db ?: synchronized(this) { db ?: UsageDb(context).also { db = it } }

    /** Records a launch with a full context snapshot. Safe to call from the UI thread. */
    fun logLaunch(context: Context, pkg: String) {
        val appContext = context.applicationContext
        val prefs = Prefs(appContext)
        val prev = prefs.lastLaunchedPkg
        prefs.lastLaunchedPkg = pkg
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val event = ContextSignals.activeEvent(now)
        executor.execute {
            try {
                val database = db(appContext)
                database.insertLaunch(
                    UsageDb.Row(
                        pkg = pkg,
                        ts = now,
                        hour = cal.get(Calendar.HOUR_OF_DAY),
                        dow = cal.get(Calendar.DAY_OF_WEEK),
                        prevPkg = prev?.takeIf { it != pkg },
                        ctxEvent = event,
                    )
                )
                if (++insertsSincePrune >= 200) {
                    insertsSincePrune = 0
                    database.prune()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not log launch", e)
            }
        }
    }

    /**
     * Computes the ranked prediction (best first) plus the raw score map used to
     * break search ties, then delivers both on the main thread.
     */
    fun computeSuggestions(
        context: Context,
        count: Int,
        callback: (ranked: List<String>, scores: Map<String, Double>) -> Unit,
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = try {
                score(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Prediction failed", e)
                emptyMap()
            }
            val prefs = Prefs(appContext)
            val hidden = prefs.hiddenApps
            val prev = prefs.lastLaunchedPkg
            val ranked = result.entries.asSequence()
                .filter { it.key !in hidden && it.key != prev }
                .sortedByDescending { it.value }
                .map { it.key }
                .take(count)
                .toMutableList()
            if (ranked.size < count) {
                for (pkg in fallbackDefaults(appContext)) {
                    if (ranked.size >= count) break
                    if (pkg !in ranked && pkg !in hidden && pkg != prev) ranked.add(pkg)
                }
            }
            mainHandler.post { callback(ranked, result) }
        }
    }

    private fun score(context: Context): Map<String, Double> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val hourNow = cal.get(Calendar.HOUR_OF_DAY)
        val weekendNow = isWeekend(cal.get(Calendar.DAY_OF_WEEK))
        val prev = Prefs(context).lastLaunchedPkg
        val activeEvent = ContextSignals.activeEvent(now)

        val rows = db(context).rowsSince(now - HISTORY_DAYS * DAY_MS)
        if (rows.isEmpty()) return emptyMap()

        val scores = HashMap<String, Double>(64)
        for (r in rows) {
            val ageDays = (now - r.ts).toDouble() / DAY_MS
            val w = exp(-ageDays / DECAY_DAYS)
            var s = w
            if (circularHourDiff(r.hour, hourNow) <= 1) s += W_HOUR * w
            if (isWeekend(r.dow) == weekendNow) s += W_DAY_TYPE * w
            if (prev != null && r.prevPkg == prev) s += W_TRANSITION * w
            if (activeEvent != null && r.ctxEvent == activeEvent) s += W_TRIGGER * w
            scores.merge(r.pkg, s, Double::plus)
        }
        return scores
    }

    /** Sensible cold-start picks before any history exists: phone, SMS, browser, camera. */
    private fun fallbackDefaults(context: Context): List<String> {
        val pm = context.packageManager
        val picks = LinkedHashSet<String>()
        fun resolve(intent: Intent) {
            try {
                pm.resolveActivity(intent, 0)?.activityInfo?.packageName
                    ?.takeIf { it != "android" }
                    ?.let { picks.add(it) }
            } catch (e: Exception) {
                // ignore
            }
        }
        resolve(Intent(Intent.ACTION_DIAL))
        try {
            Telephony.Sms.getDefaultSmsPackage(context)?.let { picks.add(it) }
        } catch (e: Exception) {
            // ignore
        }
        resolve(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
        resolve(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        return picks.toList()
    }

    /** Deletes the learned history (settings: "forget everything"). */
    fun clearHistory(context: Context, onDone: () -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                db(appContext).clearAll()
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear history", e)
            }
            Prefs(appContext).lastLaunchedPkg = null
            mainHandler.post(onDone)
        }
    }

    private fun isWeekend(dow: Int): Boolean =
        dow == Calendar.SATURDAY || dow == Calendar.SUNDAY

    private fun circularHourDiff(a: Int, b: Int): Int {
        val d = kotlin.math.abs(a - b)
        return minOf(d, 24 - d)
    }
}
