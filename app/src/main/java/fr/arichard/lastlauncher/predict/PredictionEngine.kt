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

    // All scoring constants and the per-row formula live in ScoreMath (pure,
    // unit-tested) so the walk-forward Backtester replays the exact same math.
    private const val DAY_MS = ScoreMath.DAY_MS
    private const val HISTORY_DAYS = ScoreMath.HISTORY_DAYS
    private const val MIN_CONFIDENCE = ScoreMath.MIN_CONFIDENCE
    private const val W_NOTIFICATION = ScoreMath.W_NOTIFICATION
    private const val NOTIFICATION_CAP = ScoreMath.NOTIFICATION_CAP
    private const val BOOST_FACTOR = ScoreMath.BOOST_FACTOR
    private const val BOOST_BASE = ScoreMath.BOOST_BASE
    private const val JUST_USED_FACTOR = ScoreMath.JUST_USED_FACTOR
    private const val MISTAKE_WINDOW_MS = ScoreMath.MISTAKE_WINDOW_MS
    private const val JUST_USED_WINDOW_MS = ScoreMath.JUST_USED_WINDOW_MS
    private const val W_MISS = ScoreMath.W_MISS

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
            val s = ScoreMath.rowScore(
                r.ts, r.hour, r.dow, r.prevPkg, r.ctxEvent,
                now, hourNow, weekendNow, prev, activeEvent,
            )
            scores.merge(r.pkg, s, Double::plus)
        }
        // Corrections: a suggested-but-swiped-away app counts as a negative launch,
        // matched and decayed exactly like the positive ones — strongest when the
        // context repeats, forgotten on the same horizon as everything else.
        // (Miss rows carry no prevPkg, so the transition term never fires.)
        for (r in db(context).missesSince(now - HISTORY_DAYS * DAY_MS)) {
            val s = ScoreMath.rowScore(
                r.ts, r.hour, r.dow, r.prevPkg, r.ctxEvent,
                now, hourNow, weekendNow, prev, activeEvent,
            )
            scores.merge(r.pkg, -W_MISS * s, Double::plus)
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

    /**
     * Records the corrected-trio feedback: the shown [shownPkgs] were swiped away
     * and [launchedPkg] opened within seconds. Each shown app takes a context-
     * stamped miss row, and the launched app gets one extra reinforcement row —
     * reaching past the proposal is stronger evidence than an ordinary launch.
     * Play-swipes (no launch after) must NOT be reported — the host enforces the
     * timing rule.
     */
    fun logTrioCorrection(context: Context, shownPkgs: Collection<String>, launchedPkg: String) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val event = ContextSignals.activeEvent(now)
        executor.execute {
            try {
                val database = db(appContext)
                for (pkg in shownPkgs) {
                    if (pkg == launchedPkg) continue
                    database.insertMiss(
                        UsageDb.Row(pkg, now, hour, dow, prevPkg = null, ctxEvent = event)
                    )
                }
                database.insertLaunch(
                    UsageDb.Row(launchedPkg, now, hour, dow, prevPkg = null, ctxEvent = event)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not log trio correction", e)
            }
        }
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
        val totalMisses: Int = 0,
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
                    totalMisses = database.totalMissCount(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Snapshot failed", e)
                Snapshot(0, 0, 0, 0, 0, false, null, null, emptyList(), emptySet(), emptyMap())
            }
            mainHandler.post { callback(snapshot) }
        }
    }

    /**
     * Replays the whole usage log through [Backtester] on the predict executor
     * and delivers the report (null when history is too short) on main. The
     * replay uses the exact live scoring math via [ScoreMath]; notification
     * and boost signals are excluded (no historical snapshots exist).
     */
    fun backtest(context: Context, callback: (Backtester.Report?) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val report = try {
                val database = db(appContext)
                val prefs = Prefs(appContext)
                Backtester.run(
                    launches = database.rowsSince(0),
                    misses = database.missesSince(0),
                    favorites = prefs.favorites,
                    hidden = prefs.hiddenApps,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Backtest failed", e)
                null
            }
            mainHandler.post { callback(report) }
        }
    }

    /** The signal weights, exposed for the insights screen. */
    fun weights(): List<Pair<String, Double>> = listOf(
        "hour" to ScoreMath.W_HOUR,
        "daytype" to ScoreMath.W_DAY_TYPE,
        "transition" to ScoreMath.W_TRANSITION,
        "trigger" to ScoreMath.W_TRIGGER,
        "notification" to W_NOTIFICATION,
        "miss" to W_MISS,
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

    /**
     * Dumps the engine's whole world to a pretty-printed JSON file in the export
     * cache dir (shared via FileProvider), for the owner to inspect or send off
     * for analysis: every launch and miss row with its context, the signal
     * weights, user tuning (boosts, favorites), and the ranking as computed right
     * now. Nothing is sent anywhere by the app itself — the user shares the file.
     */
    fun exportData(context: Context, callback: (java.io.File?) -> Unit) {
        val appContext = context.applicationContext
        executor.execute {
            val file = try {
                val database = db(appContext)
                val prefs = Prefs(appContext)
                fun rowJson(r: UsageDb.Row) = org.json.JSONObject().apply {
                    put("pkg", r.pkg)
                    put("ts", r.ts)
                    put("hour", r.hour)
                    put("dow", r.dow)
                    put("prev", r.prevPkg ?: org.json.JSONObject.NULL)
                    put("ctx", r.ctxEvent ?: org.json.JSONObject.NULL)
                }
                val root = org.json.JSONObject()
                root.put("schema", 1)
                root.put("app_version", fr.arichard.lastlauncher.BuildConfig.VERSION_NAME)
                root.put("exported_at", System.currentTimeMillis())
                root.put("timezone", java.util.TimeZone.getDefault().id)
                root.put("weights", org.json.JSONObject(weights().toMap()))
                root.put(
                    "launches",
                    org.json.JSONArray().also { arr ->
                        database.rowsSince(0).forEach { arr.put(rowJson(it)) }
                    }
                )
                root.put(
                    "misses",
                    org.json.JSONArray().also { arr ->
                        database.missesSince(0).forEach { arr.put(rowJson(it)) }
                    }
                )
                root.put("boosted", org.json.JSONArray(prefs.boostedApps.toList()))
                root.put("favorites", org.json.JSONArray(prefs.favorites))
                root.put("hidden_count", prefs.hiddenApps.size)
                root.put(
                    "settings",
                    org.json.JSONObject()
                        .put("predictions", prefs.predictions)
                        .put("bt_signal", prefs.btSignal)
                        .put("ssid_signal", prefs.ssidSignal)
                )
                root.put(
                    "current_ranking",
                    org.json.JSONArray().also { arr ->
                        score(appContext).entries
                            .sortedByDescending { it.value }
                            .take(20)
                            .forEach {
                                arr.put(
                                    org.json.JSONObject()
                                        .put("pkg", it.key)
                                        .put("score", it.value)
                                )
                            }
                    }
                )
                val dir = java.io.File(appContext.cacheDir, "export").apply { mkdirs() }
                java.io.File(dir, "lastlauncher-brain.json").apply {
                    writeText(root.toString(2))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Export failed", e)
                null
            }
            mainHandler.post { callback(file) }
        }
    }

    private fun isWeekend(dow: Int): Boolean = ScoreMath.isWeekend(dow)
}
