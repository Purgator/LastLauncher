package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.predict.ScoreMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * Pins the per-row scoring formula: the live engine and the backtester both
 * ride on these exact numbers, so any change here is a behavior change.
 */
class ScoreMathTest {

    private val now = 100L * ScoreMath.DAY_MS

    private fun score(
        ageDays: Long = 0,
        rowHour: Int = 3,
        rowDow: Int = 2,
        rowPrev: String? = null,
        rowCtx: String? = null,
        hourNow: Int = 12,
        weekendNow: Boolean = true,
        prevPkg: String? = null,
        activeEvent: String? = null,
    ) = ScoreMath.rowScore(
        now - ageDays * ScoreMath.DAY_MS, rowHour, rowDow, rowPrev, rowCtx,
        now, hourNow, weekendNow, prevPkg, activeEvent,
    )

    @Test
    fun freshRowWithNoMatchingSignalScoresItsBareDecayWeight() {
        // hour 3 vs 12, weekday row vs weekend now, no prev, no trigger.
        assertEquals(1.0, score(), 1e-9)
    }

    @Test
    fun decayHalvesEveryFourteenDaysish() {
        assertEquals(exp(-1.0), score(ageDays = 20), 1e-9)
        assertEquals(exp(-3.0), score(ageDays = 60), 1e-9)
    }

    @Test
    fun hourBonusIsACliffAtPlusMinusOne() {
        assertEquals(1.0 + ScoreMath.W_HOUR, score(rowHour = 11), 1e-9)
        assertEquals(1.0 + ScoreMath.W_HOUR, score(rowHour = 12), 1e-9)
        assertEquals(1.0 + ScoreMath.W_HOUR, score(rowHour = 13), 1e-9)
        assertEquals(1.0, score(rowHour = 14), 1e-9)
        assertEquals(1.0, score(rowHour = 10), 1e-9)
    }

    @Test
    fun hourDiffWrapsAroundMidnight() {
        assertEquals(1.0 + ScoreMath.W_HOUR, score(rowHour = 23, hourNow = 0), 1e-9)
        assertEquals(1, ScoreMath.circularHourDiff(23, 0))
        assertEquals(2, ScoreMath.circularHourDiff(23, 1))
        assertEquals(0, ScoreMath.circularHourDiff(5, 5))
        assertEquals(12, ScoreMath.circularHourDiff(0, 12))
    }

    @Test
    fun dayTypeBonusMatchesWeekendToWeekend() {
        // dow 7 = Saturday, weekend now → match.
        assertEquals(1.0 + ScoreMath.W_DAY_TYPE, score(rowDow = 7), 1e-9)
        // weekday row, weekday now → also a match.
        assertEquals(1.0 + ScoreMath.W_DAY_TYPE, score(rowDow = 3, weekendNow = false), 1e-9)
    }

    @Test
    fun transitionBonusNeedsTheExactPreviousApp() {
        assertEquals(
            1.0 + ScoreMath.W_TRANSITION,
            score(rowPrev = "x", prevPkg = "x"), 1e-9,
        )
        assertEquals(1.0, score(rowPrev = "x", prevPkg = "y"), 1e-9)
        // A null current prev never matches (miss rows / fresh boot).
        assertEquals(1.0, score(rowPrev = null, prevPkg = null), 1e-9)
    }

    @Test
    fun triggerBonusNeedsTheSameActiveEvent() {
        assertEquals(
            1.0 + ScoreMath.W_TRIGGER,
            score(rowCtx = "bt_connected", activeEvent = "bt_connected"), 1e-9,
        )
        assertEquals(1.0, score(rowCtx = "bt_connected", activeEvent = "power_connected"), 1e-9)
        assertEquals(1.0, score(rowCtx = null, activeEvent = "bt_connected"), 1e-9)
    }

    @Test
    fun allSignalsStackOnTheDecayedWeight() {
        val expected = exp(-1.0) * (
            1.0 + ScoreMath.W_HOUR + ScoreMath.W_DAY_TYPE +
                ScoreMath.W_TRANSITION + ScoreMath.W_TRIGGER
            )
        val s = score(
            ageDays = 20, rowHour = 12, rowDow = 1, rowPrev = "x", rowCtx = "bt",
            hourNow = 12, weekendNow = true, prevPkg = "x", activeEvent = "bt",
        )
        assertEquals(expected, s, 1e-9)
    }

    @Test
    fun weekendIsCalendarSundayAndSaturday() {
        assertTrue(ScoreMath.isWeekend(1))
        assertTrue(ScoreMath.isWeekend(7))
        for (dow in 2..6) assertFalse(ScoreMath.isWeekend(dow))
    }

    // ------------------------------------------------ swipe-page re-rank (B)

    @Test
    fun hourConcentrationIsOneForSpreadOrThinApps() {
        // Uniform over the day: 3/24 of the mass sits in the ±1 h window.
        assertEquals(1.0, ScoreMath.hourConcentration(mass = 48.0, hourMass = 6.0), 1e-9)
        // Below the evidence floor, whatever the shape.
        assertEquals(1.0, ScoreMath.hourConcentration(mass = 2.9, hourMass = 2.9), 1e-9)
        // Never below 1 — a dead hour does not demote.
        assertEquals(1.0, ScoreMath.hourConcentration(mass = 40.0, hourMass = 0.0), 1e-9)
    }

    @Test
    fun hourConcentrationRewardsPeaksUpToTheCap() {
        // Half of 20 launches within the window: share (10+0.25)/22 → ×3.73, capped at 3.
        assertEquals(ScoreMath.CONCENTRATION_CAP, ScoreMath.hourConcentration(20.0, 10.0), 1e-9)
        // A quarter: (5+0.25)/22 / 0.125 ≈ 1.91 — smoothing keeps it under the raw 2×.
        val c = ScoreMath.hourConcentration(20.0, 5.0)
        assertTrue(c > 1.8 && c < 2.0)
        // At the evidence floor: 1 of 3 in-window → (1+0.25)/5 / 0.125 = 2.0 exactly.
        assertEquals(2.0, ScoreMath.hourConcentration(3.0, 1.0), 1e-9)
    }

    @Test
    fun rerankPagesKeepsTheTrioAndSortsTheRestByConcentratedScore() {
        val scores = mapOf("a" to 100.0, "b" to 90.0, "c" to 80.0, "d" to 50.0, "e" to 30.0, "f" to 20.0)
        val conc = mapOf("f" to 3.0, "e" to 1.0, "d" to 1.0, "a" to 3.0)
        val ranked = listOf("a", "b", "c", "d", "e", "f")
        // f: 20×3 = 60 beats d: 50 and e: 30; the head is untouched even though a is concentrated.
        assertEquals(listOf("a", "b", "c", "f", "d", "e"), ScoreMath.rerankPages(ranked, scores) { conc[it] ?: 1.0 })
        // Ties keep the plain order (stable sort).
        assertEquals(ranked, ScoreMath.rerankPages(ranked, scores) { 1.0 })
        // Nothing to sort behind the head.
        assertEquals(listOf("a", "b", "c", "d"), ScoreMath.rerankPages(listOf("a", "b", "c", "d"), scores) { 3.0 })
    }
}
