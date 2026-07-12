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

    /**
     * Meaningful words from the package id (e.g. com.spotify.music -> [spotify, music]),
     * so a search can match what an app *is* even when its label differs — a cheap,
     * offline stand-in for description search.
     */
    val packageTokens: List<String> = packageName.lowercase()
        .split('.')
        .filter { it.length >= 3 && it !in GENERIC_TOKENS }

    companion object {
        private val GENERIC_TOKENS =
            setOf("com", "org", "net", "android", "app", "apps", "mobile", "www")

        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
                .lowercase()
                .trim()
    }
}
