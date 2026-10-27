package app.lumen.player.remote

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.app.NotificationManagerCompat
import app.lumen.player.MainActivity
import app.lumen.player.diag.RingLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 0.5.0: the YouTube Music remote. Lumen never plays YouTube audio; it opens the official YouTube
 * Music app on a song or playlist and, with Notification access granted, follows and drives that
 * app's own media session: [MediaSessionManager.getActiveSessions] with [LumenNotificationListener]
 * as the credential gives the YouTube Music [MediaController], whose metadata, playback state and
 * position feed Now Playing and whose TransportControls take play, pause, seek, next and previous.
 *
 * Without Notification access everything here is inert except [open]: the song still opens in
 * YouTube Music, and Now Playing offers "Open YouTube Music" instead of controls. Nothing is stored
 * and nothing leaves the phone.
 */
class RemoteController(context: Context) {

    companion object {
        /**
         * 0.6.0: off. Play Protect blocks sideloaded apps that ask for notification access, which
         * this control needs. Back on if Lumen is ever installed from the Play Store.
         */
        const val CONTROL_ENABLED = false

        const val YTM_PACKAGE = "com.google.android.apps.youtube.music"

        /**
         * The "one second flash": after YouTube Music is asked to play, Lumen comes back to the
         * front this long after. Long enough for YouTube Music to take the request, short enough
         * that the owner barely sees it.
         */
        const val RETURN_DELAY_MS = 600L
    }

    /** What YouTube Music's session says right now. [positionMs] is as of [stateAt] (elapsedRealtime). */
    data class State(
        val connected: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val art: Bitmap? = null,
        val mediaId: String = "",
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val speed: Float = 1f,
        val stateAt: Long = 0L,
        val playbackState: Int = PlaybackState.STATE_NONE,
        val actions: Long = 0L,
    ) {
        val isPlaying: Boolean get() = playbackState == PlaybackState.STATE_PLAYING || playbackState == PlaybackState.STATE_BUFFERING

        /** The track, for spotting that YouTube Music moved on: its media id, or title and artist. */
        val trackKey: String get() = mediaId.ifBlank { if (title.isBlank()) "" else "$title|$artist" }

        /** The position now, moved on by the time since the session last reported it while playing. */
        fun positionNow(nowElapsed: Long = SystemClock.elapsedRealtime()): Long {
            if (playbackState != PlaybackState.STATE_PLAYING || stateAt <= 0L) return positionMs.coerceAtLeast(0L)
            val moved = positionMs + ((nowElapsed - stateAt) * speed).toLong()
            return if (durationMs > 0L) moved.coerceIn(0L, durationMs) else moved.coerceAtLeast(0L)
        }
    }

    private val appContext = context.applicationContext
    private val sessions: MediaSessionManager? = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, LumenNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var controller: MediaController? = null
    private var attached = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        pick(list.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

        override fun onSessionDestroyed() {
            RingLog.i("ytm", "YouTube Music session ended")
            release(controller)
            controller = null
            _state.value = State()
        }
    }

    /** Whether a YouTube Music item is current in Lumen, so its session should be followed. */
    private var wanted = false

    /** Follows YouTube Music while [on] (a YouTube Music item is current); false without access. */
    @MainThread
    fun follow(on: Boolean): Boolean {
        wanted = on
        if (on) return attach()
        detach()
        return false
    }

    /** Called when Notification access connects: picks the session up if Lumen wants it. */
    @MainThread
    fun attachIfWatching() {
        if (wanted) attach()
    }

    /**
     * True when the owner allowed Lumen in Settings, Notification access. 0.6.0: always false,
     * since the listener is no longer declared (see [CONTROL_ENABLED]).
     */
    fun hasAccess(): Boolean =
        CONTROL_ENABLED && NotificationManagerCompat.getEnabledListenerPackages(appContext).contains(appContext.packageName)

