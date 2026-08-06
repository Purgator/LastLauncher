package fr.arichard.lastlauncher

import fr.arichard.lastlauncher.predict.MissLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissLogTest {

    private val now = 1_800_000_000_000L

    @Test
    fun bucketsSliceTheDayInThrees() {
        assertEquals(0, MissLog.bucketOf(0))
        assertEquals(2, MissLog.bucketOf(8))
        assertEquals(7, MissLog.bucketOf(23))
    }

    @Test
    fun recordedMissesCountInTheirBucketOnly() {
        var log = MissLog.record("", listOf("a", "b"), bucket = 2, now = now)
        log = MissLog.record(log, listOf("a"), bucket = 2, now = now + 1000)
        assertEquals(mapOf("a" to 2, "b" to 1), MissLog.penalties(log, 2, now + 2000))
        assertTrue(MissLog.penalties(log, 3, now + 2000).isEmpty())
    }

    @Test
    fun oldEntriesExpire() {
        val log = MissLog.record("", listOf("a"), bucket = 1, now = now)
        val later = now + MissLog.WINDOW_MS + 1
        assertTrue(MissLog.penalties(log, 1, later).isEmpty())
        // Recording later also prunes the stale entry from the stored string.
        val pruned = MissLog.record(log, listOf("b"), bucket = 1, now = later)
        assertEquals(mapOf("b" to 1), MissLog.penalties(pruned, 1, later))
    }

    @Test
    fun logIsCappedAtTheNewestEntries() {
        var log = ""
        for (i in 0 until 100) {
            log = MissLog.record(log, listOf("pkg$i"), bucket = 0, now = now + i)
        }
        val counts = MissLog.penalties(log, 0, now + 1000)
        assertEquals(MissLog.MAX_ENTRIES, counts.values.sum())
        assertTrue("pkg99" in counts)   // newest kept
        assertTrue("pkg0" !in counts)   // oldest dropped
    }

    @Test
    fun garbageDecodesToNothing() {
        assertTrue(MissLog.penalties("", 0, now).isEmpty())
        assertTrue(MissLog.penalties("junk,also|junk,x|y|z", 0, now).isEmpty())
    }
}
