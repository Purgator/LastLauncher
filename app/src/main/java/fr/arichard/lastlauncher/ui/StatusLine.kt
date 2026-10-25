package fr.arichard.lastlauncher.ui

/**
 * The launcher's signature widget: a one-line terminal status readout shown under the
 * date. Which tokens appear — and their fixed left-to-right order — is chosen by the
 * user; battery is off by default since the system status bar already shows it.
 *
 * Example:  › wifi:Home · bt · ⏰07:00 · ▸ 1h20 Dentist · ↑23 · ▤ 41.2G · up 3d4h
 *
 * Purely a formatter so it stays unit testable; the host gathers the live [Values].
 */
object StatusLine {

    enum class Net(val token: String) {
        WIFI("wifi"), CELLULAR("cell"), OFFLINE("off")
    }

    /** Sound profile; [NORMAL] renders nothing — only the quiet states are worth a token. */
    enum class Ringer(val token: String?) {
        NORMAL(null), VIBRATE("vib"), SILENT("mute"), DND("dnd")
    }

    const val BATTERY = "battery"
    const val NETWORK = "network"
    const val WIFI_NAME = "wifi_name"
    const val BT = "bt"
    const val RINGER = "ringer"
    const val ALARM = "alarm"
    const val EVENT = "event"
    const val LAUNCHES = "launches"
    const val STORAGE = "storage"
    const val RAM = "ram"
    const val DB = "db"
    const val UPTIME = "uptime"

    /** Canonical order tokens are rendered in, regardless of selection order. */
    val ALL_TOKENS = listOf(
        BATTERY, NETWORK, WIFI_NAME, BT, RINGER, ALARM, EVENT, LAUNCHES, STORAGE, RAM, DB, UPTIME
    )

    val DEFAULT_TOKENS = setOf(NETWORK, ALARM, LAUNCHES)

    /** Longest event title shown after the countdown; the agenda has the full one. */
    const val EVENT_TITLE_MAX = 14

    data class Values(
        val batteryPercent: Int,
        val charging: Boolean,
        val net: Net,
        val nextAlarm: String?,
        val launchesToday: Int,
        val freeStorageGb: Double,
        val dbBytes: Long = 0,
        /** Joined network's name when readable (SSID option + precise location), else null. */
        val wifiName: String? = null,
        /** A headset / A2DP audio device is connected. */
        val btAudio: Boolean = false,
        val ringer: Ringer = Ringer.NORMAL,
        /** Minutes until the agenda's next event (≤ 0 = ongoing), null when none. */
        val nextEventMinutes: Int? = null,
        val nextEventTitle: String? = null,
        val freeRamGb: Double = 0.0,
        val uptimeMs: Long = 0,
    )

    fun build(enabled: Set<String>, v: Values): String =
        "› " + segments(enabled, v).joinToString("  ·  ") { it.second }

    /** Rendered (tokenId, text) pairs, so hosts can attach per-token tap actions. */
    fun segments(enabled: Set<String>, v: Values): List<Pair<String, String>> =
        ALL_TOKENS.filter { it in enabled }
            .mapNotNull { id -> token(id, v, enabled)?.let { id to it } }

    private fun token(id: String, v: Values, enabled: Set<String>): String? = when (id) {
        BATTERY -> "${v.batteryPercent}%" + if (v.charging) "⚡" else ""
        // With the name option on, the Wi-Fi token carries the network's name.
        NETWORK -> v.net.token + wifiSuffix(v, enabled)
        // Standalone name only when the network token itself is off.
        WIFI_NAME -> if (NETWORK in enabled) null else wifiName(v)
        BT -> if (v.btAudio) "bt" else null
        RINGER -> v.ringer.token
        ALARM -> v.nextAlarm?.let { "⏰$it" }
        EVENT -> v.nextEventMinutes?.let { "▸ " + formatEvent(it, v.nextEventTitle) }
        LAUNCHES -> if (v.launchesToday > 0) "↑${v.launchesToday}" else null
        STORAGE -> "▤ ${"%.1f".format(java.util.Locale.US, v.freeStorageGb)}G"
        RAM -> "⌁ ${"%.1f".format(java.util.Locale.US, v.freeRamGb)}G"
        DB -> if (v.dbBytes > 0) "◇ ${formatBytes(v.dbBytes)}" else null
        UPTIME -> if (v.uptimeMs > 0) "up ${formatDuration(v.uptimeMs / 60_000L)}" else null
        else -> null
    }

    private fun wifiName(v: Values): String? =
        if (v.net == Net.WIFI) v.wifiName?.takeIf { it.isNotBlank() } else null

    private fun wifiSuffix(v: Values, enabled: Set<String>): String =
        if (WIFI_NAME in enabled) wifiName(v)?.let { ":$it" } ?: "" else ""

    /** `now Dentist`, `1h20 Dentist`, `2d3h` — title cut at [EVENT_TITLE_MAX]. */
    internal fun formatEvent(minutes: Int, title: String?): String {
        val time = if (minutes <= 0) "now" else formatDuration(minutes.toLong())
        val short = title?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.length > EVENT_TITLE_MAX) it.take(EVENT_TITLE_MAX - 1).trimEnd() + "…" else it
        }
        return if (short == null) time else "$time $short"
    }

    /** Compact duration from minutes: 12m, 1h20, 3d4h (two units at most, never zero). */
    internal fun formatDuration(totalMinutes: Long): String {
        val minutes = totalMinutes.coerceAtLeast(0)
        val days = minutes / (24 * 60)
        val hours = minutes / 60 % 24
        val mins = minutes % 60
        return when {
            days > 0 -> if (hours > 0) "${days}d${hours}h" else "${days}d"
            hours > 0 -> "${hours}h" + "%02d".format(java.util.Locale.US, mins)
            else -> "${mins}m"
        }
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
