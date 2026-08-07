package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.predict.Backtester
import fr.arichard.lastlauncher.predict.ScoreMath
import fr.arichard.lastlauncher.predict.UsageDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class BacktesterTest {

    private val t0 = 1_700_000_000_000L
    private val hourMs = 3_600_000L

    /** Same-pkg prev collapses to null, mimicking logLaunch's dedup. */
    private fun row(pkg: String, ts: Long, prev: String?, ctx: String? = null) =
        UsageDb.Row(pkg, ts, hour = 12, dow = 3, prevPkg = prev?.takeIf { it != pkg }, ctxEvent = ctx)

    private fun sequence(pkgs: List<String>, stepMs: Long): List<UsageDb.Row> {
        var prev: String? = null
        return pkgs.mapIndexed { i, p ->
            row(p, t0 + i * stepMs, prev).also { prev = p }
        }
    }

    @Test
    fun refusesHistoriesBelowTheFloor() {
        val rows = sequence(List(Backtester.MIN_ROWS - 1) { "a" }, 2 * hourMs)
        assertNull(Backtester.run(rows, emptyList(), emptyList(), emptySet()))
    }

    @Test
    fun singleAppHistoryIsPredictedPerfectly() {
        // 2h spacing keeps every launch outside the just-used crush window.
        val rows = sequence(List(120) { "a" }, 2 * hourMs)
        val report = Backtester.run(rows, emptyList(), emptyList(), emptySet())!!
        assertEquals(24, report.warmup)
        assertEquals(96, report.evaluated)
        assertEquals(1.0, report.engine.hit1, 0.0)
        assertEquals(1.0, report.engine.mrr, 0.0)
        assertEquals(1.0, report.mfu.hit1, 0.0)
        assertEquals(1.0, report.lru.hit1, 0.0)
    }

    @Test
    fun alternatingPairIsCarriedByTheTransitionSignal() {
        // a,b,a,b… every 2h: the engine's transition term must predict the
        // other app every time, while pure recency predicts the just-used one.
        val rows = sequence(List(120) { if (it % 2 == 0) "a" else "b" }, 2 * hourMs)
        val report = Backtester.run(rows, emptyList(), emptyList(), emptySet())!!
        assertEquals(1.0, report.engine.hit1, 0.0)
        assertEquals(1.0, report.engine.mrr, 0.0)
        assertEquals(0.0, report.lru.hit1, 0.0)
        assertEquals(1.0, report.lru.hit3, 0.0) // only two apps exist
    }

    @Test
    fun justUsedCrushHidesQuickRelaunches() {
        // Cycles of [b, a, a]: the second a comes 20 min after the first, inside
        // the crush window. a holds twice b's mass, so without the crush every
        // prediction would be a (hit@1 = 2/3); the crush must push the quick
        // a-relaunch predictions below b, dragging hit@1 under one half.
        val rows = ArrayList<UsageDb.Row>()
        var prev: String? = null
        var ts = t0
        repeat(40) {
            for ((offset, pkg) in listOf(0L to "b", 3 * hourMs to "a", 3 * hourMs + 20 * 60_000L to "a")) {
                rows.add(row(pkg, ts + offset, prev))
                prev = pkg
            }
            ts += 9 * hourMs
        }
        val report = Backtester.run(rows, emptyList(), emptyList(), emptySet())!!
        assertTrue("hit@1 ${report.engine.hit1}", report.engine.hit1 < 0.45)
        assertTrue("hit@1 ${report.engine.hit1}", report.engine.hit1 > 0.2)
        assertEquals(1.0, report.engine.hit3, 0.0) // two apps: rank ≤ 2 always
    }

    @Test
    fun scoreSnapshotMatchesTheHandComputedFormula() {
        val day = ScoreMath.DAY_MS
        val now = t0 + 100 * day
        // Chronological: an out-of-window b row, then two a rows.
        val launches = listOf(
            UsageDb.Row("b", now - 70 * day, hour = 9, dow = 5, prevPkg = null, ctxEvent = null),
            UsageDb.Row("a", now - 10 * day, hour = 8, dow = 2, prevPkg = null, ctxEvent = null),
            UsageDb.Row("a", now - 1 * day, hour = 9, dow = 5, prevPkg = "b", ctxEvent = "bt"),
        )
        val misses = listOf(
            UsageDb.Row("a", now - 2 * day, hour = 9, dow = 3, prevPkg = null, ctxEvent = null),
        )
        val scores = Backtester.scoreSnapshot(
            launches, misses, endExclusive = 3,
            now = now, hourNow = 9, weekendNow = false, prevPkg = "b", activeEvent = "bt",
        )
        // Row 2: hour 8 is within ±1 of 9, weekday matches → w·(1 + 1.2 + 0.3).
        val r2 = exp(-10.0 / 20) * (1 + ScoreMath.W_HOUR + ScoreMath.W_DAY_TYPE)
        // Row 3: hour, daytype, prev AND trigger all match → w·(1+1.2+0.3+2.5+3.5).
        val r3 = exp(-1.0 / 20) * (
            1 + ScoreMath.W_HOUR + ScoreMath.W_DAY_TYPE +
                ScoreMath.W_TRANSITION + ScoreMath.W_TRIGGER
            )
        // Miss: hour + daytype match, no prev, no ctx → −1.5·w·(1+1.2+0.3).
        val miss = -ScoreMath.W_MISS * exp(-2.0 / 20) *
            (1 + ScoreMath.W_HOUR + ScoreMath.W_DAY_TYPE)
        assertEquals(r2 + r3 + miss, scores["a"]!!, 1e-9)
        // The 70-day-old b row sits outside the 60-day history window.
        assertNull(scores["b"])
    }
}
