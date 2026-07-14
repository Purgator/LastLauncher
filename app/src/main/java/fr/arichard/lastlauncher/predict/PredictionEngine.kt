package fr.arichard.lastlauncher.predict

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Telephony
import android.util.Log
import fr.arichard.lastlauncher.notify.NotifListener
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

    // A learned candidate only takes a suggestion slot from the user's go-to apps
    // once its score is at least this (≈ one recent, contextually matching launch).
    private const val MIN_CONFIDENCE = 1.0

    // Per active notification (capped), so an app demanding attention floats up
    // without drowning real habits: 4+ notifications ≈ one recent matching launch.
    private const val W_NOTIFICATION = 0.35
    private const val NOTIFICATION_CAP = 4

    // User-boosted apps ("Boost in suggestions" in the long-press menu): learned score
    // is amplified and gets a floor, so a boosted app shows even with little history
    // yet still yields to genuinely stronger habits.
    private const val BOOST_FACTOR = 1.35
    private const val BOOST_BASE = 1.0

    // The app just opened is almost never wanted again right away — crush its score
    // for a while. Exception: within the first seconds (an accidental exit) it keeps
    // its full rank so reopening is one tap.
    private const val JUST_USED_FACTOR = 0.05
    private const val MISTAKE_WINDOW_MS = 15_000L
    private const val JUST_USED_WINDOW_MS = 45L * 60_000

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
        prefs.lastLaunchedTs = now
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
                score(appContext).toMutableMap()
            } catch (e: Exception) {
                Log.w(TAG, "Prediction failed", e)
                mutableMapOf()
            }
            val prefs = Prefs(appContext)
            val hidden = prefs.hiddenApps
            fun eligible(pkg: String) = pkg !in hidden

            // The just-opened app is crushed (it's one tap away in recents anyway) —
            // unless it was exited within seconds, i.e. probably by mistake.
            val prev = prefs.lastLaunchedPkg
            val sincePrev = System.currentTimeMillis() - prefs.lastLaunchedTs
            if (prev != null && sincePrev in MISTAKE_WINDOW_MS..JUST_USED_WINDOW_MS) {
                result[prev] = (result[prev] ?: 0.0) * JUST_USED_FACTOR
            }

            // Confident learned predictions first…
            val ranked = result.entries.asSequence()
                .filter { eligible(it.key) && it.value >= MIN_CONFIDENCE }
                .sortedByDescending { it.value }
                .map { it.key }
                .take(count)
                .toMutableList()
            // …then the user's go-to apps and sensible defaults while the memory is
            // young, and finally any low-confidence leftovers.
            val fillers = prefs.favorites + defaultPicks(appContext) +
                result.entries.sortedByDescending { it.value }.map { it.key }
            for (pkg in fillers) {
                if (ranked.size >= count) break
                if (pkg !in ranked && eligible(pkg)) ranked.add(pkg)
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
        // An app currently showing notifications is more likely to be wanted next.
        // Empty map without notification access, so this is a no-op until granted.
        for ((pkg, count) in NotifListener.counts) {
            if (count > 0) {
                scores.merge(pkg, W_NOTIFICATION * minOf(count, NOTIFICATION_CAP), Double::plus)
            }
        }
        for (pkg in Prefs(context).boostedApps) {
            scores[pkg] = (scores[pkg] ?: 0.0) * BOOST_FACTOR + BOOST_BASE
        }
        return scores
    }

    /** Sensible cold-start picks before any history exists: phone, SMS, browser, camera. */
    fun defaultPicks(context: Context): List<String> {
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

    /** Counts launches logged since local midnight; delivered on the main thread. */
    fun launchesToday(context: Context, callback: (Int) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val midnight = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val count = try {
                db(appContext).countSince(midnight)
            } catch (e: Exception) {
                0
            }
            mainHandler.post { callback(count) }
        }
    }

    /** Everything the insights screen shows about the engine's state. */
    data class Snapshot(
        val totalRows: Int,
        val daysCovered: Int,
        val dbBytes: Long,
        val launchesToday: Int,
        val hour: Int,
        val weekend: Boolean,
        val prevApp: String?,
        val activeTrigger: String?,
        val topScores: List<Pair<String, Double>>,
        val boosted: Set<String>,
        val notifying: Map<String, Int>,
    )

    /** Builds a live view of the engine's data and current ranking, off the UI thread. */
    fun snapshot(context: Context, callback: (Snapshot) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val snapshot = try {
                val database = db(appContext)
                val now = System.currentTimeMillis()
                val cal = Calendar.getInstance()
                val startOfDay = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val oldest = database.oldestTs()
                Snapshot(
                    totalRows = database.totalCount(),
                    daysCovered = oldest?.let { ((now - it) / DAY_MS).toInt() + 1 } ?: 0,
                    dbBytes = appContext.getDatabasePath("usage.db").length(),
                    launchesToday = database.countSince(startOfDay),
                    hour = cal.get(Calendar.HOUR_OF_DAY),
                    weekend = isWeekend(cal.get(Calendar.DAY_OF_WEEK)),
                    prevApp = Prefs(appContext).lastLaunchedPkg,
                    activeTrigger = ContextSignals.activeEvent(now),
                    topScores = score(appContext).entries
                        .sortedByDescending { it.value }
                        .take(10)
                        .map { it.key to it.value },
                    boosted = Prefs(appContext).boostedApps,
                    notifying = NotifListener.counts.filterValues { it > 0 },
                )
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot failed", e)
                Snapshot(0, 0, 0, 0, 0, false, null, null, emptyList(), emptySet(), emptyMap())
            }
            mainHandler.post { callback(snapshot) }
        }
    }

    /** The signal weights, exposed for the insights screen. */
    fun weights(): List<Pair<String, Double>> = listOf(
        "hour" to W_HOUR,
        "daytype" to W_DAY_TYPE,
        "transition" to W_TRANSITION,
        "trigger" to W_TRIGGER,
        "notification" to W_NOTIFICATION,
        "boost_factor" to BOOST_FACTOR,
    )

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
