package fr.arichard.lastlauncher.predict

/**
 * Pure codec for the "corrected trio" feedback: when the user swipes the
 * suggestion row and launches a DIFFERENT app within seconds, the trio that was
 * shown was wrong for that moment — each shown app takes a decaying penalty in
 * the current time bucket. Swiping without launching anything is just play and
 * records nothing (the host enforces that part).
 *
 * Encoded as "pkg|bucket|ts" entries joined by commas; small, pruned, and kept
 * in SharedPreferences. Unit-tested.
 */
object MissLog {

    /** Penalties only apply within the same 3-hour slice of the day. */
    fun bucketOf(hour: Int): Int = (hour.coerceIn(0, 23)) / 3

    /** Appends one miss per package, pruning expired entries and capping size. */
    fun record(
        encoded: String,
        pkgs: Collection<String>,
        bucket: Int,
        now: Long,
        max: Int = MAX_ENTRIES,
    ): String {
        val live = decode(encoded).filter { now - it.ts <= WINDOW_MS }
        val added = live + pkgs.map { Entry(it, bucket, now) }
        return added.takeLast(max).joinToString(",") { "${it.pkg}|${it.bucket}|${it.ts}" }
    }

    /** Miss count per package for [bucket], counting only unexpired entries. */
    fun penalties(encoded: String, bucket: Int, now: Long): Map<String, Int> =
        decode(encoded)
            .filter { it.bucket == bucket && now - it.ts <= WINDOW_MS }
            .groupingBy { it.pkg }
            .eachCount()

    private data class Entry(val pkg: String, val bucket: Int, val ts: Long)

    private fun decode(encoded: String): List<Entry> =
        encoded.split(',').mapNotNull {
            val parts = it.split('|')
            if (parts.size != 3) return@mapNotNull null
            val bucket = parts[1].toIntOrNull() ?: return@mapNotNull null
            val ts = parts[2].toLongOrNull() ?: return@mapNotNull null
            if (parts[0].isEmpty()) null else Entry(parts[0], bucket, ts)
        }

    const val WINDOW_MS = 14L * 24 * 3_600_000 // two weeks, then forgiven
    const val MAX_ENTRIES = 60
}
