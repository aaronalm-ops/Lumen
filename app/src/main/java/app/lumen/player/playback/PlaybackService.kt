package app.lumen.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.lumen.player.LumenApp
import app.lumen.player.MainActivity
import app.lumen.player.data.PositionStore
import app.lumen.player.diag.RingLog
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The only place a player exists. The UI talks to it through a MediaController; the system
 * talks to it through the MediaSession (notification, lock screen, Bluetooth, resumption).
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val HEARTBEAT_MS = 30_000L
        private const val DESTROY_SAVE_TIMEOUT_MS = 1_500L
    }

    private lateinit var player: ExoPlayer
    private lateinit var store: PositionStore
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var heartbeat: Job? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        store = LumenApp.from(this).store
        RingLog.i("service", "onCreate")

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.addListener(playerListener)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(mediaButtonPreferences())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        RingLog.i("service", "onDestroy")
        heartbeat?.cancel()
        saveBlocking("destroy")
        scope.cancel()
        session?.let { s ->
            player.removeListener(playerListener)
            s.release()
            player.release()
        }
        session = null
        isRunning = false
        super.onDestroy()
    }

    /**
     * Seek back and seek forward take the two slots the system would otherwise give to previous
     * and next, on the notification, the lock screen and Bluetooth displays.
     */
    private fun mediaButtonPreferences(): List<CommandButton> {
        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Back 10 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("Forward 30 seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        return listOf(back, forward)
    }

    // Progress persistence. Reads of the player must happen on the main thread, so the snapshot is
    // taken here and only the write moves to the IO dispatcher inside the store.

    private fun currentSnapshot(): Snapshot? {
        val item = player.currentMediaItem ?: return null
        val uri = item.localConfiguration?.uri?.toString() ?: item.mediaId
        if (uri.isBlank()) return null
        val duration = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        val title = player.mediaMetadata.title?.toString()
            ?: item.mediaMetadata.title?.toString()
            ?: Uri.parse(uri).lastPathSegment
            ?: uri
        return Snapshot(uri, title, maxOf(0L, player.currentPosition), duration)
    }

    private data class Snapshot(val uri: String, val title: String, val positionMs: Long, val durationMs: Long)

    private fun save(reason: String) {
        val s = currentSnapshot() ?: return
        store.save(s.uri, s.title, s.positionMs, s.durationMs, reason)
    }

    private fun saveBlocking(reason: String) {
        val s = currentSnapshot() ?: return
        runBlocking {
            withTimeoutOrNull(DESTROY_SAVE_TIMEOUT_MS) {
                store.saveNow(s.uri, s.title, s.positionMs, s.durationMs, reason)
            }
        }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (player.isPlaying) save("heartbeat")
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            RingLog.i("player", "isPlaying=$isPlaying")
            if (isPlaying) {
                startHeartbeat()
            } else {
                heartbeat?.cancel()
                // Covers pause, audio focus loss, becoming noisy and the end of the item.
                save("isPlaying=false")
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) save("seek")
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            RingLog.i("player", "item transition reason=$reason id=${mediaItem?.mediaId}")
            if (mediaItem != null) save("transition")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            RingLog.i("player", "state=$playbackState")
            if (playbackState == Player.STATE_READY) save("ready")
            if (playbackState == Player.STATE_ENDED) save("ended")
        }

        override fun onPlayerError(error: PlaybackException) {
            RingLog.e("player", "error ${error.errorCodeName}", error)
        }
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {

        /** Controllers send items without a URI across the binder; put it back from the id. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { resolve(it) }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /** Same as above, but also looks up the stored position for the first item. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resolved = mediaItems.map { resolve(it) }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            if (resolved.isEmpty() || startPositionMs != C.TIME_UNSET) {
                future.set(MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs))
                return future
            }
            val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
            val uri = uriOf(resolved[index])
            scope.launch {
                val saved = store.latestForUri(uri)
                val position = if (saved == null) C.TIME_UNSET else PositionStore.resumePosition(saved.positionMs)
                RingLog.i("session", "setMediaItems uri=$uri resumeAt=$position")
                future.set(MediaSession.MediaItemsWithStartPosition(resolved, index, position))
            }
            return future
        }

        /**
         * Called by the system (notification after process death, Bluetooth play, boot) when
         * there is no current item. Rebuilds the last item from the store and seeks 3 s back.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val last = store.lastItem()
                if (last == null) {
                    RingLog.w("session", "resumption requested but nothing stored")
                    future.setException(UnsupportedOperationException("Nothing to resume"))
                    return@launch
                }
                val uri = Uri.parse(last.uri)
                val item = MediaItem.Builder()
                    .setMediaId(last.uri)
                    .setUri(uri)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(last.title)
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                            .build(),
                    )
                    .build()
                val position = PositionStore.resumePosition(last.positionMs)
                RingLog.i("session", "resumption forPlayback=$isForPlayback uri=${last.uri} at=$position")
                future.set(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, position))
            }
            return future
        }

        private fun uriOf(item: MediaItem): String =
            item.localConfiguration?.uri?.toString()
                ?: item.requestMetadata.mediaUri?.toString()
                ?: item.mediaId

        private fun resolve(item: MediaItem): MediaItem {
            if (item.localConfiguration != null) return item
            val uri = item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
            return item.buildUpon().setUri(uri).build()
        }
    }
}
