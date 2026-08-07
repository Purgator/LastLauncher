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

    /** Calendar.SUNDAY = 1, Calendar.SATURDAY = 7 (constants inlined to stay pure). */
    fun isWeekend(dow: Int): Boolean = dow == 1 || dow == 7

    fun circularHourDiff(a: Int, b: Int): Int {
        val d = abs(a - b)
        return minOf(d, 24 - d)
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
        val ageDays = (now - rowTs).toDouble() / DAY_MS
        val w = exp(-ageDays / DECAY_DAYS)
        var s = w
        if (circularHourDiff(rowHour, hourNow) <= 1) s += W_HOUR * w
        if (isWeekend(rowDow) == weekendNow) s += W_DAY_TYPE * w
        if (prevPkg != null && rowPrevPkg == prevPkg) s += W_TRANSITION * w
        if (activeEvent != null && rowCtxEvent == activeEvent) s += W_TRIGGER * w
        return s
    }
}