    /**
     * Starts following YouTube Music's session (and picks it up whenever it appears). Returns false,
     * and does nothing, without Notification access. Safe to call repeatedly.
     */
    @MainThread
    fun attach(): Boolean {
        val manager = sessions ?: return false
        if (!hasAccess()) {
            detach()
            return false
        }
        if (!attached) {
            try {
                manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
                attached = true
            } catch (e: SecurityException) {
                RingLog.w("ytm", "session listener refused: $e")
                return false
            }
        }
        try {
            pick(manager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            RingLog.w("ytm", "active sessions refused: $e")
            return false
        }
        return true
    }

    /** Stops following YouTube Music. */
    @MainThread
    fun detach() {
        if (attached) {
            try {
                sessions?.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (t: Throwable) {
                RingLog.w("ytm", "session listener removal failed: $t")
            }
            attached = false
        }
        release(controller)
        controller = null
        _state.value = State()
    }

    private fun pick(list: List<MediaController>) {
        val found = list.firstOrNull { it.packageName == YTM_PACKAGE }
        val current = controller
        if (found != null && current != null && found.sessionToken == current.sessionToken) {
            publish()
            return
        }
        release(current)
        controller = found
        if (found != null) {
            found.registerCallback(callback, handler)
            RingLog.i("ytm", "following YouTube Music's session")
        }
        publish()
    }

    private fun release(c: MediaController?) {
        try {
            c?.unregisterCallback(callback)
        } catch (t: Throwable) {
            RingLog.w("ytm", "callback removal failed: $t")
        }
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = State()
            return
        }
        val metadata = c.metadata
        val playback = c.playbackState
        _state.value = State(
            connected = true,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: "",
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                ?: "",
            art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L,
            positionMs = playback?.position?.coerceAtLeast(0L) ?: 0L,
            speed = playback?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            stateAt = playback?.lastPositionUpdateTime ?: 0L,
            playbackState = playback?.state ?: PlaybackState.STATE_NONE,
            actions = playback?.actions ?: 0L,
        )
    }

    // Transport. Each does nothing when YouTube Music's session is not followed.

    fun play() = controller?.transportControls?.play() ?: Unit

    fun pause() = controller?.transportControls?.pause() ?: Unit

    fun seekTo(positionMs: Long) = controller?.transportControls?.seekTo(positionMs.coerceAtLeast(0L)) ?: Unit

    fun next() = controller?.transportControls?.skipToNext() ?: Unit

    fun previous() = controller?.transportControls?.skipToPrevious() ?: Unit

    /**
     * For an item reached while Lumen is not on screen, where Android does not let Lumen open an
     * app: asks the followed YouTube Music session to play the link directly, only when that session
     * says it takes ACTION_PLAY_FROM_URI. Returns false when it cannot.
     */
    fun playFromUri(link: YtmLink): Boolean {
        val c = controller ?: return false
        val actions = c.playbackState?.actions ?: 0L
        if ((actions and PlaybackState.ACTION_PLAY_FROM_URI) == 0L) return false
        c.transportControls.playFromUri(Uri.parse(link.watchUrl), null)
        RingLog.i("ytm", "asked YouTube Music to play ${link.uri} from its session")
        return true
    }

    /**
     * Opens [link] in YouTube Music (any browser if the app is missing) and, with [comeBack], brings
     * Lumen back to the front [RETURN_DELAY_MS] later. Lumen started an activity a moment before, so
     * Android allows that second start from the background.
     */
    @MainThread
    fun open(link: YtmLink, comeBack: Boolean) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link.watchUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(Intent(view).setPackage(YTM_PACKAGE))
        } catch (e: ActivityNotFoundException) {
            RingLog.w("ytm", "YouTube Music is not installed; opening the link in the browser")
            try {
                appContext.startActivity(view)
            } catch (again: ActivityNotFoundException) {
                RingLog.w("ytm", "nothing can open ${link.watchUrl}")
                return
            }
        }
        RingLog.i("ytm", "opened ${link.uri} in YouTube Music")
        if (!comeBack) return
        handler.postDelayed({
            try {
                appContext.startActivity(
                    Intent(appContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            } catch (t: Throwable) {
                RingLog.w("ytm", "could not come back to Lumen: $t")
            }
        }, RETURN_DELAY_MS)
    }

    /** Opens YouTube Music as it is, for "Open YouTube Music" when Lumen cannot control it. */
    fun openApp() {
        val launch = appContext.packageManager.getLaunchIntentForPackage(YTM_PACKAGE)
        if (launch == null) {
            RingLog.w("ytm", "YouTube Music is not installed")
            return
        }
        try {
            appContext.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            RingLog.w("ytm", "YouTube Music could not be opened: $e")
        }
    }
}

/**
 * 0.5.0: decides when the YouTube Music item Lumen started is over, so the Lumen queue can move on.
 *
 * The first track YouTube Music plays after [startedAt] is the baseline: at once when its media id
 * is the link's video id, otherwise once it has played for [GRACE_MS] (YouTube Music can show the
 * previous song for a moment while it loads the new one). After that the item is over when the
 * session stops or pauses on its last second, or, for a song (not a playlist), when it plays a
 * different track. Fires once.
 */
class YtmWatch(private val link: YtmLink, private val startedAt: Long = SystemClock.elapsedRealtime()) {

    companion object {
        const val GRACE_MS = 2_500L

        /** Paused this close to the end counts as the end. */
        const val END_SLACK_MS = 1_500L
    }

    private var baseline: String? = null
    private var fired = false

    /** Feeds one state; true exactly once, when the item has ended. */
    fun ended(state: RemoteController.State, nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean {
        if (fired || !state.connected) return false
        val key = state.trackKey
        val base = baseline
        if (base == null) {
            if (!state.isPlaying || key.isBlank()) return false
            val exact = !link.isList && state.mediaId == link.id
            if (exact || nowElapsed - startedAt >= GRACE_MS) {
                baseline = key
                RingLog.i("ytm", "following ${link.uri} as \"${state.title}\"")
            }
            return false
        }
        // Stopped; or paused on the last second (autoplay off); or, for a song, another track.
        val atEnd = state.playbackState == PlaybackState.STATE_PAUSED &&
            state.durationMs > 0L && state.positionNow(nowElapsed) >= state.durationMs - END_SLACK_MS
        val over = state.playbackState == PlaybackState.STATE_STOPPED || atEnd ||
            (!link.isList && key.isNotBlank() && key != base)
        if (over) {
            fired = true
            RingLog.i("ytm", "${link.uri} is over (state ${state.playbackState})")
        }
        return over
    }
}
