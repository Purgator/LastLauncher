package fr.arichard.lastlauncher.predict

/**
 * Walk-forward backtest of the usage log: replays the launches chronologically
 * and, for each one after a warmup prefix, ranks all apps using only the rows
 * that existed at that moment — with the exact same math as the live engine
 * ([ScoreMath]). The launched app's rank feeds hit@1 / hit@3 (the trio) /
 * hit@12 (the paging depth) and MRR.
 *
 * Two deliberately dumb baselines run in the same pass, because published
 * next-app-prediction work always compares against them and they are
 * embarrassingly strong: pure recency (LRU) and pure 60-day frequency (MFU).
 *
 * Pure Kotlin, no Android dependencies, unit-tested. Three honest limits:
 *  - notification counts and user boosts have no historical snapshots, so they
 *    are NOT replayed (the live trio likely does a little better on notifying
 *    messengers than the replay shows);
 *  - the engine's defaultPicks fillers need PackageManager, so the filler zone
 *    is favorites-only here;
 *  - the once-a-day [SatiationTracker] here learns from the whole log, the live
 *    engine from its 60-day scan — a repeat rate can differ slightly at the edge.
 * The swipe-page re-rank ([ScoreMath.rerankPages]) IS replayed: it moves apps
 * across the 12-deep cut, so hit@12 reflects it.
 */
object Backtester {

    /** Fractions in [0,1]; MRR is the mean reciprocal rank of the launched app. */
    data class Metrics(val hit1: Double, val hit3: Double, val hit12: Double, val mrr: Double)

    data class Report(
        val evaluated: Int,
        val warmup: Int,
        val engine: Metrics,
        val mfu: Metrics,
        val lru: Metrics,
    )

    /** Below this many launches a replay is mostly warmup noise — refuse. */
    const val MIN_ROWS = 100
    const val WARMUP_FRACTION = 0.2

    fun run(
        launches: List<UsageDb.Row>,
        misses: List<UsageDb.Row>,
        favorites: List<String>,
        hidden: Set<String>,
    ): Report? {
        if (launches.size < MIN_ROWS) return null
        val rows = launches.sortedBy { it.ts }
        val missRows = misses.sortedBy { it.ts }
        val warmup = (rows.size * WARMUP_FRACTION).toInt().coerceAtLeast(1)
        val evaluated = rows.size - warmup

        val engine = Tally()
        val mfu = Tally()
        val lru = Tally()

        // Baseline state, maintained incrementally: rows[0 until i] are folded in
        // before evaluating row i, and a window pointer expires rows older than
        // the 60-day history so the baselines see what the engine sees.
        val lastUse = HashMap<String, Long>()
        val counts = HashMap<String, Int>()
        val satiation = SatiationTracker()
        var windowStart = 0
        for (j in 0 until warmup) fold(rows[j], lastUse, counts, satiation)

        for (i in warmup until rows.size) {
            val t = rows[i]
            val prevRow = rows[i - 1]
            val horizon = t.ts - ScoreMath.HISTORY_DAYS * ScoreMath.DAY_MS
            while (windowStart < i && rows[windowStart].ts < horizon) {
                val old = rows[windowStart].pkg
                counts[old]?.let { c -> if (c <= 1) counts.remove(old) else counts[old] = c - 1 }
                windowStart++
            }

            val concentration = HashMap<String, Double>(64)
            val scores = scoreSnapshot(
                rows, missRows, i,
                now = t.ts,
                hourNow = t.hour,
                weekendNow = ScoreMath.isWeekend(t.dow),
                prevPkg = prevRow.pkg,
                activeEvent = t.ctxEvent,
                concentrationOut = concentration,
            )
            // Once-a-day satiation and the just-used crush, exactly as the engine applies them.
            for (pkg in satiation.satiated(t.ts)) {
                scores[pkg]?.let { scores[pkg] = it * ScoreMath.SATIATION_FACTOR }
            }
            val gap = t.ts - prevRow.ts
            if (gap in ScoreMath.MISTAKE_WINDOW_MS..ScoreMath.JUST_USED_WINDOW_MS) {
                scores[prevRow.pkg]?.let { scores[prevRow.pkg] = it * ScoreMath.JUST_USED_FACTOR }
            }
            engine.add(rankIn(engineRanking(scores, favorites, hidden, concentration), t.pkg))
            mfu.add(rankIn(counts.entries.sortedByDescending { it.value }.map { it.key }, t.pkg))
            lru.add(rankIn(lastUse.entries.sortedByDescending { it.value }.map { it.key }, t.pkg))

            fold(t, lastUse, counts, satiation)
        }
        return Report(
            evaluated = evaluated,
            warmup = warmup,
            engine = engine.metrics(evaluated),
            mfu = mfu.metrics(evaluated),
            lru = lru.metrics(evaluated),
        )
    }

