package fr.arichard.lastlauncher.predict

/**
 * "Once-a-day" satiation: an app the user almost never reopens the same day
 * (gym entry, Play Store, a password manager…) should not keep sitting in
 * the trio for hours after its launch just because its trigger fired again.
 *
 * Fed with the launch rows in timestamp order, it learns per app how often a
 * launch is followed by another one between the just-used crush window
 * ([ScoreMath.JUST_USED_WINDOW_MS]) and [ScoreMath.SATIATION_WINDOW_MS]
 * later — the *repeat rate*. Gaps shorter than the crush window are rebounds
 * (reopened right away): they neither count as a repeat nor settle the
 * launch, so a launch reopened at 20 min and again at 3 h still counts as
 * repeated. Rows closer than [ScoreMath.DUPLICATE_GAP_MS] to the previous
 * one of the same app are the correction feedback's reinforcement rows and
 * are skipped — counted, they turn every corrected app into a "twice a day"
 * app.
 *
 * [factor] then damps an app by [ScoreMath.SATIATION_FACTOR] while it is
 * inside the window after its last launch, if it has enough settled history
 * ([ScoreMath.SATIATION_MIN_RESOLVED]) and a repeat rate at or under
 * [ScoreMath.SATIATION_MAX_REPEAT]. Measured on the owner's 86-day export:
 * changes 3 of 4289 replayed trios, never removing the launched app
 * (docs/BRAIN_IDEAS.md, 2026-10-14 analysis). Pure Kotlin, unit-tested; the
 * engine and the backtester share it.
 */
class SatiationTracker {

    private class App {
        var lastTs = Long.MIN_VALUE
        /** Launches not yet settled (younger than the window, or only rebounded). */
        val pending = ArrayList<Long>(4)
        var resolved = 0
        var repeats = 0
    }

    private val apps = HashMap<String, App>(64)

    /** Feeds one launch; rows MUST arrive in timestamp order. */
    fun add(pkg: String, ts: Long) {
        val app = apps.getOrPut(pkg) { App() }
        if (app.lastTs != Long.MIN_VALUE && ts - app.lastTs < ScoreMath.DUPLICATE_GAP_MS) return
        app.lastTs = ts
        val it = app.pending.iterator()
        while (it.hasNext()) {
            val gap = ts - it.next()
            when {
                gap > ScoreMath.SATIATION_WINDOW_MS -> { app.resolved++; it.remove() }
                gap > ScoreMath.JUST_USED_WINDOW_MS -> { app.resolved++; app.repeats++; it.remove() }
                // else: a rebound inside the crush window — the launch stays open.
            }
        }
        app.pending.add(ts)
    }

    /** Settled launches and how many of them were followed within the window, as of [now]. */
    fun repeatStats(pkg: String, now: Long): Pair<Int, Int>? {
        val app = apps[pkg] ?: return null
        var resolved = app.resolved
        for (p in app.pending) if (now - p > ScoreMath.SATIATION_WINDOW_MS) resolved++
        return resolved to app.repeats
    }

    /** Milliseconds since the app's last (non-duplicate) launch, or null if never seen. */
    fun sinceLast(pkg: String, now: Long): Long? = apps[pkg]?.let { now - it.lastTs }

    /** True when [factor] is currently damping the app. */
    fun isSatiated(pkg: String, now: Long): Boolean {
        val elapsed = sinceLast(pkg, now) ?: return false
        if (elapsed <= ScoreMath.JUST_USED_WINDOW_MS || elapsed >= ScoreMath.SATIATION_WINDOW_MS) return false
        val (resolved, repeats) = repeatStats(pkg, now) ?: return false
        if (resolved < ScoreMath.SATIATION_MIN_RESOLVED) return false
        return repeats.toDouble() / resolved <= ScoreMath.SATIATION_MAX_REPEAT
    }

    /** The multiplier to apply to the app's history-derived score right now. */
    fun factor(pkg: String, now: Long): Double =
        if (isSatiated(pkg, now)) ScoreMath.SATIATION_FACTOR else 1.0

    /** Every app currently damped, for the insights screen. */
    fun satiated(now: Long): List<String> = apps.keys.filter { isSatiated(it, now) }
}
