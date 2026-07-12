package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.ui.StatusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusLineTest {

    @Test
    fun chargingShowsBoltAndAllTokens() {
        val line = StatusLine.build(82, true, StatusLine.Net.WIFI, "07:00", 23)
        assertTrue(line.contains("82%⚡"))
        assertTrue(line.contains("wifi"))
        assertTrue(line.contains("⏰07:00"))
        assertTrue(line.contains("↑23"))
    }

    @Test
    fun optionalTokensAreOmittedWhenEmpty() {
        val line = StatusLine.build(50, false, StatusLine.Net.OFFLINE, null, 0)
        assertFalse(line.contains("⚡"))
        assertFalse(line.contains("⏰"))
        assertFalse(line.contains("↑"))
        assertTrue(line.contains("off"))
        assertTrue(line.contains("50%"))
    }
}
