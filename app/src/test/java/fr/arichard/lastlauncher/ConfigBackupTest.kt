package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.backup.ConfigBackup
import fr.arichard.lastlauncher.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupTest {

    @Test
    fun buildPayloadKeepsOnlyPortableKeys() {
        val raw = mapOf(
            Prefs.KEY_HAPTICS to true,
            Prefs.KEY_ACCENT to "cyan",
            Prefs.KEY_SSID_SALT to "device-local-secret", // not portable
            Prefs.KEY_LAST_LAUNCHED_TS to 123456789L, // transient
        )
        val payload = ConfigBackup.buildPayload(raw, emptyList(), "1.19.0", now = 42L)
        assertEquals(setOf(Prefs.KEY_HAPTICS, Prefs.KEY_ACCENT), payload.prefs.keys)
        assertEquals(ConfigBackup.SCHEMA, payload.schema)
        assertEquals("1.19.0", payload.appVersion)
        assertEquals(42L, payload.exportedAt)
    }

    @Test
    fun resolveImportSkipsUnknownKeysAndCoercesListsBackToSets() {
        val payloadPrefs = mapOf(
            Prefs.KEY_HAPTICS to true,
            Prefs.KEY_HIDDEN_APPS to listOf("com.a", "com.b"), // Set round-tripped through JSON
            "some_future_key_this_build_does_not_know" to "x",
        )
        val result = ConfigBackup.resolveImport(payloadPrefs, emptyList(), emptyMap())
        assertEquals(true, result.applied[Prefs.KEY_HAPTICS])
        assertEquals(setOf("com.a", "com.b"), result.applied[Prefs.KEY_HIDDEN_APPS])
        assertFalse(result.applied.containsKey("some_future_key_this_build_does_not_know"))
        assertEquals(1, result.skippedUnknownKeys)
    }

    @Test
    fun resolveImportRemapsCalendarExclusionsByName() {
        val result = ConfigBackup.resolveImport(
            emptyMap(),
            excludedCalendarNames = listOf("Birthdays", "Work", "Gone Calendar"),
            destinationCalendars = mapOf("Birthdays" to "7", "Work" to "3"),
        )
        assertEquals(setOf("7", "3"), result.matchedCalendarIds)
        assertEquals(1, result.unmatchedCalendarCount)
    }

    @Test
    fun resolveImportWithNoDestinationCalendarsMatchesNothing() {
        val result = ConfigBackup.resolveImport(
            emptyMap(), listOf("Birthdays"), destinationCalendars = emptyMap(),
        )
        assertTrue(result.matchedCalendarIds.isEmpty())
        assertEquals(1, result.unmatchedCalendarCount)
    }

    @Test
    fun portableKeysIncludeEveryDrawerSlot() {
        for (i in 0 until Prefs.MAX_DRAWERS) {
            assertTrue(ConfigBackup.PORTABLE_KEYS.contains(Prefs.KEY_DRAWER_NAME_PREFIX + i))
            assertTrue(ConfigBackup.PORTABLE_KEYS.contains(Prefs.KEY_DRAWER_APPS_PREFIX + i))
        }
    }

    @Test
    fun portableKeysExcludeTransientAndDeviceLocalState() {
        val excluded = listOf(
            Prefs.KEY_SSID_SALT, Prefs.KEY_LAST_LAUNCHED, Prefs.KEY_LAST_LAUNCHED_TS,
            Prefs.KEY_CALIBRATION, Prefs.KEY_NEW_APPS, Prefs.KEY_PARKED,
            Prefs.KEY_WEATHER_TEMP, Prefs.KEY_WEATHER_CODE, Prefs.KEY_WEATHER_FETCH,
            Prefs.KEY_LAST_UPDATE_CHECK, Prefs.KEY_UPDATE_DEFERRED,
            Prefs.KEY_BT_PERM_ASKED, Prefs.KEY_CAL_PERM_ASKED,
            Prefs.KEY_AGENDA_EXCLUDED, // carried separately, by name
            Prefs.KEY_DRAWER_APPS, // legacy, migrated
        )
        for (key in excluded) {
            assertFalse("$key should not be portable", ConfigBackup.PORTABLE_KEYS.contains(key))
        }
    }
}
