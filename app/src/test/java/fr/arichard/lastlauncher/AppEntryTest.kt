package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.apps.AppEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class AppEntryTest {

    @Test
    fun normalizationStripsAccentsAndCase() {
        assertEquals("telephone", AppEntry.normalize("Téléphone"))
        assertEquals("appareil photo", AppEntry.normalize("  Appareil Photo "))
    }

    @Test
    fun initialsComeFromEachWord() {
        val entry = AppEntry("com.google.android.apps.maps", "Main", "Google Maps")
        assertEquals("gm", entry.initials)
    }
}
