package fr.arichard.lastlauncher.ui

/**
 * The launcher's signature widget: a one-line terminal status readout shown under the
 * date. Purely a formatter so it stays unit testable; the host gathers live values.
 *
 * Example:  › 82%⚡ · wifi · ⏰07:00 · ↑23
 */
object StatusLine {

    enum class Net(val token: String) {
        WIFI("wifi"), CELLULAR("cell"), OFFLINE("off")
    }

    fun build(
        batteryPercent: Int,
        charging: Boolean,
        net: Net,
        nextAlarm: String?,
        launchesToday: Int,
    ): String {
        val tokens = ArrayList<String>(4)
        tokens.add("$batteryPercent%" + if (charging) "⚡" else "")
        tokens.add(net.token)
        if (!nextAlarm.isNullOrEmpty()) tokens.add("⏰$nextAlarm")
        if (launchesToday > 0) tokens.add("↑$launchesToday")
        return "› " + tokens.joinToString("  ·  ")
    }
}
