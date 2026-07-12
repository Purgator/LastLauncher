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

    /** Canonical order tokens are rendered in, regardless of selection order. */
    val ALL_TOKENS = listOf(BATTERY, NETWORK, ALARM, LAUNCHES, STORAGE)

    val DEFAULT_TOKENS = setOf(NETWORK, ALARM, LAUNCHES)

    data class Values(
        val batteryPercent: Int,
        val charging: Boolean,
        val net: Net,
        val nextAlarm: String?,
        val launchesToday: Int,
        val freeStorageGb: Double,
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
        STORAGE -> "▤ ${"%.1f".format(v.freeStorageGb)}G"
        else -> null
    }
}
