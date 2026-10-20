package app.lumen.player

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lumen.player.diag.Diagnostics
import app.lumen.player.diag.RingLog
import app.lumen.player.playback.PlaybackService
import app.lumen.player.ui.LumenTheme
import app.lumen.player.ui.PlayerActions
import app.lumen.player.ui.PlayerScreen
import app.lumen.player.ui.PlayerUiState
import app.lumen.player.update.UpdateChecker
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private val state = PlayerUiState()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        RingLog.i("ui", "activity onCreate")

        val actions = PlayerActions(
            pickFile = { openDocument.launch(arrayOf("audio/*", "video/*")) },
            playPause = { controller?.let { c -> if (c.isPlaying) c.pause() else playOrResume(c) } },
            seekBack = { controller?.seekBack() },
            seekForward = { controller?.seekForward() },
            seekTo = { ms -> controller?.seekTo(ms) },
            shareDiagnostics = { lifecycleScope.launch { Diagnostics.share(this@MainActivity) } },
            openUpdate = { state.updateUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } },
        )

        setContent {
            LumenTheme {
                PlayerScreen(state = state, actions = actions)
            }
        }

        lifecycleScope.launch {
            LumenApp.from(this@MainActivity).store.lastSaved.collect { state.lastSaved = it }
        }
        lifecycleScope.launch {
            val update = UpdateChecker.check(this@MainActivity)
            state.updateTag = update?.tag
            state.updateUrl = update?.url
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                // onStop may already have released this future; ignore a late completion.
                if (controllerFuture !== future) return@addListener
                try {
                    val c = future.get()
                    controller = c
                    c.addListener(controllerListener)
                    state.controllerConnected = true
                    refreshFromController(c)
                    startTicker()
                    RingLog.i("ui", "controller connected")
                } catch (t: Throwable) {
                    RingLog.e("ui", "controller connection failed", t)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun onStop() {
        ticker?.cancel()
        controller?.removeListener(controllerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        state.controllerConnected = false
        super.onStop()
    }

    private fun onFilePicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: Throwable) {
            RingLog.w("ui", "persistable permission not granted for $uri: $t")
        }
        val displayName = queryDisplayName(uri)
        RingLog.i("ui", "picked $uri ($displayName)")
        val item = MediaItem.Builder()
            .setMediaId(uri.toString())
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(displayName).build())
            .build()
        val c = controller
        if (c == null) {
            RingLog.w("ui", "controller not ready, file ignored")
            return
        }
        // setMediaItem goes through PlaybackService.SessionCallback.onSetMediaItems, which looks
        // up the stored position for this URI and seeks 3 s before it.
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    private fun playOrResume(c: MediaController) {
        if (c.currentMediaItem == null) {
            // Empty player (fresh process after swipe-away, force stop or reboot). play() makes
            // the session call SessionCallback.onPlaybackResumption, which rebuilds the last
            // item from the store and seeks 3 s before the saved position.
            RingLog.i("ui", "play with no item, requesting playback resumption")
            c.play()
            return
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        c.play()
    }

    private fun queryDisplayName(uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled"
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: fallback
        } catch (t: Throwable) {
            fallback
        }
    }

    private fun refreshFromController(c: MediaController) {
        val item = c.currentMediaItem
        state.hasItem = item != null
        val metadata = c.mediaMetadata
        state.title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
            ?: item?.mediaMetadata?.title?.toString()
            ?: item?.localConfiguration?.uri?.lastPathSegment
            ?: item?.mediaId
            ?: ""
        state.artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
            ?: metadata.albumArtist?.toString()?.takeIf { it.isNotBlank() }
            ?: ""
        state.isPlaying = c.isPlaying
        state.durationMs = c.duration.let { if (it == C.TIME_UNSET) 0L else it }
        state.positionMs = c.currentPosition.coerceAtLeast(0L)
        state.serviceRunning = PlaybackService.isRunning
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (isActive) {
                controller?.let { c ->
                    state.positionMs = c.currentPosition.coerceAtLeast(0L)
                    state.durationMs = c.duration.let { if (it == C.TIME_UNSET) 0L else it }
                    state.serviceRunning = PlaybackService.isRunning
                }
                delay(500)
            }
        }
    }

    private val controllerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            controller?.let { refreshFromController(it) }
        }
    }
}
