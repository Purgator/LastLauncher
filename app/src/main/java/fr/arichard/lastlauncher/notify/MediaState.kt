package fr.arichard.lastlauncher.notify

import android.media.session.PlaybackState

/**
 * Pure ranking of media-session playback states, kept free of framework objects
 * so it stays unit-testable (PlaybackState constants are compile-time ints).
 */
object MediaState {

    /** States worth a player row: what the notification shade would show. */
    fun showable(state: Int?): Boolean =
        state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_PAUSED

    /** Actively sounding (the play button should read "pause"). */
    fun playingLike(state: Int?): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    /**
     * Index of the session to surface among [states], or -1 when none deserves
     * the row: a playing session beats a paused one, list order breaks ties
     * (the system lists the most recently active first).
     */
    fun pick(states: List<Int?>): Int {
        var best = -1
        var bestRank = Int.MAX_VALUE
        for ((i, state) in states.withIndex()) {
            if (!showable(state)) continue
            val rank = if (playingLike(state)) 0 else 1
            if (rank < bestRank) {
                bestRank = rank
                best = i
                if (rank == 0) break // first playing session wins outright
            }
        }
        return best
    }
}
