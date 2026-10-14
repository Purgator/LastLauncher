package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.predict.AmbientState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientStateTest {

    @Test
    fun formatAndParseRoundTripKeepsOrder() {
        val values = linkedMapOf("dnd" to 2, "chg" to 1, "alarm" to 372, "wifi" to 0)
        val s = AmbientState.format(values)
        assertEquals("dnd=2,chg=1,alarm=372,wifi=0", s)
        assertEquals(values, AmbientState.parse(s))
        assertEquals(listOf("dnd", "chg", "alarm", "wifi"), AmbientState.parse(s).keys.toList())
    }

    @Test
    fun emptyMapFormatsToNullAndNullParsesToEmpty() {
        assertNull(AmbientState.format(emptyMap()))
        assertTrue(AmbientState.parse(null).isEmpty())
        assertTrue(AmbientState.parse("").isEmpty())
    }

    @Test
    fun parseSkipsMalformedFragments() {
        val parsed = AmbientState.parse("dnd=1,,=5,chg,alarm=abc,wifi=1,=,bt=0=1")
        assertEquals(mapOf("dnd" to 1, "wifi" to 1), parsed)
    }

    @Test
    fun parseAcceptsNegativeAndLargeValues() {
        assertEquals(mapOf("x" to -1, "y" to 1440), AmbientState.parse("x=-1,y=1440"))
    }
}
