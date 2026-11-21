package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.predict.ContextSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextSignalsTest {

    @Test
    fun cleanSsidStripsTheSystemQuotes() {
        assertEquals("Home", ContextSignals.cleanSsid("\"Home\""))
        assertEquals("Café du coin", ContextSignals.cleanSsid(" \"Café du coin\" "))
    }

    @Test
    fun cleanSsidRejectsPlaceholders() {
        // What the system hands an app that may not see the name right now.
        assertNull(ContextSignals.cleanSsid("<unknown ssid>"))
        assertNull(ContextSignals.cleanSsid("\"<Unknown SSID>\""))
        assertNull(ContextSignals.cleanSsid("\"\""))
        assertNull(ContextSignals.cleanSsid("  "))
        assertNull(ContextSignals.cleanSsid(null))
    }
}
