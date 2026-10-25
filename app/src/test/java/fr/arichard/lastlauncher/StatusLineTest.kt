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

    // ------------------------------------------------------- new fields (1.26)

    @Test
    fun wifiNameRidesTheNetworkTokenOrStandsAlone() {
        val v = values(net = StatusLine.Net.WIFI).copy(wifiName = "Home")
        // Both on: one segment, the name suffixed to the network token.
        val both = StatusLine.segments(setOf(StatusLine.NETWORK, StatusLine.WIFI_NAME), v)
        assertEquals(listOf(StatusLine.NETWORK to "wifi:Home"), both)
        // Name only: its own segment, so the tap can still open Wi-Fi settings.
        val alone = StatusLine.segments(setOf(StatusLine.WIFI_NAME), v)
        assertEquals(listOf(StatusLine.WIFI_NAME to "Home"), alone)
        // No name readable: plain token, nothing standalone.
        val unnamed = values(net = StatusLine.Net.WIFI)
        assertTrue(StatusLine.build(setOf(StatusLine.NETWORK, StatusLine.WIFI_NAME), unnamed).endsWith("wifi"))
        assertTrue(StatusLine.segments(setOf(StatusLine.WIFI_NAME), unnamed).isEmpty())
        // Off Wi-Fi a stale name never shows.
        val cell = values(net = StatusLine.Net.CELLULAR).copy(wifiName = "Home")
        assertEquals("› cell", StatusLine.build(setOf(StatusLine.NETWORK, StatusLine.WIFI_NAME), cell))
    }

    @Test
    fun quietStatesOnlyWhenActive() {
        val all = setOf(StatusLine.BT, StatusLine.RINGER)
        assertEquals("› ", StatusLine.build(all, values()))
        assertEquals("› bt", StatusLine.build(all, values().copy(btAudio = true)))
        assertEquals("› vib", StatusLine.build(all, values().copy(ringer = StatusLine.Ringer.VIBRATE)))
        assertEquals("› mute", StatusLine.build(all, values().copy(ringer = StatusLine.Ringer.SILENT)))
        assertEquals(
            "› bt  ·  dnd",
            StatusLine.build(all, values().copy(btAudio = true, ringer = StatusLine.Ringer.DND))
        )
    }

    @Test
    fun durationsAreCompactAndNeverZero() {
        assertEquals("0m", StatusLine.formatDuration(0))
        assertEquals("12m", StatusLine.formatDuration(12))
        assertEquals("1h05", StatusLine.formatDuration(65))
        assertEquals("23h59", StatusLine.formatDuration(23 * 60 + 59))
        assertEquals("1d", StatusLine.formatDuration(24 * 60 + 30)) // minutes dropped past a day
        assertEquals("3d4h", StatusLine.formatDuration(3 * 24 * 60 + 4 * 60 + 59))
        assertEquals("0m", StatusLine.formatDuration(-5))
    }

    @Test
    fun uptimeTokenFromElapsedMillis() {
        val line = StatusLine.build(setOf(StatusLine.UPTIME), values().copy(uptimeMs = 3 * 86_400_000L + 4 * 3_600_000L))
        assertEquals("› up 3d4h", line)
        assertEquals("› ", StatusLine.build(setOf(StatusLine.UPTIME), values()))
    }

    @Test
    fun eventCountdownWithShortTitle() {
        assertEquals("1h20 Dentist", StatusLine.formatEvent(80, "Dentist"))
        assertEquals("now Dentist", StatusLine.formatEvent(0, "Dentist"))
        assertEquals("now Dentist", StatusLine.formatEvent(-30, "Dentist"))
        assertEquals("2d3h", StatusLine.formatEvent(2 * 24 * 60 + 3 * 60, "  "))
        assertEquals("5m", StatusLine.formatEvent(5, null))
        // Long titles are cut with an ellipsis at EVENT_TITLE_MAX characters.
        val cut = StatusLine.formatEvent(5, "Quarterly planning review with everyone")
        assertEquals("5m Quarterly pla…", cut)
        val line = StatusLine.build(
            setOf(StatusLine.EVENT), values().copy(nextEventMinutes = 80, nextEventTitle = "Dentist")
        )
        assertEquals("› ▸ 1h20 Dentist", line)
        assertEquals("› ", StatusLine.build(setOf(StatusLine.EVENT), values()))
    }

    @Test
    fun ramUsesDotDecimalRegardlessOfLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            val line = StatusLine.build(setOf(StatusLine.RAM), values().copy(freeRamGb = 2.14))
            assertEquals("› ⌁ 2.1G", line)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun newTokensKeepCanonicalOrder() {
        val v = values().copy(
            wifiName = "Home", btAudio = true, ringer = StatusLine.Ringer.DND,
            nextEventMinutes = 80, nextEventTitle = "Dentist", freeRamGb = 2.1, uptimeMs = 3_600_000L,
            dbBytes = 218_000,
        )
        val ids = StatusLine.segments(StatusLine.ALL_TOKENS.toSet(), v).map { it.first }
        assertEquals(StatusLine.ALL_TOKENS - StatusLine.WIFI_NAME, ids)
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
