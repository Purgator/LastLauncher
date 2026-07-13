package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.ui.StatusLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusLineTest {

    private fun values(
        percent: Int = 82,
        charging: Boolean = true,
        net: StatusLine.Net = StatusLine.Net.WIFI,
        alarm: String? = "07:00",
        launches: Int = 23,
        storage: Double = 41.2,
        dbBytes: Long = 0,
    ) = StatusLine.Values(percent, charging, net, alarm, launches, storage, dbBytes)

    @Test
    fun rendersOnlyEnabledTokensInCanonicalOrder() {
        val line = StatusLine.build(
            setOf(StatusLine.NETWORK, StatusLine.BATTERY, StatusLine.STORAGE), values()
        )
        // Battery comes before network before storage regardless of set order.
        assertTrue(line.indexOf("82%⚡") < line.indexOf("wifi"))
        assertTrue(line.indexOf("wifi") < line.indexOf("41.2G"))
        assertFalse(line.contains("⏰"))  // alarm not enabled
        assertFalse(line.contains("↑"))   // launches not enabled
    }

    @Test
    fun chargingBoltAndOptionalTokens() {
        val line = StatusLine.build(StatusLine.ALL_TOKENS.toSet(), values())
        assertTrue(line.contains("82%⚡"))
        assertTrue(line.contains("⏰07:00"))
        assertTrue(line.contains("↑23"))
    }

    @Test
    fun storageUsesDotDecimalRegardlessOfLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE) // comma decimal locale
            val line = StatusLine.build(setOf(StatusLine.STORAGE), values(storage = 41.2))
            assertTrue(line.contains("41.2G"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun dbTokenFormatsCompactBytes() {
        assertEquals("87K", StatusLine.formatBytes(87_400))
        assertEquals("1.2M", StatusLine.formatBytes(1_230_000))
        assertEquals("1K", StatusLine.formatBytes(400))
        val line = StatusLine.build(setOf(StatusLine.DB), values(dbBytes = 218_000))
        assertTrue(line.contains("◇ 218K"))
    }

    @Test
    fun emptyOptionalsAreOmitted() {
        val line = StatusLine.build(
            StatusLine.ALL_TOKENS.toSet(),
            values(charging = false, net = StatusLine.Net.OFFLINE, alarm = null, launches = 0)
        )
        assertFalse(line.contains("⚡"))
        assertFalse(line.contains("⏰"))
        assertFalse(line.contains("↑"))
        assertTrue(line.contains("off"))
    }
}
