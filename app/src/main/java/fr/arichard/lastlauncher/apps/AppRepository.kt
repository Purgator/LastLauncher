package fr.arichard.lastlauncher.apps

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * In-memory catalog of every launchable app. Loaded once on startup, refreshed when
 * packages change; the UI thread only ever reads immutable snapshots, so the home
 * screen never blocks on the PackageManager.
 */
class AppRepository(private val context: Context) {

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    /** Prediction base scores (pkg -> score) used to rank equal search matches. */
    @Volatile
    var usageBoost: Map<String, Double> = emptyMap()

    private val iconCache = ConcurrentHashMap<String, Drawable>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-repo").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: () -> Unit) = listeners.add(listener)
    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** Reloads the app list in the background and notifies listeners on the main thread. */
    fun load() {
        executor.execute {
            try {
                reload()
            } catch (e: Exception) {
                Log.w(TAG, "App list load failed", e)
            }
        }
    }

    private fun reload() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val list = resolved.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            if (ai.packageName == context.packageName) return@mapNotNull null
            AppEntry(ai.packageName, ai.name, ri.loadLabel(pm).toString())
        }.distinctBy { it.componentKey }
            .sortedBy { it.normalizedLabel }
        apps = list
        iconCache.keys.retainAll(list.map { it.componentKey }.toSet())
        mainHandler.post { listeners.forEach { it() } }
        // Warm the icon cache so scrolling the list never hits the PackageManager.
        for (entry in list) {
            if (!iconCache.containsKey(entry.componentKey)) loadIcon(entry)
        }
        mainHandler.post { listeners.forEach { it() } }
    }

    /** Cached icon, or null if not warmed yet (an async load is then kicked off). */
    fun icon(entry: AppEntry): Drawable? {
        iconCache[entry.componentKey]?.let { return it }
        executor.execute { loadIcon(entry) }
        return null
    }

    /** Loads (and caches) an icon; call from the repo executor only. */
    private fun loadIcon(entry: AppEntry): Drawable? = try {
        val pm = context.packageManager
        val icon = pm.getActivityIcon(
            android.content.ComponentName(entry.packageName, entry.activityName)
        )
        iconCache[entry.componentKey] = icon
        icon
    } catch (e: Exception) {
        null
    }

    fun visibleApps(hidden: Set<String>): List<AppEntry> =
        apps.filter { it.packageName !in hidden }

    fun byPackage(pkg: String): AppEntry? = apps.firstOrNull { it.packageName == pkg }

    /**
     * Ranks apps against [query]: prefix > word prefix > initials > substring >
     * subsequence; ties broken by how often the user opens the app.
     */
    fun search(query: String, hidden: Set<String>): List<AppEntry> {
        val q = AppEntry.normalize(query)
        if (q.isEmpty()) return visibleApps(hidden)
        val boost = usageBoost
        return apps.asSequence()
            .filter { it.packageName !in hidden }
            .mapNotNull { entry ->
                val score = matchScore(entry, q)
                if (score <= 0) null else Triple(entry, score, boost[entry.packageName] ?: 0.0)
            }
            .sortedWith(
                compareByDescending<Triple<AppEntry, Int, Double>> { it.second }
                    .thenByDescending { it.third }
                    .thenBy { it.first.normalizedLabel }
            )
            .map { it.first }
            .toList()
    }

    private fun matchScore(entry: AppEntry, q: String): Int {
        val n = entry.normalizedLabel
        return when {
            n.startsWith(q) -> 100
            n.split(' ', '-', '.').any { it.startsWith(q) } -> 80
            entry.initials.startsWith(q) -> 65
            n.contains(q) -> 50
            entry.packageTokens.any { it.startsWith(q) } -> 45
            isSubsequence(q, n) -> 20
            else -> 0
        }
    }

    private fun isSubsequence(needle: String, haystack: String): Boolean {
        if (needle.length > haystack.length) return false
        var i = 0
        for (c in haystack) {
            if (i < needle.length && needle[i] == c) i++
        }
        return i == needle.length
    }

    private companion object {
        const val TAG = "AppRepository"
    }
}
