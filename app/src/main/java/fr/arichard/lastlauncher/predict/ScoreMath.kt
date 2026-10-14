package fr.arichard.lastlauncher.predict

import kotlin.math.abs
import kotlin.math.exp

/**
 * The prediction scoring math, extracted pure so the live engine and the
 * walk-forward backtester compute exactly the same numbers from the same rows.
 * No Android dependencies allowed here — this file is covered by JVM unit
 * tests that pin the formula.
 */
object ScoreMath {

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val HISTORY_DAYS = 60L
    const val DECAY_DAYS = 20.0

    // Signal weights, tuned so a strong contextual habit beats raw frequency.
    const val W_HOUR = 1.2
    const val W_DAY_TYPE = 0.3
    const val W_TRANSITION = 2.5
    const val W_TRIGGER = 3.5

    // A learned candidate only takes a suggestion slot from the user's go-to apps
    // once its score is at least this (≈ one recent, contextually matching launch).
    const val MIN_CONFIDENCE = 1.0

    // Per active notification (capped), so an app demanding attention floats up
    // without drowning real habits: 4+ notifications ≈ one recent matching launch.
    const val W_NOTIFICATION = 0.35
    const val NOTIFICATION_CAP = 4

    // User-boosted apps ("Boost in suggestions" in the long-press menu): learned score
    // is amplified and gets a floor, so a boosted app shows even with little history
    // yet still yields to genuinely stronger habits.
    const val BOOST_FACTOR = 1.35
    const val BOOST_BASE = 1.0

    // The app just opened is almost never wanted again right away — crush its score
    // for a while. Exception: within the first seconds (an accidental exit) it keeps
    // its full rank so reopening is one tap.
    const val JUST_USED_FACTOR = 0.05
    const val MISTAKE_WINDOW_MS = 15_000L
    const val JUST_USED_WINDOW_MS = 45L * 60_000

    // A miss row (corrected trio) counts as a negative launch with this weight.
    const val W_MISS = 1.5

    // "Once a day" satiation (see SatiationTracker): an app whose launches are
    // followed by another one inside SATIATION_WINDOW_MS at most SATIATION_MAX_REPEAT
    // of the time (over at least SATIATION_MIN_RESOLVED settled launches) is damped
    // by SATIATION_FACTOR from the end of the just-used crush until the window ends.
    // 0.25 already clears a trigger-boosted score out of the trio on real data;
    // 0.1 produced the identical trios.
    const val SATIATION_WINDOW_MS = 12L * 60 * 60_000
    const val SATIATION_MAX_REPEAT = 0.2
    const val SATIATION_MIN_RESOLVED = 10
    const val SATIATION_FACTOR = 0.25
    // Rows this close to the previous one of the same app are the correction
    // feedback's reinforcement rows (same instant), not a second launch.
    const val DUPLICATE_GAP_MS = 5_000L

    // Swipe-page re-rank (hourConcentration/rerankPages): the trio stays the plain
    // ranking, but the pages behind it sort by score × how concentrated the app's
    // launches are around the current hour, so a "due at this hour" app that never
    // out-volumes the all-day heavies (a nightly video app vs. the messengers)
    // reaches the first swipe instead of rank 13. Backtested neutral on hit@3 by
    // construction, +0.2pp hit@12.
    const val PAGE_HEAD = 3
    const val HOUR_WINDOW_SHARE = 3.0 / 24
    const val CONCENTRATION_ALPHA = 2.0
    const val CONCENTRATION_CAP = 3.0
    const val CONCENTRATION_MIN_MASS = 3.0

    /** Calendar.SUNDAY = 1, Calendar.SATURDAY = 7 (constants inlined to stay pure). */
    fun isWeekend(dow: Int): Boolean = dow == 1 || dow == 7

    fun circularHourDiff(a: Int, b: Int): Int {
        val d = abs(a - b)
        return minOf(d, 24 - d)
    }

    /** The recency weight of a row: 1.0 now, halving roughly every two weeks. */
    fun decayWeight(rowTs: Long, now: Long): Double =
        exp(-((now - rowTs).toDouble() / DAY_MS) / DECAY_DAYS)

    /**
     * How much more than uniform an app's (decayed) launches fall within ±1 h of
     * the current hour: 1.0 = spread over the day (or too little data), up to
     * [CONCENTRATION_CAP]. [mass] is the app's decayed launch count and
     * [hourMass] the part of it within the hour window; both Laplace-smoothed
     * toward uniform so a 3-launch app can't claim a 8× peak.
     */
    fun hourConcentration(mass: Double, hourMass: Double): Double {
        if (mass < CONCENTRATION_MIN_MASS) return 1.0
        val share = (hourMass + CONCENTRATION_ALPHA * HOUR_WINDOW_SHARE) / (mass + CONCENTRATION_ALPHA)
        return (share / HOUR_WINDOW_SHARE).coerceIn(1.0, CONCENTRATION_CAP)
    }

    /**
     * Keeps the first [PAGE_HEAD] entries of [ranked] (the trio) as they are and
     * sorts the rest by score × [concentration] — the order the swipe pages show.
     * Stable, so equal products keep the plain ranking order.
     */
    fun rerankPages(
        ranked: List<String>,
        scores: Map<String, Double>,
        concentration: (String) -> Double,
    ): List<String> {
        if (ranked.size <= PAGE_HEAD + 1) return ranked
        val head = ranked.subList(0, PAGE_HEAD)
        val tail = ranked.subList(PAGE_HEAD, ranked.size)
            .sortedByDescending { (scores[it] ?: 0.0) * concentration(it) }
        return head + tail
    }

    /**
     * One launch row's contribution to its app's score at the given moment:
     * recency-decayed base plus the matching signal bonuses. Miss rows use the
     * same formula (their prevPkg is always null, so the transition term never
     * fires) scaled by -[W_MISS] at the call site.
     */
    fun rowScore(
        rowTs: Long,
        rowHour: Int,
        rowDow: Int,
        rowPrevPkg: String?,
        rowCtxEvent: String?,
        now: Long,
        hourNow: Int,
        weekendNow: Boolean,
        prevPkg: String?,
        activeEvent: String?,
    ): Double {
        val w = decayWeight(rowTs, now)
        var s = w
        if (circularHourDiff(rowHour, hourNow) <= 1) s += W_HOUR * w
        if (isWeekend(rowDow) == weekendNow) s += W_DAY_TYPE * w
        if (prevPkg != null && rowPrevPkg == prevPkg) s += W_TRANSITION * w
        if (activeEvent != null && rowCtxEvent == activeEvent) s += W_TRIGGER * w
        return s
    }
}
