package fr.arichard.lastlauncher

import android.media.session.PlaybackState
import fr.arichard.lastlauncher.notify.MediaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStateTest {

    @Test
    fun showableStates() {
        assertTrue(MediaState.showable(PlaybackState.STATE_PLAYING))
        assertTrue(MediaState.showable(PlaybackState.STATE_PAUSED))
        assertTrue(MediaState.showable(PlaybackState.STATE_BUFFERING))
        assertFalse(MediaState.showable(PlaybackState.STATE_STOPPED))
        assertFalse(MediaState.showable(PlaybackState.STATE_NONE))
        assertFalse(MediaState.showable(PlaybackState.STATE_ERROR))
        assertFalse(MediaState.showable(null))
    }

    @Test
    fun playingLikeDrivesThePauseGlyph() {
        assertTrue(MediaState.playingLike(PlaybackState.STATE_PLAYING))
        assertTrue(MediaState.playingLike(PlaybackState.STATE_BUFFERING))
        assertFalse(MediaState.playingLike(PlaybackState.STATE_PAUSED))
        assertFalse(MediaState.playingLike(null))
    }

    @Test
    fun pickPrefersPlayingOverPausedAndKeepsListOrder() {
        // A paused session listed first must lose to a playing one after it.
        assertEquals(
            1,
            MediaState.pick(
                listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING)
            )
        )
        // Two playing sessions: the first (most recently active) wins.
        assertEquals(
            0,
            MediaState.pick(
                listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PLAYING)
            )
        )
        // Only paused sessions: the first paused one is shown.
        assertEquals(
            0,
            MediaState.pick(
                listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PAUSED)
            )
        )
        // Stopped/none/null sessions never surface.
        assertEquals(
            -1,
            MediaState.pick(
                listOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, null)
            )
        )
        assertEquals(-1, MediaState.pick(emptyList()))
        // Non-showable entries are skipped, not counted.
        assertEquals(
            2,
            MediaState.pick(
                listOf(PlaybackState.STATE_STOPPED, null, PlaybackState.STATE_PAUSED)
            )
        )
    }
}
