package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.predict.Calibration
import org.junit.Assert.assertEquals
import org.junit.Test

class CalibrationTest {

    @Test
    fun emptyAndCorruptInputsParseToTheEmptyState() {
        for (raw in listOf(null, "", "garbage", "1.0|not_a_number|x")) {
            val st = Calibration.parse(raw)
            assertEquals(0, st.globalN)
            assertEquals(0.0, st.globalEma, 0.0)
            assertEquals(Calibration.BIN_COUNT, st.binEma.size)
        }
    }

    @Test
    fun encodeParseRoundTripsExactly() {
        var st = Calibration.empty()
        st = Calibration.record(st, 0.7, top1Hit = true, trioHit = true)
        st = Calibration.record(st, 0.4, top1Hit = false, trioHit = true)
        st = Calibration.record(st, -1.0, top1Hit = false, trioHit = false)
        val back = Calibration.parse(Calibration.encode(st))
        assertEquals(st.globalEma, back.globalEma, 0.0)
        assertEquals(st.globalN, back.globalN)
        for (i in 0 until Calibration.BIN_COUNT) {
            assertEquals(st.binEma[i], back.binEma[i], 0.0)
            assertEquals(st.binN[i], back.binN[i])
        }
    }

    @Test
    fun emaSeedsAtTheFirstValueThenMovesByBeta() {
        var st = Calibration.record(Calibration.empty(), -1.0, top1Hit = false, trioHit = true)
        assertEquals(1.0, st.globalEma, 0.0)
        st = Calibration.record(st, -1.0, top1Hit = false, trioHit = false)
        assertEquals(1.0 + Calibration.BETA * (0.0 - 1.0), st.globalEma, 1e-12)
        assertEquals(2, st.globalN)
    }

    @Test
    fun binEdgesSplitTheShareRangeAsDocumented() {
        assertEquals(0, Calibration.binIndex(0.34))
        assertEquals(0, Calibration.binIndex(0.4499))
        assertEquals(1, Calibration.binIndex(0.45))
        assertEquals(2, Calibration.binIndex(0.60))
        assertEquals(3, Calibration.binIndex(0.79))
        assertEquals(4, Calibration.binIndex(0.80))
        assertEquals(4, Calibration.binIndex(1.0))
    }

    @Test
    fun unknownShareSkipsTheReliabilityTableButNotTheGlobalEma() {
        val st = Calibration.record(Calibration.empty(), -1.0, top1Hit = true, trioHit = true)
        assertEquals(1, st.globalN)
        assertEquals(0, st.binN.sum())
    }

    @Test
    fun knownShareLandsInExactlyOneBin() {
        val st = Calibration.record(Calibration.empty(), 0.9, top1Hit = true, trioHit = true)
        assertEquals(1, st.binN.sum())
        assertEquals(1, st.binN[4])
        assertEquals(1.0, st.binEma[4], 0.0)
    }
}
