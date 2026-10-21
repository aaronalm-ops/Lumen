package app.lumen.player.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.lumen.player.data.BookmarkEntity
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NowPlayingActions(
    val close: () -> Unit,
    val playPause: () -> Unit,
    val seekBack: () -> Unit,
    val seekForward: () -> Unit,
    val seekTo: (Long) -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val toggleShuffle: () -> Unit,
    val cycleRepeat: () -> Unit,
    val cycleSpeed: () -> Unit,
    val cyclePreset: () -> Unit,
    val cycleSleep: () -> Unit,
    val addBookmark: () -> Unit,
    val deleteBookmark: (BookmarkEntity) -> Unit,
    val toggleLayout: () -> Unit,
    val unlockKids: () -> Unit,
    val setAudioOnly: (Boolean) -> Unit,
    val openLabelSheet: () -> Unit,
)

/** True black. Speech or music layout by duration, label or the per-item override. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun NowPlayingScreen(
    player: PlayerState,
    controller: MediaController?,
    item: LibraryItem?,
    labels: List<LabelEntity>,
    settings: Settings,
    speech: Boolean,
    kidsLocked: Boolean,
    noInterruptions: Boolean,
    bookmarks: List<BookmarkEntity>,
    audioOnly: Boolean,
    actions: NowPlayingActions,
) {
    val c = Lumen.colours
    BackHandler(enabled = true) { if (!kidsLocked) actions.close() }
    KeepScreenOn(enabled = noInterruptions)

    val tintValue = item?.media?.tint
    val tint = if (!speech && tintValue != null) Color(tintValue) else c.base
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (kidsLocked) {
        KidsLock(player, actions)
        return
    }

    if (player.isVideo && landscape && controller != null) {
        Box(Modifier.fillMaxSize().background(c.base)) {
            VideoSurface(controller, Modifier.fillMaxSize())
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(tint)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gutter),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap(LumenIcons.ChevronDown, "Close", c.text, onClick = actions.close)
            Spacer(Modifier.weight(1f))
            val labelName = labels.firstOrNull()?.name
            val eyebrow = listOfNotNull(labelName, if (speech) "Speech" else "Music").joinToString(" · ")
            Text(eyebrow.uppercase(), style = Lumen.type.label, color = c.text2)
            Spacer(Modifier.weight(1f))
            IconTap(LumenIcons.Label, "Labels", c.text, enabled = item != null, onClick = actions.openLabelSheet)
        }

        if (speech) SpeechLayout(player, settings, bookmarks, noInterruptions, actions)
        else MusicLayout(player, controller, item, labels, settings, audioOnly, actions)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeechLayout(
    player: PlayerState,
    settings: Settings,
    bookmarks: List<BookmarkEntity>,
    noInterruptions: Boolean,
    actions: NowPlayingActions,
) {
    val c = Lumen.colours
    val view = LocalView.current
    Spacer(Modifier.height(8.dp))
    Text(
        player.title.ifBlank { "Nothing loaded" },
        style = Lumen.type.displayL,
        color = c.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.combinedClickable(
            onClick = {},
            onLongClick = {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                actions.toggleLayout()
            },
        ),
    )
    if (player.artist.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(player.artist, style = Lumen.type.body, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(12.dp))
    ChipRow(player, settings, noInterruptions, bookmarks.isNotEmpty(), actions)
    Spacer(Modifier.height(16.dp))
    Scrubber(positionMs = player.positionMs, durationMs = player.durationMs, enabled = player.hasItem, thin = false, onSeek = actions.seekTo)
    Spacer(Modifier.height(24.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(seconds = settings.skipBackSeconds, forward = false, enabled = player.hasItem, onClick = actions.seekBack)
        PlayButton(isPlaying = player.isPlaying, enabled = player.hasItem || player.connected, size = 80.dp, onClick = actions.playPause)
        SkipButton(seconds = settings.skipForwardSeconds, forward = true, enabled = player.hasItem, onClick = actions.seekForward)
    }
    if (bookmarks.isNotEmpty()) {
        Spacer(Modifier.height(26.dp))
        SectionLabel("Bookmarks", count = bookmarks.size.toString())
        Spacer(Modifier.height(4.dp))
        bookmarks.forEachIndexed { index, b ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .combinedClickable(onClick = { actions.seekTo(b.positionMs) }, onLongClick = { actions.deleteBookmark(b) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LumenIcons.BookmarkFilled, contentDescription = null, tint = c.text2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text("Bookmark ${index + 1}", style = Lumen.type.bodyS, color = c.text, modifier = Modifier.weight(1f))
                Text(formatTime(b.positionMs), style = Lumen.type.timeS, color = c.text2)
            }
            if (index < bookmarks.lastIndex) HorizontalDivider(color = c.line, thickness = 1.dp)
        }
        Text("Tap to seek, long press to remove", style = Lumen.type.bodyS, color = c.text3, modifier = Modifier.padding(top = 6.dp))
    }
    if (player.nextTitle != null) UpNext(player)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ChipRow(player: PlayerState, settings: Settings, noInterruptions: Boolean, hasBookmarks: Boolean, actions: NowPlayingActions) {
    val c = Lumen.colours
    val view = LocalView.current
    val sleepLeft = if (settings.sleepActive) ((settings.sleepEndAt - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1) else 0L
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(text = speedLabel(player.speed), active = player.speed != 1.0f, onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            actions.cycleSpeed()
        })
        StatusChip(
            text = settings.eqPreset,
            active = settings.eqPreset != "Flat",
            enabled = settings.eqEngine != "none",
            onClick = actions.cyclePreset,
        )
        if (!noInterruptions) {
            StatusChip(
                text = if (sleepLeft > 0) "$sleepLeft min" else "Sleep",
                icon = LumenIcons.Moon,
                active = sleepLeft > 0,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    actions.cycleSleep()
                },
            )
        }
        StatusChip(
            text = "Mark",
            icon = if (hasBookmarks) LumenIcons.BookmarkFilled else LumenIcons.Bookmark,
            enabled = player.hasItem,
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                actions.addBookmark()
            },
        )
    }
    Text(
        "Long press the title to switch between the speech and music layouts",
        style = Lumen.type.bodyS,
        color = c.text3,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MusicLayout(
    player: PlayerState,
    controller: MediaController?,
    item: LibraryItem?,
    labels: List<LabelEntity>,
    settings: Settings,
    audioOnly: Boolean,
    actions: NowPlayingActions,
) {
    val c = Lumen.colours
    val view = LocalView.current
    Spacer(Modifier.height(10.dp))
    if (player.isVideo && controller != null) {
        VideoSurface(
            controller,
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(CardRadius)),
        )
        Spacer(Modifier.height(10.dp))
        ToggleRow(
            title = "Audio only when you leave",
            explanation = "Keeps the sound going and drops the picture while the screen is away",
            checked = audioOnly,
            onChange = actions.setAudioOnly,
        )
    } else {
        ArtBlock(item, player.title)
    }
    Spacer(Modifier.height(16.dp))
    Text(
        player.title.ifBlank { "Nothing loaded" },
        style = Lumen.type.displayS,
        color = c.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.combinedClickable(
            onClick = {},
            onLongClick = {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                actions.toggleLayout()
            },
        ),
    )
    if (player.artist.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(player.artist, style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.take(2).forEach { LabelChip(name = it.name, hue = it.hue, onClick = actions.openLabelSheet) }
        StatusChip(text = settings.eqPreset, active = settings.eqPreset != "Flat", enabled = settings.eqEngine != "none", onClick = actions.cyclePreset)
    }
    Spacer(Modifier.height(8.dp))
    Scrubber(positionMs = player.positionMs, durationMs = player.durationMs, enabled = player.hasItem, thin = true, onSeek = actions.seekTo)
    Spacer(Modifier.height(16.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTap(LumenIcons.Shuffle, "Shuffle", if (player.shuffle) c.silver else c.text3, size = 20.dp, onClick = actions.toggleShuffle)
        IconTap(LumenIcons.SkipPrevious, "Previous", c.text, size = 28.dp, enabled = player.hasItem, onClick = actions.previous)
        PlayButton(isPlaying = player.isPlaying, enabled = player.hasItem || player.connected, size = 72.dp, onClick = actions.playPause)
        IconTap(LumenIcons.SkipNext, "Next", if (player.nextTitle != null) c.text else c.text3, size = 28.dp, enabled = player.nextTitle != null, onClick = actions.next)
        IconTap(
            LumenIcons.Repeat,
            when (player.repeat) {
                Player.REPEAT_MODE_ONE -> "Repeat one"
                Player.REPEAT_MODE_ALL -> "Repeat all"
                else -> "Repeat off"
            },
            if (player.repeat != Player.REPEAT_MODE_OFF) c.silver else c.text3,
            size = 20.dp,
            onClick = actions.cycleRepeat,
        )
    }
    if (player.repeat == Player.REPEAT_MODE_ONE) {
        Text("Repeating this one", style = Lumen.type.labelS, color = c.text3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
    UpNext(player)
    Spacer(Modifier.height(16.dp))
}

/** Embedded art from the cache, or a flat block with the first letter. Tint comes from data. */
@Composable
private fun ArtBlock(item: LibraryItem?, title: String) {
    val c = Lumen.colours
    val path = item?.media?.artPath
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            try {
                BitmapFactory.decodeFile(path)
            } catch (t: Throwable) {
                null
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(CardRadius))
            .background(c.elevated),
        contentAlignment = Alignment.Center,
    ) {
        val art = bitmap
        if (art != null) {
            Image(bitmap = art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                title.trim().take(1).uppercase().ifBlank { "L" },
                style = Lumen.type.displayL.copy(fontSize = Lumen.type.displayL.fontSize * 4),
                color = c.line,
            )
            val tag = listOfNotNull(item?.folderName?.takeIf { it.isNotBlank() }).joinToString(" · ")
            if (tag.isNotBlank()) {
                Text(
                    tag.uppercase(),
                    style = Lumen.type.labelS,
                    color = c.text2,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun UpNext(player: PlayerState) {
    val c = Lumen.colours
    val next = player.nextTitle ?: return
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = c.line, thickness = 1.dp)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Up next · ${player.queueSize}".uppercase(), style = Lumen.type.label, color = c.text2)
        Spacer(Modifier.width(8.dp))
        Text(next, style = Lumen.type.bodyS.copy(color = c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** PlayerView inline. The surface is attached while this composable is shown and detached after. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(controller: MediaController, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = controller
            }
        },
        update = { view -> if (view.player !== controller) view.player = controller },
        onRelease = { view -> view.player = null },
    )
}

/** Keeps the screen on, dimmed, for labels marked "no interruptions". */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        val window = (view.context as? Activity)?.window
        if (enabled) {
            view.keepScreenOn = true
            window?.let { w ->
                val attributes = w.attributes
                attributes.screenBrightness = 0.15f
                w.attributes = attributes
            }
        }
        onDispose {
            view.keepScreenOn = false
            window?.let { w ->
                val attributes = w.attributes
                attributes.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = attributes
            }
        }
    }
}

/**
 * Kids lock: three thumb pads and a lock glyph. Back is swallowed by the caller; the only exit
 * is a two second press on the top-right corner.
 */
@Composable
private fun KidsLock(player: PlayerState, actions: NowPlayingActions) {
    val c = Lumen.colours
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var holding by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(c.base)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(96.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            holding = true
                            val job: Job = scope.launch {
                                delay(2_000)
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                actions.unlockKids()
                            }
                            val released = tryAwaitRelease()
                            holding = false
                            if (released) job.cancel()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .border(1.dp, if (holding) c.silver else c.line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LumenIcons.Lock, contentDescription = "Hold two seconds to exit", tint = if (holding) c.silver else c.text2, modifier = Modifier.size(20.dp))
            }
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                player.title.ifBlank { "Nothing loaded" },
                style = Lumen.type.displayS,
                color = c.text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(64.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                KidsPad(size = 72.dp, icon = LumenIcons.SkipPrevious, description = "Previous", filled = false, onClick = actions.previous)
                KidsPad(size = 84.dp, icon = if (player.isPlaying) LumenIcons.Pause else LumenIcons.Play, description = if (player.isPlaying) "Pause" else "Play", filled = true, onClick = actions.playPause)
                KidsPad(size = 72.dp, icon = LumenIcons.SkipNext, description = "Next", filled = false, onClick = actions.next)
            }
        }
    }
}

@Composable
private fun KidsPad(size: androidx.compose.ui.unit.Dp, icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, filled: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) c.accent else Color.Transparent)
            .border(1.dp, if (filled) c.accent else c.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (filled) c.onAccent else c.text, modifier = Modifier.size(32.dp))
    }
}