    /**
     * The engine's score map as it stood just before launch index [endExclusive]:
     * every earlier launch and miss row inside the 60-day history, scored with
     * [ScoreMath.rowScore] against the given moment. Public so the parity unit
     * test can pin it against a by-hand computation of the documented formula.
     * When [concentrationOut] is given it receives each app's
     * [ScoreMath.hourConcentration] for [hourNow] from the same pass.
     */
    fun scoreSnapshot(
        launches: List<UsageDb.Row>,
        misses: List<UsageDb.Row>,
        endExclusive: Int,
        now: Long,
        hourNow: Int,
        weekendNow: Boolean,
        prevPkg: String?,
        activeEvent: String?,
        concentrationOut: HashMap<String, Double>? = null,
    ): HashMap<String, Double> {
        val horizon = now - ScoreMath.HISTORY_DAYS * ScoreMath.DAY_MS
        val scores = HashMap<String, Double>(64)
        val mass = if (concentrationOut != null) HashMap<String, DoubleArray>(64) else null
        for (j in 0 until endExclusive) {
            val r = launches[j]
            if (r.ts < horizon) continue
            val s = ScoreMath.rowScore(
                r.ts, r.hour, r.dow, r.prevPkg, r.ctxEvent,
                now, hourNow, weekendNow, prevPkg, activeEvent,
            )
            scores.merge(r.pkg, s, Double::plus)
            if (mass != null) {
                val w = ScoreMath.decayWeight(r.ts, now)
                val m = mass.getOrPut(r.pkg) { DoubleArray(2) }
                m[0] += w
                if (ScoreMath.circularHourDiff(r.hour, hourNow) <= 1) m[1] += w
            }
        }
        if (mass != null && concentrationOut != null) {
            for ((pkg, m) in mass) concentrationOut[pkg] = ScoreMath.hourConcentration(m[0], m[1])
        }
        for (r in misses) {
            if (r.ts >= now) break // sorted; later corrections don't exist yet
            if (r.ts < horizon) continue
            val s = ScoreMath.rowScore(
                r.ts, r.hour, r.dow, r.prevPkg, r.ctxEvent,
                now, hourNow, weekendNow, prevPkg, activeEvent,
            )
            scores.merge(r.pkg, -ScoreMath.W_MISS * s, Double::plus)
        }
        return scores
    }

    /** Confident scores first (pages re-ranked), then favorites fillers, then the leftovers. */
    private fun engineRanking(
        scores: Map<String, Double>,
        favorites: List<String>,
        hidden: Set<String>,
        concentration: Map<String, Double>,
    ): List<String> {
        val confident = scores.entries.asSequence()
            .filter { it.key !in hidden && it.value >= ScoreMath.MIN_CONFIDENCE }
            .sortedByDescending { it.value }
            .map { it.key }
            .toList()
        val ranked = ScoreMath.rerankPages(confident, scores) { concentration[it] ?: 1.0 }
            .toMutableList()
        val seen = ranked.toHashSet()
        for (pkg in favorites) {
            if (pkg !in hidden && seen.add(pkg)) ranked.add(pkg)
        }
        for (entry in scores.entries.sortedByDescending { it.value }) {
            if (entry.key !in hidden && seen.add(entry.key)) ranked.add(entry.key)
        }
        return ranked
    }

    private fun rankIn(ranking: List<String>, pkg: String): Int? {
        val idx = ranking.indexOf(pkg)
        return if (idx >= 0) idx + 1 else null
    }

    private fun fold(
        row: UsageDb.Row,
        lastUse: HashMap<String, Long>,
        counts: HashMap<String, Int>,
        satiation: SatiationTracker,
    ) {
        lastUse[row.pkg] = row.ts
        counts.merge(row.pkg, 1, Int::plus)
        satiation.add(row.pkg, row.ts)
    }

    private class Tally {
        var h1 = 0; var h3 = 0; var h12 = 0; var mrr = 0.0

        fun add(rank: Int?) {
            if (rank == null) return
            if (rank <= 1) h1++
            if (rank <= 3) h3++
            if (rank <= 12) h12++
            mrr += 1.0 / rank
        }

        fun metrics(n: Int) = Metrics(
            hit1 = h1.toDouble() / n,
            hit3 = h3.toDouble() / n,
            hit12 = h12.toDouble() / n,
            mrr = mrr / n,
        )
    }
}
