package fr.arichard.lastlauncher.apps

import java.text.Normalizer

/** One launchable activity. */
data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
) {
    val componentKey: String = "$packageName/$activityName"

    /** Lowercase, accent-stripped label used for matching. */
    val normalizedLabel: String = normalize(label)

    /** First letter of each word, e.g. "Google Maps" -> "gm". */
    val initials: String = normalizedLabel
        .split(' ', '-', '.')
        .filter { it.isNotEmpty() }
        .joinToString("") { it.first().toString() }

    companion object {
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .lowercase()
                .trim()
    }
}
