package fr.arichard.lastlauncher.backup

import fr.arichard.lastlauncher.settings.Prefs

/**
 * Pure logic for exporting/importing the user's settings (gestures, drawers,
 * appearance, agenda options, pinned/favorite/hidden apps, etc.) as a single
 * shareable file — for moving to a new phone. This is settings only, not usage
 * history: the prediction engine has its own separate export (see
 * [fr.arichard.lastlauncher.predict.PredictionEngine.exportData]) for that,
 * and it isn't included here. The actual file/JSON/Android plumbing lives in
 * [ConfigBackupIO]; this file has no Android imports so it's plain-JVM testable.
 *
 * Deliberately excludes device-local/transient state: cached weather, "have we
 * asked" permission flags, the Wi-Fi hashing salt, timestamps, and the
 * prediction engine's own signals. Calendar exclusions are the one setting
 * stored as a device-local id (a `CalendarContract` row id, meaningless on
 * another device or even a fresh account sign-in) rather than something
 * portable, so they travel as display names and get best-effort re-matched
 * against the destination device's calendars on import.
 *
 * [PORTABLE_KEYS] is an allowlist, not a denylist, on purpose: a future
 * preference nobody remembers to add here simply isn't exported yet, rather
 * than leaking device-local state by default. If a key's *meaning* or
 * encoding ever needs to change incompatibly, bump [SCHEMA] and branch on the
 * imported file's schema number — nothing needs that today, so nothing here
 * pre-builds it.
 */
object ConfigBackup {

    const val SCHEMA = 1

    /** Preference keys carried over as-is; see the class doc for what's excluded and why. */
    val PORTABLE_KEYS: Set<String> = setOf(
        Prefs.KEY_KEYBOARD_ALWAYS, Prefs.KEY_DOUBLE_TAP_LOCK, Prefs.KEY_SWIPE_DOWN,
        Prefs.KEY_GESTURE_HINTS, Prefs.KEY_HAPTICS,
        Prefs.KEY_GESTURE_LR_1, Prefs.KEY_GESTURE_LR_2, Prefs.KEY_GESTURE_RL_1, Prefs.KEY_GESTURE_RL_2,
        Prefs.KEY_PREDICTIONS, Prefs.KEY_BT_SIGNAL, Prefs.KEY_SSID_SIGNAL, Prefs.KEY_BOOSTED_APPS,
        Prefs.KEY_ANIMATIONS, Prefs.KEY_SHOW_CLOCK, Prefs.KEY_SHOW_STATUS_LINE,
        Prefs.KEY_STATUS_TOKENS, Prefs.KEY_ACCENT, Prefs.KEY_DIM,
        Prefs.KEY_AUTO_UPDATE,
        Prefs.KEY_CLOCK_TAP,
        Prefs.KEY_WEATHER_ENABLED, Prefs.KEY_WEATHER_UNITS, Prefs.KEY_WEATHER_STYLE,
        Prefs.KEY_WEATHER_TAP, Prefs.KEY_WEATHER_BESIDE_CLOCK, Prefs.KEY_WEATHER_CLOCK_STYLE,
        Prefs.KEY_TICKER_TWO_LINES, Prefs.KEY_TICKER_SECONDS,
        Prefs.KEY_FAVORITES, Prefs.KEY_ONBOARDING_DONE, Prefs.KEY_HIDDEN_APPS, Prefs.KEY_SEARCH_MODE,
        Prefs.KEY_DRAWER_COUNT,
        Prefs.KEY_NOTIF_BADGES, Prefs.KEY_MESSAGE_TICKER, Prefs.KEY_MUSIC_WIDGET,
        Prefs.KEY_NEW_APP_ENABLED, Prefs.KEY_NEW_APP_SIDE, Prefs.KEY_NEW_APP_HOURS,
        Prefs.KEY_PARK_ENABLED, Prefs.KEY_PARK_HOURS, Prefs.KEY_PARK_MULTI,
        Prefs.KEY_AGENDA_ENABLED, Prefs.KEY_AGENDA_ON_GESTURE, Prefs.KEY_AGENDA_LINES,
        Prefs.KEY_AGENDA_TEXT_SIZE, Prefs.KEY_AGENDA_ALL_DAY, Prefs.KEY_AGENDA_ALL_DAY_HIGHLIGHT,
        Prefs.KEY_AGENDA_COUNTDOWN, Prefs.KEY_AGENDA_DAYS, Prefs.KEY_AGENDA_TAP,
    ) + (0 until Prefs.MAX_DRAWERS).flatMap {
        listOf(Prefs.KEY_DRAWER_NAME_PREFIX + it, Prefs.KEY_DRAWER_APPS_PREFIX + it)
    }

    data class ExportPayload(
        val schema: Int,
        val appVersion: String,
        val exportedAt: Long,
        val prefs: Map<String, Any?>,
        val excludedCalendarNames: List<String>,
    )

    data class ImportResult(
        val applied: Map<String, Any?>,
        val skippedUnknownKeys: Int,
        val matchedCalendarIds: Set<String>,
        val unmatchedCalendarCount: Int,
    )

    /** Keeps only the allowlisted, portable keys — the actual export payload. */
    fun buildPayload(
        rawPrefs: Map<String, Any?>,
        excludedCalendarNames: List<String>,
        appVersion: String,
        now: Long = System.currentTimeMillis(),
    ): ExportPayload = ExportPayload(
        SCHEMA, appVersion, now, rawPrefs.filterKeys { it in PORTABLE_KEYS }, excludedCalendarNames,
    )

    /**
     * Filters an imported file's preference map down to keys this build still
     * recognizes (an older build reading a newer export just skips whatever's
     * new — [ImportResult.skippedUnknownKeys] reports how many), and re-matches
     * previously-excluded calendars by display name against [destinationCalendars]
     * (name -> id) since their old numeric ids mean nothing on this device.
     */
    fun resolveImport(
        payloadPrefs: Map<String, Any?>,
        excludedCalendarNames: List<String>,
        destinationCalendars: Map<String, String>,
    ): ImportResult {
        val applied = HashMap<String, Any?>()
        var skipped = 0
        for ((key, value) in payloadPrefs) {
            if (key !in PORTABLE_KEYS) {
                skipped++
                continue
            }
            // A Set<String> round-trips through JSON as a plain array, so it comes
            // back here as a List — restore it to the Set shape SharedPreferences expects.
            applied[key] = if (value is List<*>) value.filterIsInstance<String>().toSet() else value
        }
        val matchedIds = excludedCalendarNames.mapNotNull { destinationCalendars[it] }.toSet()
        val unmatched = (excludedCalendarNames.toSet() - destinationCalendars.keys).size
        return ImportResult(applied, skipped, matchedIds, unmatched)
    }
}
