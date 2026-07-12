package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.command.CommandProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandProcessorTest {

    private val catalog = listOf(
        CommandProcessor.QuickAction("lock", "Lock screen", 0, "secure"),
        CommandProcessor.QuickAction("wifi", "Wi-Fi settings", 0, "network"),
        CommandProcessor.QuickAction("flashlight", "Flashlight", 0, "torch"),
    )

    private fun parse(q: String) = CommandProcessor.parse(q, catalog, 1, 2)

    @Test
    fun evaluatesArithmeticWithPrecedence() {
        assertEquals(14.0, CommandProcessor.evaluate("2+3*4")!!, 0.0001)
        assertEquals(3.5, CommandProcessor.evaluate("(3+4)/2")!!, 0.0001)
        assertEquals(100.0, CommandProcessor.evaluate("1200/12")!!, 0.0001)
        assertEquals(2.0, CommandProcessor.evaluate("12 % 5")!!, 0.0001)
    }

    @Test
    fun rejectsInvalidExpressions() {
        assertNull(CommandProcessor.evaluate("2+"))
        assertNull(CommandProcessor.evaluate("2**2"))
        assertNull(CommandProcessor.evaluate("hello"))
        assertNull(CommandProcessor.evaluate("1/0")) // infinite, not finite
    }

    @Test
    fun calcRowAppearsOnlyForRealExpressions() {
        val calc = parse("12*7")
        assertEquals(1, calc.size)
        assertEquals("84", calc[0].title)
        assertTrue(parse("5").isEmpty())      // a bare number is not a calculation
        assertTrue(parse("signal").isEmpty()) // plain text is an app search
    }

    @Test
    fun detectsUrls() {
        assertTrue(CommandProcessor.looksLikeUrl("example.com"))
        assertTrue(CommandProcessor.looksLikeUrl("https://sub.example.com/path?q=1"))
        assertFalse(CommandProcessor.looksLikeUrl("just text"))
        assertFalse(CommandProcessor.looksLikeUrl("1.5"))
        val row = parse("example.com").single()
        assertTrue(row.action is CommandProcessor.Action.OpenUrl)
        assertEquals("https://example.com", (row.action as CommandProcessor.Action.OpenUrl).url)
    }

    @Test
    fun commandPaletteFiltersCatalog() {
        assertEquals(3, parse(">").size)
        val wifi = parse(">net")
        assertEquals(1, wifi.size)
        assertEquals("wifi", (wifi[0].action as CommandProcessor.Action.Quick).id)
        assertEquals("torch", "torch")
        assertEquals(1, parse(">torch").size)
    }
}
