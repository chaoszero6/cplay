package com.chaoswares.chaosplay.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Background play (spec 6 "Background audio play", 7.1 "MediaSession (lock
 * screen, notification, Bluetooth buttons)").
 *
 * The player itself is owned by the player screen's view model; this
 * service only hosts the session that view model publishes in
 * [SessionHolder]. Media3 then shows the media notification and keeps the
 * service in the foreground while playing.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        SessionHolder.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away ends playback unless something is actively playing
        val player = SessionHolder.session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        SessionHolder.session?.let { if (isSessionAdded(it)) removeSession(it) }
        super.onDestroy()
    }
}

/** The one live session, if a player is open. */
object SessionHolder {
    @Volatile var session: MediaSession? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null

    /**
     * Binds the service (via a controller) so it promotes itself to a
     * foreground media service while playing. Called while the app is still
     * in the foreground, which Android 12+ requires for starting a
     * foreground service.
     */
    @UnstableApi
    fun connectService(context: Context) {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
    }

    fun disconnectService(context: Context) {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        runCatching { context.stopService(Intent(context, PlaybackService::class.java)) }
    }

    val isServiceConnected: Boolean get() = controllerFuture != null
}

/**
 * Process-wide view of what the player is doing. Used by the activity for
 * Picture-in-Picture decisions, and by the instrumented tests to assert
 * real playback (first frame rendered, position advancing) instead of
 * trusting what the UI draws.
 */
object PlaybackDiagnostics {

    data class Snapshot(
        val active: Boolean = false,
        val title: String = "",
        val uri: String = "",
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val firstFrameRendered: Boolean = false,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val error: String? = null,
        val subtitleText: String = "",
        val queueIndex: Int = -1,
        val queueSize: Int = 0,
        val sessionId: Int = 0,
        val speed: Float = 1f,
        val audioTracks: Int = 0,
        val subtitleTracks: Int = 0,
        val controlsVisible: Boolean = false,
        val locked: Boolean = false,
        /** Level indicator showing: "brightness", "volume", "both" (a bug) or "". */
        val indicator: String = "",
        val tvDevice: String = "",
        val tvState: String = "",
        val audioDecoder: String = "",
        val audioMime: String = "",
        val audioDelayMs: Long = 0L,
        val levelling: Boolean = false,
        val ended: Boolean = false
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Increments for every new player session, so tests can tell sessions apart. */
    @Volatile var sessionCounter = 0
        private set

    fun nextSession(): Int = ++sessionCounter

    fun publish(snapshot: Snapshot) {
        _state.value = snapshot
    }

    fun clear(sessionId: Int) {
        // Only the session that published may clear, so a fading-out old
        // player cannot wipe the state of the new one.
        if (_state.value.sessionId == sessionId) _state.value = Snapshot()
    }
}
