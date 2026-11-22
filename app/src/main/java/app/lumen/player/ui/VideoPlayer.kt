package app.lumen.player.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 0.10.0: the full-screen video player. Tap shows or hides the controls (they hide again after a
 * few seconds of playing); double-tap the left or right half skips back or forward; pinch zooms
 * the picture to fill the screen (or the Zoom button does); Subtitles lists the video's own text
 * tracks; Picture in picture keeps it playing in a small window over other apps.
 */
@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideo(
    controller: MediaController,
    player: PlayerState,
    backSeconds: Int,
    forwardSeconds: Int,
    playPause: () -> Unit,
    seekBack: () -> Unit,
    seekForward: () -> Unit,
    seekTo: (Long) -> Unit,
    onClose: () -> Unit,
    /** Opened on purpose (not by turning the phone): a wide video turns the screen to landscape. */
    lockLandscape: Boolean = true,
    /** 0.10.1: seconds per tap for tap to skip. */
    tapSkipSeconds: Int = 10,
    onSkipUsed: () -> Unit = {},
) {
    val c = Lumen.colours
    var controls by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(false) }
    var pinch by remember { mutableFloatStateOf(1f) }
    var subtitlesMenu by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    BackHandler(onBack = onClose)
    ImmersiveWhileShown(activity, landscape = lockLandscape && VideoRules.isWide(controller.videoSize.width, controller.videoSize.height))
    // Controls hide after three seconds of playing untouched.
    LaunchedEffect(controls, player.isPlaying) {
        if (controls && player.isPlaying) {
            delay(3_000)
            controls = false
        }
    }
    ProvideTapSkip {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = controller
                    keepScreenOn = controller.isPlaying
                }
            },
            update = { view ->
                if (view.player !== controller) view.player = controller
                view.keepScreenOn = player.isPlaying
                view.resizeMode = if (zoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onRelease = { view ->
                view.keepScreenOn = false
                view.player = null
            },
        )
        // Gestures sit over the picture, so the player view never swallows them. 0.10.1: one tap
        // shows or hides the controls; double-tap either side skips by the tap step, further with
        // each tap (the same as a cover); pinch zooms.
        run {
            TapSkipZone(
                enabled = player.hasItem && player.durationMs > 0,
                stepSeconds = tapSkipSeconds,
                position = { controller.currentPosition },
                duration = { controller.duration.coerceAtLeast(0L) },
                onSeek = seekTo,
                loop = player.loopRange(),
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, _, scale, _ ->
                        pinch *= scale
                        if (pinch > VideoRules.ZOOM_IN_AT) { zoom = true; pinch = 1f }
                        if (pinch < VideoRules.ZOOM_OUT_AT) { zoom = false; pinch = 1f }
                    }
                },
                onSingleTap = { controls = !controls },
                onFirstUse = onSkipUsed,
                overlayOnDark = true,
            ) { Box(Modifier.fillMaxSize()) }
        }
        AnimatedVisibility(visible = controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
                    IconTap(LumenIcons.ChevronLeft, "Close full screen", Color.White, onClick = onClose)
                    Text(player.title, style = Lumen.type.bodyStrong, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    Box {
                        val tracks = VideoRules.textTracks(controller.currentTracks)
                        IconTap(LumenIcons.Subtitles, if (tracks.isEmpty()) "No subtitles in this video" else "Subtitles", if (tracks.isEmpty()) Color.White.copy(alpha = 0.4f) else Color.White, enabled = tracks.isNotEmpty()) { subtitlesMenu = true }
                        DropdownMenu(expanded = subtitlesMenu, onDismissRequest = { subtitlesMenu = false }, containerColor = c.elevated) {
                            val selected = tracks.firstOrNull { it.selected }
                            DropdownMenuItem(text = { Text(if (selected == null) "Off (chosen)" else "Off", style = Lumen.type.body, color = c.text) }, onClick = {
                                subtitlesMenu = false
                                controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                            })
                            tracks.forEach { t ->
                                DropdownMenuItem(text = { Text(t.label + if (t.selected) " (chosen)" else "", style = Lumen.type.body, color = c.text) }, onClick = {
                                    subtitlesMenu = false
                                    controller.trackSelectionParameters = controller.trackSelectionParameters.buildUpon()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                                        .build()
                                })
                            }
                        }
                    }
                    IconTap(if (zoom) LumenIcons.ZoomOut else LumenIcons.ZoomIn, if (zoom) "Fit the whole picture" else "Fill the screen", Color.White) { zoom = !zoom }
                    if (activity != null && Build.VERSION.SDK_INT >= 26) {
                        IconTap(LumenIcons.PictureInPicture, "Picture in picture", Color.White) {
                            runCatching { activity.enterPictureInPictureMode(VideoRules.pipParams(controller.videoSize.width, controller.videoSize.height, player.isPlaying)) }
                        }
                    }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTap(LumenIcons.SkipBack, "Back $backSeconds seconds", Color.White, size = 32.dp, onClick = seekBack)
                    PlayButton(isPlaying = player.isPlaying, enabled = true, size = 72.dp, onClick = playPause)
                    IconTap(LumenIcons.SkipForward, "Forward $forwardSeconds seconds", Color.White, size = 32.dp, onClick = seekForward)
                }
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    LumenTheme(dark = true) {
                        Scrubber(player.positionMs, player.durationMs, enabled = player.hasItem, thin = true, isPlaying = player.isPlaying, mediaKey = player.mediaId, onSeek = seekTo)
                    }
                    Spacer(Modifier.width(1.dp))
                }
            }
        }
    }
    }
}

/** The video alone, for the small picture-in-picture window: no controls fit there. */
@OptIn(UnstableApi::class)
@Composable
fun PictureInPictureVideo(controller: MediaController) {
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = controller
            }
        },
        update = { view -> if (view.player !== controller) view.player = controller },
        onRelease = { view -> view.player = null },
    )
}

/** Hides the status and navigation bars, and turns a wide video to landscape, while shown. */
@Composable
private fun ImmersiveWhileShown(activity: Activity?, landscape: Boolean) {
    val view = LocalView.current
    DisposableEffect(activity, landscape) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (landscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            if (landscape) activity?.requestedOrientation = before
        }
    }
}

/** 0.10.0: the video player's rules, kept pure so they are unit tested. */
object VideoRules {
    /** Pinch past these scales to fill the screen or to fit the whole picture again. */
    const val ZOOM_IN_AT = 1.15f
    const val ZOOM_OUT_AT = 0.87f

    fun isWide(width: Int, height: Int): Boolean = width > 0 && height > 0 && width > height

    /** Android allows picture in picture between 1:2.39 and 2.39:1; anything else is clamped. */
    fun pipRatio(width: Int, height: Int): Rational {
        if (width <= 0 || height <= 0) return Rational(16, 9)
        val ratio = width.toFloat() / height
        return when {
            ratio > 2.39f -> Rational(239, 100)
            ratio < 1 / 2.39f -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }

    fun pipParams(width: Int, height: Int, autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(pipRatio(width, height))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
        return builder.build()
    }

    data class TextTrack(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean)

    /** The video's own subtitle tracks, named by their label or language. */
    fun textTracks(tracks: Tracks): List<TextTrack> {
        val out = ArrayList<TextTrack>()
        var n = 0
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                n++
                val format = group.getTrackFormat(i)
                val language = format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.UK).ifBlank { it } }
                val label = format.label?.takeIf { it.isNotBlank() } ?: language ?: "Subtitles $n"
                out += TextTrack(group, i, label, group.isTrackSelected(i))
            }
        }
        return out
    }
}
