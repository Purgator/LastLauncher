package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.predict.SatiationTracker
import fr.arichard.lastlauncher.predict.ScoreMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SatiationTest {

    private val day = ScoreMath.DAY_MS
    private val hour = 3_600_000L
    private val t0 = 1_700_000_000_000L

    /** One launch per day at the same hour for [days] days. */
    private fun onceADay(days: Int, pkg: String = "gym"): SatiationTracker {
        val t = SatiationTracker()
        for (d in 0 until days) t.add(pkg, t0 + d * day)
        return t
    }

    @Test
    fun onceADayAppIsDampedBetweenCrushAndWindow() {
        val t = onceADay(12)
        val last = t0 + 11 * day
        assertEquals(ScoreMath.SATIATION_FACTOR, t.factor("gym", last + 3 * hour), 0.0)
        assertTrue(t.isSatiated("gym", last + ScoreMath.JUST_USED_WINDOW_MS + 1))
        assertTrue(t.isSatiated("gym", last + ScoreMath.SATIATION_WINDOW_MS - 1))
    }

    @Test
    fun notDampedInsideTheCrushWindowNorAfterTwelveHours() {
        val t = onceADay(12)
        val last = t0 + 11 * day
        assertEquals(1.0, t.factor("gym", last + 10 * 60_000), 0.0)       // the crush owns this
        assertEquals(1.0, t.factor("gym", last + ScoreMath.SATIATION_WINDOW_MS), 0.0)
        assertEquals(1.0, t.factor("gym", last + 14 * hour), 0.0)
        assertEquals(1.0, t.factor("unknown", last + 3 * hour), 0.0)
    }

    @Test
    fun needsEnoughSettledLaunches() {
        // 10 launches but the last one is still open: 9 settled < 10 → no damping.
        val t = onceADay(10)
        val last = t0 + 9 * day
        assertEquals(9 to 0, t.repeatStats("gym", last + 3 * hour))
        assertEquals(1.0, t.factor("gym", last + 3 * hour), 0.0)
        // One more day and the 10th launch has settled.
        t.add("gym", t0 + 10 * day)
        assertEquals(10 to 0, t.repeatStats("gym", t0 + 10 * day + 3 * hour))
        assertEquals(ScoreMath.SATIATION_FACTOR, t.factor("gym", t0 + 10 * day + 3 * hour), 0.0)
    }

    @Test
    fun appReopenedTheSameDayIsNotDamped() {
        val t = SatiationTracker()
        // Every day: 09:00 and again 15:00 → repeat rate 0.5 (the 15:00 one never repeats).
        for (d in 0 until 15) {
            t.add("chat", t0 + d * day)
            t.add("chat", t0 + d * day + 6 * hour)
        }
        val now = t0 + 14 * day + 6 * hour + 2 * hour
        val (resolved, repeats) = t.repeatStats("chat", now)!!
        assertEquals(29, resolved) // today's 15:00 launch is still open
        assertEquals(15, repeats)
        assertTrue(repeats.toDouble() / resolved > ScoreMath.SATIATION_MAX_REPEAT)
        assertFalse(t.isSatiated("chat", now))
    }

    @Test
    fun reboundsNeitherRepeatNorSettle() {
        val t = SatiationTracker()
        for (d in 0 until 15) {
            t.add("maps", t0 + d * day)
            t.add("maps", t0 + d * day + 20 * 60_000) // reopened at 20 min: a rebound
        }
        val now = t0 + 14 * day + 3 * hour
        // 28 settled launches (the 2 of today are open), none repeated: still once a day.
        assertEquals(28 to 0, t.repeatStats("maps", now))
        assertTrue(t.isSatiated("maps", now))
        // But a rebound followed by a real repeat marks the ORIGINAL launch as repeated.
        val t2 = SatiationTracker()
        t2.add("x", t0); t2.add("x", t0 + 20 * 60_000); t2.add("x", t0 + 3 * hour)
        // t0 and the rebound both settle as repeated at 3 h; the 3 h launch settles a day later.
        assertEquals(3 to 2, t2.repeatStats("x", t0 + day))
    }

    @Test
    fun reinforcementDuplicatesAreIgnored() {
        val t = SatiationTracker()
        for (d in 0 until 15) {
            t.add("gym", t0 + d * day)
            t.add("gym", t0 + d * day + 10) // logTrioCorrection's extra row, 10 ms later
        }
        val now = t0 + 14 * day + 3 * hour
        assertEquals(14 to 0, t.repeatStats("gym", now))
        assertTrue(t.isSatiated("gym", now))
        assertEquals(listOf("gym"), t.satiated(now))
    }
}
