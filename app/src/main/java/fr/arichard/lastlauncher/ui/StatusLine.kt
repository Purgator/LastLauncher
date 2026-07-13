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

    fun build(enabled: Set<String>, v: Values): String {
        val tokens = ALL_TOKENS.filter { it in enabled }.mapNotNull { token(it, v) }
        return "› " + tokens.joinToString("  ·  ")
    }

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
}
