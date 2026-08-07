package fr.arichard.lastlauncher.predict

/**
 * Hit-rate observability: a slow EMA of "the suggestion trio contained the app
 * the user launched", plus a small reliability table bucketed by how dominant
 * the top slot looked (its share of the trio's score mass) — i.e. when the
 * engine acts confident, is it actually right more often?
 *
 * Pure state math and a string codec; the single encoded value lives in Prefs.
 * Strictly observational: nothing here feeds back into the ranking, and
 * MIN_CONFIDENCE must not be tuned from it until the table has real mass.
 */
object Calibration {

    /** EMA smoothing: each launch moves the estimate 2% toward the outcome. */
    const val BETA = 0.02
    const val BIN_COUNT = 5

    // Top-slot share of the trio's scores runs from ~1/3 (three equals) to 1.0
    // (one app crushes the other two). Bin edges chosen so real traffic spreads.
    private val BIN_EDGES = doubleArrayOf(0.45, 0.55, 0.65, 0.80)

    class State(
        val globalEma: Double,
        val globalN: Int,
        val binEma: DoubleArray,
        val binN: IntArray,
    )

    fun empty() = State(0.0, 0, DoubleArray(BIN_COUNT), IntArray(BIN_COUNT))

    fun binIndex(topShare: Double): Int {
        for (i in BIN_EDGES.indices) {
            if (topShare < BIN_EDGES[i]) return i
        }
        return BIN_COUNT - 1
    }

    /** Human label for a bin, e.g. "45–55%". Pure ASCII digits, Locale-safe. */
    fun binLabel(i: Int): String = when (i) {
        0 -> "<45%"
        1 -> "45–55%"
        2 -> "55–65%"
        3 -> "65–80%"
        else -> ">80%"
    }

    /**
     * Folds one launch in. [topShare] is the top slot's share of the trio's
     * score mass, or any negative value when unknown (young memory, zero
     * scores) — the reliability table is skipped then, the global EMA is not.
     * The EMA seeds at the first observed value instead of decaying from 0.
     */
    fun record(st: State, topShare: Double, top1Hit: Boolean, trioHit: Boolean): State {
        val trioValue = if (trioHit) 1.0 else 0.0
        val globalEma =
            if (st.globalN == 0) trioValue
            else st.globalEma + BETA * (trioValue - st.globalEma)
        val binEma = st.binEma.copyOf()
        val binN = st.binN.copyOf()
        if (topShare >= 0.0) {
            val b = binIndex(topShare)
            val topValue = if (top1Hit) 1.0 else 0.0
            binEma[b] =
                if (binN[b] == 0) topValue
                else binEma[b] + BETA * (topValue - binEma[b])
            binN[b]++
        }
        return State(globalEma, st.globalN + 1, binEma, binN)
    }

    /** Codec note: Double.toString/toDouble are locale-independent ('.'). */
    fun encode(st: State): String = buildString {
        append(st.globalEma).append('|').append(st.globalN)
        for (i in 0 until BIN_COUNT) {
            append('|').append(st.binEma[i]).append('|').append(st.binN[i])
        }
    }

    fun parse(s: String?): State {
        if (s.isNullOrEmpty()) return empty()
        return try {
            val p = s.split('|')
            val binEma = DoubleArray(BIN_COUNT)
            val binN = IntArray(BIN_COUNT)
            for (i in 0 until BIN_COUNT) {
                binEma[i] = p[2 + 2 * i].toDouble()
                binN[i] = p[3 + 2 * i].toInt()
            }
            State(p[0].toDouble(), p[1].toInt(), binEma, binN)
        } catch (e: Exception) {
            empty() // unknown/corrupt format: start over rather than crash
        }
    }
}
