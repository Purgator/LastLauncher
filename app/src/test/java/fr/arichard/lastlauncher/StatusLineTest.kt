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

    // ------------------------------------------------- next-alarm reconciliation

    private fun min(h: Int, m: Int) = h * 60 + m

    @Test
    fun parsesOemNextAlarmFormats() {
        assertEquals(min(7, 30), StatusLine.parseTimeOfDay("ven. 07:30"))
        assertEquals(min(7, 5), StatusLine.parseTimeOfDay("7h05"))
        assertEquals(min(8, 0), StatusLine.parseTimeOfDay("Tue 8:00"))
        assertEquals(min(20, 0), StatusLine.parseTimeOfDay("8:00 PM"))
        assertEquals(min(20, 0), StatusLine.parseTimeOfDay("Tue 8:00 p.m."))
        // Narrow no-break space before the meridiem (CLDR on recent Android).
        assertEquals(min(20, 0), StatusLine.parseTimeOfDay("8:00\u202FPM"))
        assertEquals(min(0, 15), StatusLine.parseTimeOfDay("12:15 AM"))
        assertEquals(min(12, 15), StatusLine.parseTimeOfDay("12:15 PM"))
        assertEquals(null, StatusLine.parseTimeOfDay(null))
        assertEquals(null, StatusLine.parseTimeOfDay(""))
        assertEquals(null, StatusLine.parseTimeOfDay("no alarm"))
        assertEquals(null, StatusLine.parseTimeOfDay("99:99"))
    }

    @Test
    fun formattedTimeWinsInsideThePrewakeWindow() {
        // MIUI: trigger reports 07:50 for an alarm the Clock app shows at 08:00.
        assertEquals(
            min(8, 0), StatusLine.reconcileAlarmMinutes(min(8, 0), min(7, 50))
        )
        // Identical values are trivially consistent.
        assertEquals(
            min(8, 0), StatusLine.reconcileAlarmMinutes(min(8, 0), min(8, 0))
        )
        // Pre-wake window wraps midnight: trigger 23:55 for a 00:05 alarm.
        assertEquals(
            min(0, 5), StatusLine.reconcileAlarmMinutes(min(0, 5), min(23, 55))
        )
    }

    @Test
    fun staleOrTimezoneShiftedFormattedStringIsDiscarded() {
        // A two-hour shift (UTC-formatted string on a UTC+2 device) must lose to
        // the framework trigger time.
        assertEquals(
            min(8, 0), StatusLine.reconcileAlarmMinutes(min(6, 0), min(8, 0))
        )
        // A stale string pointing at some old alarm loses too.
        assertEquals(
            min(8, 0), StatusLine.reconcileAlarmMinutes(min(14, 30), min(8, 0))
        )
        // Formatted BEFORE the trigger is never a pre-wake shift — discard.
        assertEquals(
            min(8, 0), StatusLine.reconcileAlarmMinutes(min(7, 50), min(8, 0))
        )
        // No parseable string: trigger time as-is.
        assertEquals(
            min(8, 0), StatusLine.reconcileAlarmMinutes(null, min(8, 0))
        )
    }
}
