package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.update.ReleaseNotes
import fr.arichard.lastlauncher.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private val all = listOf("1.31.0", "1.30.0", "1.29.0")

    @Test
    fun freshInstallSeesNothing() {
        assertTrue(ReleaseNotes.versionsToShow(all, null, "1.31.0", true).isEmpty())
    }

    @Test
    fun upgradeFromBeforeTheFeatureSeesEverythingAfterBaseline() {
        assertEquals(
            listOf("1.31.0", "1.30.0"),
            ReleaseNotes.versionsToShow(all, null, "1.31.0", false),
        )
    }

    @Test
    fun skippedVersionsAreIncludedNewestFirst() {
        val many = listOf("1.8.0", "1.10.0", "1.9.0")
        assertEquals(
            listOf("1.10.0", "1.9.0"),
            ReleaseNotes.versionsToShow(many, "1.8.0", "1.10.0", false),
        )
    }

    @Test
    fun nothingWhenAlreadySeen_andNeverNewerThanInstalled() {
        assertTrue(ReleaseNotes.versionsToShow(all, "1.31.0", "1.31.0", false).isEmpty())
        assertEquals(
            listOf("1.30.0"),
            ReleaseNotes.versionsToShow(all, "1.29.0", "1.30.0", false),
        )
    }

    @Test
    fun registryIsNewestFirst() {
        val v = ReleaseNotes.versions
        assertEquals(v.sortedWith { a, b -> UpdateManager.compareVersions(b, a) }, v)
    }
}
