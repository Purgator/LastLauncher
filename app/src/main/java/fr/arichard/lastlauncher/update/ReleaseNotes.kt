package fr.arichard.lastlauncher.update

import android.content.Context
import fr.arichard.lastlauncher.R
import fr.arichard.lastlauncher.settings.Prefs

/**
 * Bundled, localized release notes and the versions still awaiting the user's attention.
 * Notes ship inside the APK (no network), so they are there the first time the updated
 * app opens.
 */
object ReleaseNotes {

    /** Last version released before in-app release notes existed. */
    internal const val BASELINE_VERSION = "1.29.0"

    // Add each release here, newest first, with matching EN + FR arrays in
    // values/release_notes.xml and values-fr/release_notes.xml.
    private val entries = listOf(
        "1.31.0" to R.array.release_notes_1_31_0,
        "1.30.0" to R.array.release_notes_1_30_0,
    )

    internal val versions: List<String> get() = entries.map { it.first }

    /**
     * Known releases newer than [lastSeen] and no newer than [current], newest first.
     * A brand-new install sees nothing; an upgrade from before the feature (no
     * [lastSeen]) sees everything after [BASELINE_VERSION].
     */
    internal fun versionsToShow(
        available: List<String>,
        lastSeen: String?,
        current: String,
        freshInstall: Boolean,
    ): List<String> {
        if (lastSeen == null && freshInstall) return emptyList()
        val from = lastSeen ?: BASELINE_VERSION
        if (!UpdateManager.isNewer(current, from)) return emptyList()
        return available
            .filter { UpdateManager.isNewer(it, from) && !UpdateManager.isNewer(it, current) }
            .distinct()
            .sortedWith { a, b -> UpdateManager.compareVersions(b, a) }
    }

    /** Notes for every unseen version (newest first); empty when the setting is off. */
    fun pending(context: Context): List<Pair<String, List<String>>> {
        val prefs = Prefs(context)
        if (!prefs.showReleaseNotes) return emptyList()
        val freshInstall = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        } catch (e: Exception) {
            false
        }
        val shown = versionsToShow(
            versions, prefs.lastSeenVersion, UpdateManager.currentVersion(context), freshInstall,
        )
        return shown.mapNotNull { v ->
            entries.firstOrNull { it.first == v }?.let { (_, id) ->
                v to context.resources.getStringArray(id).toList()
            }
        }
    }

    /** The whole bundled history, newest first. */
    fun all(context: Context): List<Pair<String, List<String>>> =
        entries.map { (v, id) -> v to context.resources.getStringArray(id).toList() }

    /** Records the installed version once its notes were dismissed (or there were none). */
    fun markSeen(context: Context) {
        Prefs(context).lastSeenVersion = UpdateManager.currentVersion(context)
    }
}
