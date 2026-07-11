package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.update.UpdateManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun newerVersionsAreDetected() {
        assertTrue(UpdateManager.isNewer("1.0.1", "1.0.0"))
        assertTrue(UpdateManager.isNewer("1.10.0", "1.9.9"))
        assertTrue(UpdateManager.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun equalOrOlderVersionsAreNotNewer() {
        assertFalse(UpdateManager.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateManager.isNewer("0.9.0", "1.0.0"))
        assertFalse(UpdateManager.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun nonNumericPartsCountAsZero() {
        assertTrue(UpdateManager.isNewer("1.1", "1.beta"))
        assertFalse(UpdateManager.isNewer("1.beta", "1.0"))
    }
}
