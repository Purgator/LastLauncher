package fr.arichard.lastlauncher.ui

/**
 * The launcher's signature widget: a one-line terminal status readout shown under the
 * date. Which tokens appear — and their fixed left-to-right order — is chosen by the
 * user; battery is off by default since the system status bar already shows it.
 *
 * Example:  › wifi · ⏰07:00 · ↑23 · ▤ 41.2G
 *
 * Purely a formatter so it stays unit testable; the host gathers the live [Values].
 */
object StatusLine {

    enum class Net(val token: String) {
        WIFI("wifi"), CELLULAR("cell"), OFFLINE("off")
    }

    const val BATTERY = "battery"
    const val NETWORK = "network"
    const val ALARM = "alarm"
    const val LAUNCHES = "launches"
    const val STORAGE = "storage"
    const val DB = "db"

    /** Canonical order tokens are rendered in, regardless of selection order. */
    val ALL_TOKENS = listOf(BATTERY, NETWORK, ALARM, LAUNCHES, STORAGE, DB)

    val DEFAULT_TOKENS = setOf(NETWORK, ALARM, LAUNCHES)

    data class Values(
        val batteryPercent: Int,
        val charging: Boolean,
        val net: Net,
        val nextAlarm: String?,
        val launchesToday: Int,
        val freeStorageGb: Double,
        val dbBytes: Long = 0,
    )

    fun build(enabled: Set<String>, v: Values): String =
        "› " + segments(enabled, v).joinToString("  ·  ") { it.second }

    /** Rendered (tokenId, text) pairs, so hosts can attach per-token tap actions. */
    fun segments(enabled: Set<String>, v: Values): List<Pair<String, String>> =
        ALL_TOKENS.filter { it in enabled }
            .mapNotNull { id -> token(id, v)?.let { id to it } }

    private fun token(id: String, v: Values): String? = when (id) {
        BATTERY -> "${v.batteryPercent}%" + if (v.charging) "⚡" else ""
        NETWORK -> v.net.token
        ALARM -> v.nextAlarm?.let { "⏰$it" }
        LAUNCHES -> if (v.launchesToday > 0) "↑${v.launchesToday}" else null
        STORAGE -> "▤ ${"%.1f".format(java.util.Locale.US, v.freeStorageGb)}G"
        DB -> if (v.dbBytes > 0) "◇ ${formatBytes(v.dbBytes)}" else null
        else -> null
    }

    /** Compact byte count: 87K, 1.2M. */
    internal fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1fM".format(java.util.Locale.US, bytes / 1_000_000.0)
        else -> "${(bytes / 1000).coerceAtLeast(1)}K"
    }

    // ------------------------------------------------------------ next alarm

    private const val MINUTES_PER_DAY = 24 * 60

    /** Some OEM clocks report the trigger up to ~this early vs what their UI shows. */
    const val ALARM_PREWAKE_MAX_MIN = 20

    /**
     * Minutes-of-day to display for the next alarm, reconciling the two system
     * sources: [formattedMinutes] parsed from the OEM's NEXT_ALARM_FORMATTED string
     * and [triggerMinutes] from AlarmClockInfo (framework-maintained, timezone-safe,
     * but pre-wake-shifted on MIUI). The formatted value wins only when it sits at
     * or shortly after the trigger — i.e. it clearly describes the same alarm; a
     * stale or timezone-shifted string is discarded in favor of the trigger time.
     */
    fun reconcileAlarmMinutes(formattedMinutes: Int?, triggerMinutes: Int): Int {
        if (formattedMinutes == null) return triggerMinutes
        val delta = ((formattedMinutes - triggerMinutes) % MINUTES_PER_DAY + MINUTES_PER_DAY) %
            MINUTES_PER_DAY
        return if (delta <= ALARM_PREWAKE_MAX_MIN) formattedMinutes else triggerMinutes
    }

    /**
     * First clock time found in [text] ("ven. 07:30", "7h05", "8:00 PM"), as minutes
     * of day, or null when none parses. Tolerant of the varied OEM formats behind
     * NEXT_ALARM_FORMATTED.
     */
    fun parseTimeOfDay(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val match = Regex("""(\d{1,2})[:hH.](\d{2})""").find(text) ?: return null
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        // AM/PM may be separated by a narrow no-break space (U+202F on newer
        // Android) that trimStart() won't touch — keep only letters instead.
        val rest = text.substring(match.range.last + 1)
            .filter { it.isLetter() }.take(2).lowercase()
        if (rest.startsWith("pm") && hour < 12) hour += 12
        if (rest.startsWith("am") && hour == 12) hour = 0
        return hour * 60 + minute
    }
}
