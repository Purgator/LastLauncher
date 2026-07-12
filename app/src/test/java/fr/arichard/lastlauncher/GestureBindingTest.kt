package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.gesture.GestureAction
import fr.arichard.lastlauncher.gesture.GestureBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureBindingTest {

    @Test
    fun simpleActionRoundTrips() {
        val binding = GestureBinding(GestureAction.FLASHLIGHT)
        assertEquals("flashlight", binding.encode())
        assertEquals(binding, GestureBinding.decode("flashlight"))
    }

    @Test
    fun openAppKeepsComponentKey() {
        val binding = GestureBinding(GestureAction.OPEN_APP, "com.app/.Main")
        assertEquals("open_app:com.app/.Main", binding.encode())
        val decoded = GestureBinding.decode(binding.encode())
        assertEquals(GestureAction.OPEN_APP, decoded.action)
        assertEquals("com.app/.Main", decoded.appKey)
    }

    @Test
    fun appDrawerCarriesDrawerIndex() {
        val binding = GestureBinding(GestureAction.APP_DRAWER, "2")
        assertEquals("app_drawer:2", binding.encode())
        val decoded = GestureBinding.decode(binding.encode())
        assertEquals(GestureAction.APP_DRAWER, decoded.action)
        assertEquals(2, decoded.drawerIndex)
    }

    @Test
    fun appDrawerWithoutPayloadDefaultsToFirst() {
        assertEquals(0, GestureBinding.decode("app_drawer").drawerIndex)
    }

    @Test
    fun blankOrUnknownDecodesToNone() {
        assertEquals(GestureAction.NONE, GestureBinding.decode(null).action)
        assertEquals(GestureAction.NONE, GestureBinding.decode("").action)
        assertEquals(GestureAction.NONE, GestureBinding.decode("bogus").action)
        assertNull(GestureBinding.decode("lock").appKey)
    }
}
