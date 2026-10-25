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
    /** An uninstall sweep that would delete more packages than this is skipped. */
    private const val SWEEP_MAX_ABSENT = 10

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "predict") }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var db: UsageDb? = null
    private var insertsSincePrune = 0

    // Everything below is touched on the predict executor only (single thread).
    /** The scoring window's rows, oldest first, loaded from SQLite once per process
     *  and kept in step with every insert; null until the first scan or after
     *  anything that deletes rows (prune, sweep, clear) — the next scan reloads. */
    private var launchCache: ArrayList<UsageDb.Row>? = null
    private var missCache: ArrayList<UsageDb.Row>? = null
    private var housekept = false
    /** Insights: how long the last SQLite load and the last scoring pass took. */
    private var lastLoadMillis = -1L
    private var lastScanMillis = -1L
    private var lastScanRows = 0

    private fun db(context: Context): UsageDb {
        db?.let { return it }
        val created = synchronized(this) { db ?: UsageDb(context).also { db = it } }
        if (!housekept) {
            // Once per process, before the first read: the every-200-inserts
            // prune never fires in a process that dies early, and it never ran
            // at startup at all.
            housekept = true
            housekeep(context, created)
        }
        return created
    }

    /** Prune, sweep uninstalled apps, compact the file; drops the cache. */
    private fun housekeep(context: Context, database: UsageDb) {
        try {
            val pruned = database.prune(System.currentTimeMillis())
            val swept = sweepUninstalled(context, database)
            val compacted = database.compactIfFragmented()
            if (pruned > 0 || swept > 0 || compacted) {
                Log.i(TAG, "Housekeeping: pruned $pruned, swept $swept rows, vacuum=$compacted")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Housekeeping failed", e)
        }
        launchCache = null
        missCache = null
    }

    /**
     * Deletes the rows of apps that are no longer installed (an uninstall while
     * the launcher was dead never reached the package receiver). Belt: when an
     * implausible share of the known packages reads as absent, the package
     * manager is more likely wrong than the log — nothing is deleted then.
     */
    private fun sweepUninstalled(context: Context, database: UsageDb): Int {
        val pm = context.packageManager
        val known = database.knownPackages()
        val gone = known.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                false
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                true
            } catch (e: Exception) {
                false
            }
        }
        if (gone.isEmpty()) return 0
        if (gone.size > SWEEP_MAX_ABSENT) {
            Log.w(TAG, "Sweep skipped: ${gone.size} of ${known.size} packages read as absent")
            return 0
        }
        var deleted = 0
        for (pkg in gone) deleted += database.deletePackage(pkg)
        return deleted
    }

    /** The package receiver's notice that [pkg] was fully uninstalled. */
    fun forgetPackage(context: Context, pkg: String) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                val database = db(appContext)
                // Re-check: the broadcast is per user; a package still installed for
                // this user (or merely updated) keeps its history.
                val stillInstalled = try {
                    appContext.packageManager.getPackageInfo(pkg, 0); true
                } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                    false
                }
                if (stillInstalled) return@execute
                val deleted = database.deletePackage(pkg)
                if (deleted > 0) {
                    launchCache = null
                    missCache = null
                    Log.i(TAG, "Forgot $deleted rows of uninstalled $pkg")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not forget $pkg", e)
            }
        }
    }

    /** Appends a freshly inserted launch row to the cache, if one is loaded. */
    private fun cacheLaunch(row: UsageDb.Row) {
        val cache = launchCache ?: return
        cache.add(row.copy(state = null))
        if (cache.size > 1 && cache[cache.size - 2].ts > row.ts) cache.sortBy { it.ts }
    }

    private fun cacheMiss(row: UsageDb.Row) {
        val cache = missCache ?: return
        cache.add(row.copy(state = null))
        if (cache.size > 1 && cache[cache.size - 2].ts > row.ts) cache.sortBy { it.ts }
    }

    /**
     * The scoring window's launch and miss rows: from the cache when loaded,
     * else from SQLite (one read per process in practice). Expired rows fall
     * off the front — they are sorted by timestamp — so the lists stay exactly
     * what `rowsSince(horizon)` would return.
     */
    private fun windowRows(context: Context, horizon: Long): Pair<List<UsageDb.Row>, List<UsageDb.Row>> {
        var launches = launchCache
        var misses = missCache
        if (launches == null || misses == null) {
            val database = db(context) // may housekeep on first open — before the clock starts
            val t0 = android.os.SystemClock.elapsedRealtime()
            launches = ArrayList(database.rowsSince(horizon, withState = false))
            misses = ArrayList(database.missesSince(horizon, withState = false))
            intern(launches)
            intern(misses)
            launchCache = launches
            missCache = misses
            lastLoadMillis = android.os.SystemClock.elapsedRealtime() - t0
        } else {
            expire(launches, horizon)
            expire(misses, horizon)
        }
        return launches to misses
    }

    private fun expire(rows: ArrayList<UsageDb.Row>, horizon: Long) {
        var n = 0
        while (n < rows.size && rows[n].ts < horizon) n++
        if (n > 0) rows.subList(0, n).clear()
    }

    /** Shares the handful of distinct package/event strings across thousands of rows. */
    private fun intern(rows: ArrayList<UsageDb.Row>) {
        val pool = HashMap<String, String>(128)
        fun s(v: String) = pool.getOrPut(v) { v }
        for (i in rows.indices) {
            val r = rows[i]
            rows[i] = r.copy(
                pkg = s(r.pkg),
                prevPkg = r.prevPkg?.let { s(it) },
                ctxEvent = r.ctxEvent?.let { s(it) },
            )
        }
    }

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
                val row = UsageDb.Row(
                    pkg = pkg,
                    ts = now,
                    hour = cal.get(Calendar.HOUR_OF_DAY),
                    dow = cal.get(Calendar.DAY_OF_WEEK),
                    prevPkg = prev?.takeIf { it != pkg },
                    ctxEvent = event,
                    state = AmbientState.snapshot(appContext, now),
                )
                database.insertLaunch(row)
                cacheLaunch(row)
                if (++insertsSincePrune >= 200) {
                    insertsSincePrune = 0
                    housekeep(appContext, database)
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
            val scoring = try {
                score(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "Prediction failed", e)
                Scoring(emptyMap(), emptyMap(), SatiationTracker())
            }
            val result = scoring.scores.toMutableMap()
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

            // Confident learned predictions first — the trio is their plain top,
            // the swipe pages behind it favour apps concentrated on this hour…
            val confident = result.entries.asSequence()
                .filter { eligible(it.key) && it.value >= MIN_CONFIDENCE }
                .sortedByDescending { it.value }
                .map { it.key }
                .toList()
            val ranked = ScoreMath.rerankPages(confident, result) { scoring.concentration[it] ?: 1.0 }
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

    /** Everything one scan of the log yields: the scores plus the signals that re-shape them. */
    class Scoring(
        val scores: Map<String, Double>,
        /** Per app, [ScoreMath.hourConcentration] for the current hour (absent = 1.0). */
        val concentration: Map<String, Double>,
        val satiation: SatiationTracker,
    )

    private fun score(context: Context): Scoring {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val hourNow = cal.get(Calendar.HOUR_OF_DAY)
        val weekendNow = isWeekend(cal.get(Calendar.DAY_OF_WEEK))
        val prev = Prefs(context).lastLaunchedPkg
        val activeEvent = ContextSignals.activeEvent(now)

        val t0 = android.os.SystemClock.elapsedRealtime()
        val (rows, missRows) = windowRows(context, now - HISTORY_DAYS * DAY_MS)
        val scores = HashMap<String, Double>(64)
        val mass = HashMap<String, DoubleArray>(64) // per app: [decayed launches, of which within ±1 h]
        val satiation = SatiationTracker()
        for (r in rows) {
            val s = ScoreMath.rowScore(
                r.ts, r.hour, r.dow, r.prevPkg, r.ctxEvent,
                now, hourNow, weekendNow, prev, activeEvent,
            )
            scores.merge(r.pkg, s, Double::plus)
            val w = ScoreMath.decayWeight(r.ts, now)
            val m = mass.getOrPut(r.pkg) { DoubleArray(2) }
            m[0] += w
            if (ScoreMath.circularHourDiff(r.hour, hourNow) <= 1) m[1] += w
            satiation.add(r.pkg, r.ts)
        }
        // Corrections: a suggested-but-swiped-away app counts as a negative launch,
        // matched and decayed exactly like the positive ones — strongest when the
        // context repeats, forgotten on the same horizon as everything else.
        // (Miss rows carry no prevPkg, so the transition term never fires.)
        for (r in missRows) {
            val s = ScoreMath.rowScore(
                r.ts, r.hour, r.dow, r.prevPkg, r.ctxEvent,
                now, hourNow, weekendNow, prev, activeEvent,
            )
            scores.merge(r.pkg, -W_MISS * s, Double::plus)
        }
        // "Once a day" apps opened in the last 12 h are damped — history-derived
        // score only, never the notification/boost terms below.
        for (pkg in satiation.satiated(now)) {
            scores[pkg]?.let { scores[pkg] = it * ScoreMath.SATIATION_FACTOR }
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
        val concentration = HashMap<String, Double>(mass.size)
        for ((pkg, m) in mass) concentration[pkg] = ScoreMath.hourConcentration(m[0], m[1])
        lastScanRows = rows.size + missRows.size
        lastScanMillis = android.os.SystemClock.elapsedRealtime() - t0
        return Scoring(scores, concentration, satiation)
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
                val state = AmbientState.snapshot(appContext, now)
                for (pkg in shownPkgs) {
                    if (pkg == launchedPkg) continue
                    val miss = UsageDb.Row(pkg, now, hour, dow, prevPkg = null, ctxEvent = event, state = state)
                    database.insertMiss(miss)
                    cacheMiss(miss)
                }
                val row = UsageDb.Row(launchedPkg, now, hour, dow, prevPkg = null, ctxEvent = event, state = state)
                database.insertLaunch(row)
                cacheLaunch(row)
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
        /** Rows stamped with an ambient-state snapshot (schema 3+). */
        val stateRows: Int = 0,
        /** The ambient state as it reads right now (see [AmbientState]). */
        val ambient: String? = null,
        /** Apps damped by once-a-day satiation right now, with minutes since their launch. */
        val satiated: List<Pair<String, Long>> = emptyList(),
        /** Apps whose launches concentrate on this hour (factor > 1), sorting the swipe pages. */
        val concentration: Map<String, Double> = emptyMap(),
        /** Rows the scoring pass walked (launches + misses in the window). */
        val scanRows: Int = 0,
        /** Milliseconds of the scoring pass itself, rows already in memory. */
        val scanMillis: Long = -1,
        /** Milliseconds of the last SQLite read that filled the row cache. */
        val loadMillis: Long = -1,
        /** SQLite free pages (reclaimed by VACUUM once past the threshold). */
        val freePages: Long = 0,
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
                val scoring = score(appContext)
                Snapshot(
                    totalRows = database.totalCount(),
                    daysCovered = oldest?.let { ((now - it) / DAY_MS).toInt() + 1 } ?: 0,
                    dbBytes = appContext.getDatabasePath("usage.db").length(),
                    launchesToday = database.countSince(startOfDay),
                    hour = cal.get(Calendar.HOUR_OF_DAY),
                    weekend = isWeekend(cal.get(Calendar.DAY_OF_WEEK)),
                    prevApp = Prefs(appContext).lastLaunchedPkg,
                    activeTrigger = ContextSignals.activeEvent(now),
                    topScores = scoring.scores.entries
                        .sortedByDescending { it.value }
                        .take(10)
                        .map { it.key to it.value },
                    boosted = Prefs(appContext).boostedApps,
                    notifying = NotifListener.counts.filterValues { it > 0 },
                    totalMisses = database.totalMissCount(),
                    stateRows = database.countWithState(),
                    ambient = AmbientState.snapshot(appContext, now),
                    satiated = scoring.satiation.satiated(now).map {
                        it to (scoring.satiation.sinceLast(it, now) ?: 0L) / 60_000
                    },
                    concentration = scoring.concentration.filterValues { it > 1.05 },
                    scanRows = lastScanRows,
                    scanMillis = lastScanMillis,
                    loadMillis = lastLoadMillis,
                    freePages = database.freePages(),
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
        "satiation_factor" to ScoreMath.SATIATION_FACTOR,
        "concentration_cap" to ScoreMath.CONCENTRATION_CAP,
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
            launchCache = null
            missCache = null
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
                    // Ambient state as an object on schema-3 rows, null before.
                    put(
                        "state",
                        r.state?.let { org.json.JSONObject(AmbientState.parse(it)) }
                            ?: org.json.JSONObject.NULL
                    )
                }
                val root = org.json.JSONObject()
                root.put("schema", 3)
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
                        score(appContext).scores.entries
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
