package fr.arichard.lastlauncher.notify

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/**
 * Watches the system's active media sessions — the same source the notification
 * shade's player uses — through the notification-listener grant the launcher
 * already holds for badges. Exposes the one session worth showing plus transport
 * controls. Event-driven and registered only while the launcher is in front:
 * no polling, nothing stored.
 */
object MediaWatch {

    data class NowPlaying(
        val pkg: String,
        val title: String,
        val artist: String,
        val playing: Boolean,
    )

    /** The session the home row shows, or null when silence (or no access). */
    @Volatile
    var current: NowPlaying? = null
        private set

    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var controller: MediaController? = null
    private var onChanged: (() -> Unit)? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> adopt(list.orEmpty()) }

    private val controllerCallback = object : MediaController.Callback() {
        // A state flip can also mean "another session should take over" — re-pick.
        override fun onPlaybackStateChanged(state: PlaybackState?) = rescan()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = rescan()
    }

    /**
     * Starts watching; [changed] fires on the main thread (including immediately
     * with the current state). Silently a no-op without notification access.
     */
    fun start(context: Context, changed: () -> Unit) {
        stop()
        onChanged = changed
        val comp = ComponentName(context, NotifListener::class.java)
        try {
            val msm = context.getSystemService(MediaSessionManager::class.java) ?: return
            msm.addOnActiveSessionsChangedListener(sessionsListener, comp)
            manager = msm
            component = comp
            adopt(msm.getActiveSessions(comp))
        } catch (e: Exception) {
            // No notification access: the row simply stays hidden.
            manager = null
            component = null
            current = null
            changed()
        }
    }

    fun stop() {
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
            // never registered
        }
        manager = null
        component = null
        controller?.unregisterCallback(controllerCallback)
        controller = null
        onChanged = null
        current = null
    }

    fun playPause() {
        val c = controller ?: return
        if (current?.playing == true) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious()
    }

    private fun rescan() {
        val msm = manager
        val comp = component
        if (msm == null || comp == null) return
        adopt(
            try {
                msm.getActiveSessions(comp)
            } catch (e: Exception) {
                emptyList()
            }
        )
    }

    private fun adopt(sessions: List<MediaController>) {
        controller?.unregisterCallback(controllerCallback)
        val index = MediaState.pick(sessions.map { it.playbackState?.state })
        controller = sessions.getOrNull(index)
        controller?.registerCallback(controllerCallback)
        refresh()
    }

    private fun refresh() {
        val c = controller
        val state = c?.playbackState?.state
        current = if (c == null || !MediaState.showable(state)) {
            null
        } else {
            NowPlaying(
                pkg = c.packageName,
                title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = c.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: c.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                playing = MediaState.playingLike(state),
            )
        }
        onChanged?.invoke()
    }
}
