package app.lumen.player.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.cast.MediaRouteButton
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.Settings
import app.lumen.player.data.drive.isDrive
import app.lumen.player.remote.RemoteController
import app.lumen.player.remote.YtmLink
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
    val toggleLayout: () -> Unit,
    val unlockKids: () -> Unit,
    val setAudioOnly: (Boolean) -> Unit,
    val openLabelSheet: () -> Unit,
    /** Phase 1b: "Keep offline" or "Remove download" for a Drive item. */
    val toggleOffline: () -> Unit,
    // 0.4.0.
    /** Long press on the bookmark chip: the chapter sheet at the current position. */
    val openChapterSheet: () -> Unit,
    /** Long press on a chapter list row: removes that chapter or bookmark. */
    val deleteMark: (TimelineMark) -> Unit,
    /** The chapter list's overflow: shares the notes as a plain-text file. */
    val exportNotes: () -> Unit,
    val setLevelOutVoices: (Boolean) -> Unit,
    val setTrimSilences: (Boolean) -> Unit,
    // 0.5.0.
    /** "Open YouTube Music" in remote mode, when Lumen cannot control it from here. */
    val openYouTubeMusic: () -> Unit,
    /** 0.6.0: the notes and transcript of the item playing. */
    val openNotes: () -> Unit = {},
    /** 0.6.1: Try again after a playback error. */
    val retry: () -> Unit = {},
    // 0.7.0: the redesign's actions.
    val markNow: () -> Unit = {},
    val nameMark: (Long) -> Unit = {},
    val nameBookmark: (TimelineMark) -> Unit = {},
    val removeMark: (TimelineMark) -> Unit = {},
    val undoRemove: () -> Unit = {},
    val newChapter: () -> Unit = {},
    val writeNotes: () -> Unit = {},
    val setUpNotes: () -> Unit = {},
    val setSpeed: (Float) -> Unit = {},
    val setSleepMinutes: (Int) -> Unit = {},
    val sleepAtEnd: () -> Unit = {},
    val setPreset: (String) -> Unit = {},
    val queue: () -> List<QueueEntry> = { emptyList() },
    val jumpToQueue: (Int) -> Unit = {},
)

/** The tabletop split, in this screen's own coordinates: the height above the hinge and the hinge itself. */
private data class HingeSplit(val top: Dp, val hinge: Dp)

/** Below this height either side of the hinge, a split would crush the controls; the screen stays whole. */
private val MinSplitPane = 200.dp

/**
 * True black. Speech or music layout by duration, label or the per-item override.
 *
 * 0.4.0: [paned] is true when Now Playing is the right pane of the two-pane layout (no close
 * chevron, Back is left to the Library pane). [hingeInWindow] is the bounds of a half-opened,
 * horizontal fold in window coordinates (tabletop posture); when it crosses this screen with room
 * on both sides, the screen splits at it: title, art and scrubber above, chips and transport
 * below, nothing across the hinge.
 */
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
    /** Chapters and bookmarks of the loaded item, by time. */
    timeline: List<TimelineMark>,
    audioOnly: Boolean,
    /** Fraction of a running "Keep offline" download for this item, null when none. */
    downloadProgress: Float?,
    actions: NowPlayingActions,
    paned: Boolean = false,
    hingeInWindow: Rect? = null,
    /** 0.5.0: a Cast device is on the network, so the cast button shows (it always shows while casting). */
    castAvailable: Boolean = false,
    /** 0.5.0: the current item is a YouTube Music link; Now Playing shows and drives YouTube Music. */
    remote: RemoteUi? = null,
    /** 0.7.0: notes, transcript and the mark feedback for the sermon panel. */
    extras: SermonExtras = SermonExtras(),
) {
    val c = Lumen.colours
    BackHandler(enabled = !paned) { if (!kidsLocked) actions.close() }
    KeepScreenOn(enabled = noInterruptions)
    val showCast = (castAvailable || player.casting) && remote == null

    val tintValue = item?.media?.tint
    val tint = if (!speech && tintValue != null) Color(tintValue) else c.base
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (kidsLocked) {
        // Kids lock keeps its three pads in remote mode too; they drive YouTube Music.
        KidsLock(if (remote != null) player.copy(title = remote.title, isPlaying = remote.isPlaying) else player, actions)
        return
    }

    // Where this screen sits in the window, so a hinge given in window coordinates can be placed.
    var screenTop by remember { mutableFloatStateOf(0f) }
    var screenHeight by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val split: HingeSplit? = hingeInWindow?.let { h ->
        val top = h.top - screenTop
        val bottom = h.bottom - screenTop
        val minPx = with(density) { MinSplitPane.toPx() }
        if (screenHeight > 0f && top >= minPx && screenHeight - bottom >= minPx) {
            with(density) { HingeSplit(top = top.toDp(), hinge = (bottom - top).coerceAtLeast(0f).toDp()) }
        } else {
            null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(tint)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                screenTop = bounds.top
                screenHeight = bounds.height
            },
    ) {
        var sheet by remember { mutableStateOf<NowSheet?>(null) }
        val openSheet: (NowSheet) -> Unit = { sheet = it }
        when {
            split != null && remote == null -> TabletopLayout(split, player, controller, item, labels, settings, speech, timeline, actions, paned, showCast, extras, openSheet)
            player.isVideo && landscape && controller != null && remote == null -> VideoSurface(controller, Modifier.fillMaxSize().background(c.base))
            remote == null && speech -> SermonScreen(player, controller, item, labels, settings, timeline, actions, paned, showCast, extras, openSheet)
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gutter),
            ) {
                Header(labels, false, item, paned, actions, showCast = showCast, casting = player.casting)
                // 0.6.1: a file that cannot play says why, with one way out.
                player.error?.let { message -> PlaybackErrorLine(message, actions.retry) }
                if (remote != null) RemoteLayout(remote, player, labels, actions)
                else MusicLayout(player, controller, item, settings, downloadProgress, actions, openSheet)
            }
        }
        sheet?.let { current ->
            NowPlayingSheet(current, speech && remote == null, player, item, settings, extras, downloadProgress, audioOnly, actions) { sheet = it }
        }
    }
}

/**
 * 0.7.0, Option B: the sermon screen. Title, scrubber and transport at the top, then Speed, Sleep,
 * Mark and More, then the companion panel (Notes, Chapters, Transcript) filling the rest. On a
 * screen too short for the panel (a phone on its side) everything scrolls and the panel becomes
 * two buttons.
 */
@Composable
private fun SermonScreen(
    player: PlayerState,
    controller: MediaController?,
    item: LibraryItem?,
    labels: List<LabelEntity>,
    settings: Settings,
    timeline: List<TimelineMark>,
    actions: NowPlayingActions,
    paned: Boolean,
    showCast: Boolean,
    extras: SermonExtras,
    openSheet: (NowSheet) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val roomy = maxHeight >= 600.dp
        val column = if (roomy) Modifier.fillMaxSize() else Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        var showChapters by remember { mutableStateOf(false) }
        Column(column.padding(horizontal = Gutter)) {
            Header(labels, true, item, paned, actions, showCast = showCast, casting = player.casting)
            player.error?.let { message -> PlaybackErrorLine(message, actions.retry) }
            if (player.isVideo && controller != null) {
                VideoSurface(controller, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(CardRadius)))
                Spacer(Modifier.height(10.dp))
            }
            SpeechTitle(player, compact = player.isVideo || !roomy)
            Spacer(Modifier.height(if (roomy) 18.dp else 10.dp))
            // Paused on a speech item, Lumen is holding the place: the head turns to the ember.
            Scrubber(
                positionMs = player.positionMs,
                durationMs = player.durationMs,
                enabled = player.hasItem,
                thin = false,
                holdingPlace = player.hasItem && !player.isPlaying,
                onSeek = actions.seekTo,
            )
            Spacer(Modifier.height(if (roomy) 18.dp else 10.dp))
            SpeechTransport(player, settings, actions)
            Spacer(Modifier.height(if (roomy) 18.dp else 12.dp))
            SermonActions(player, settings, actions, openSheet)
            MarkFeedback(extras, actions)
            if (player.casting) CastingNote()
            Spacer(Modifier.height(12.dp))
            if (roomy) {
                SermonPanel(item, player, timeline, extras, actions, Modifier.weight(1f).padding(bottom = 12.dp))
            } else {
                CompanionLinks(extras, timeline, actions, onChapters = { showChapters = !showChapters })
                if (showChapters) Box(Modifier.fillMaxWidth().height(320.dp).padding(top = 8.dp)) { ChaptersTab(player, timeline, actions) }
                if (player.nextTitle != null) UpNext(player)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Chevron (not in the paned layout), the eyebrow, the label button. 0.5.0: the cast button sits
 * before the label button while a Cast device is on the network (an empty slot balances it on the
 * left so the eyebrow stays centred); it is ink while casting.
 */
@Composable
private fun Header(
    labels: List<LabelEntity>,
    speech: Boolean,
    item: LibraryItem?,
    paned: Boolean,
    actions: NowPlayingActions,
    showCast: Boolean = false,
    casting: Boolean = false,
) {
    val c = Lumen.colours
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (paned) Spacer(Modifier.size(TouchTarget)) else IconTap(LumenIcons.ChevronDown, "Close", c.text, onClick = actions.close)
        if (showCast) Spacer(Modifier.size(TouchTarget))
        Spacer(Modifier.weight(1f))
        val labelName = labels.firstOrNull()?.name
        val eyebrow = listOfNotNull(labelName, if (speech) "Sermon" else "Music").joinToString(" · ")
        Text(eyebrow.uppercase(), style = Lumen.type.label, color = c.text2)
        Spacer(Modifier.weight(1f))
        if (showCast) CastButton(casting)
        IconTap(LumenIcons.Label, "Labels", c.text, enabled = item != null, onClick = actions.openLabelSheet)
    }
}

/**
 * 0.5.0: Media3's Compose MediaRouteButton (media3-cast): a 48 dp Material3 IconButton that opens
 * the route chooser, or the route controller while casting. Its glyph takes LocalContentColor.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun CastButton(casting: Boolean) {
    val c = Lumen.colours
    CompositionLocalProvider(LocalContentColor provides if (casting) c.accentInk else c.text) {
        MediaRouteButton()
    }
}

/** 0.5.0: the one ice line under the chip row while casting. */
@Composable
private fun CastingNote() {
    Text(
        "Casting: sound settings apply on the phone only",
        style = Lumen.type.bodyS,
        color = Lumen.colours.ice,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Tabletop: the part above the hinge holds the header, title, art or picture, and scrubber; the
 * part below holds the transport, the action row and, for a sermon, Notes and Chapters as buttons.
 * Nothing crosses the hinge.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TabletopLayout(
    split: HingeSplit,
    player: PlayerState,
    controller: MediaController?,
    item: LibraryItem?,
    labels: List<LabelEntity>,
    settings: Settings,
    speech: Boolean,
    timeline: List<TimelineMark>,
    actions: NowPlayingActions,
    paned: Boolean,
    showCast: Boolean,
    extras: SermonExtras,
    openSheet: (NowSheet) -> Unit,
) {
    var showChapters by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(split.top)
                .statusBarsPadding()
                .padding(horizontal = Gutter),
        ) {
            Header(labels, speech, item, paned, actions, showCast = showCast, casting = player.casting)
            if (player.isVideo && controller != null) {
                // A video takes the full width above the hinge.
                VideoSurface(controller, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(CardRadius)))
            } else if (speech) {
                SpeechTitle(player)
                Spacer(Modifier.weight(1f))
            } else {
                Row(Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ArtBlock(item, player.title, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) { MusicTitle(player) }
                }
            }
            Scrubber(
                positionMs = player.positionMs,
                durationMs = player.durationMs,
                enabled = player.hasItem,
                thin = !speech,
                holdingPlace = speech && player.hasItem && !player.isPlaying,
                onSeek = actions.seekTo,
            )
            Spacer(Modifier.height(8.dp))
        }
        // The hinge itself: left empty.
        Spacer(Modifier.fillMaxWidth().height(split.hinge))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Gutter),
        ) {
            Spacer(Modifier.height(8.dp))
            if (speech) {
                SpeechTransport(player, settings, actions)
                Spacer(Modifier.height(12.dp))
                SermonActions(player, settings, actions, openSheet)
                MarkFeedback(extras, actions)
                Spacer(Modifier.height(8.dp))
                CompanionLinks(extras, timeline, actions, onChapters = { showChapters = !showChapters })
                if (showChapters) Box(Modifier.fillMaxWidth().height(320.dp).padding(top = 8.dp)) { ChaptersTab(player, timeline, actions) }
            } else {
                MusicTransport(player, actions)
                Spacer(Modifier.height(12.dp))
                MusicActions(player, settings, actions, openSheet)
                UpNext(player)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}


/** The sermon title and the speaker. 0.7.0: no hidden long press; "Show as music" is in More. */
@Composable
private fun SpeechTitle(player: PlayerState, compact: Boolean = false) {
    val c = Lumen.colours
    Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
    Text(
        player.title.ifBlank { "Nothing loaded" },
        style = if (compact) Lumen.type.displayS else Lumen.type.displayL,
        color = c.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (player.artist.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(player.artist, style = Lumen.type.body, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Back, the 80 dp play button, forward. */
@Composable
private fun SpeechTransport(player: PlayerState, settings: Settings, actions: NowPlayingActions) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(seconds = settings.skipBackSeconds, forward = false, enabled = player.hasItem, onClick = actions.seekBack)
        PlayButton(isPlaying = player.isPlaying, enabled = player.hasItem || player.connected, size = 80.dp, onClick = actions.playPause)
        SkipButton(seconds = settings.skipForwardSeconds, forward = true, enabled = player.hasItem, onClick = actions.seekForward)
    }
}






/** The music title and the artist. 0.7.0: no hidden long press; "Show as sermon" is in More. */
@Composable
private fun MusicTitle(player: PlayerState) {
    val c = Lumen.colours
    Text(
        player.title.ifBlank { "Nothing loaded" },
        style = Lumen.type.displayS,
        color = c.text,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (player.artist.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(player.artist, style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** Shuffle, previous, the 72 dp play button, next, repeat. */
@Composable
private fun MusicTransport(player: PlayerState, actions: NowPlayingActions) {
    val c = Lumen.colours
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTap(LumenIcons.Shuffle, "Shuffle", if (player.shuffle) c.ice else c.text3, size = 20.dp, onClick = actions.toggleShuffle)
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
            if (player.repeat != Player.REPEAT_MODE_OFF) c.ice else c.text3,
            size = 20.dp,
            onClick = actions.cycleRepeat,
        )
    }
    if (player.repeat == Player.REPEAT_MODE_ONE) {
        Text("Repeating this one", style = Lumen.type.labelS, color = c.text3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/**
 * 0.7.0, Option A: art (or the picture), title, scrubber, transport, then Sleep, Labels, Sound,
 * Queue and More in one row, and what plays next.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MusicLayout(
    player: PlayerState,
    controller: MediaController?,
    item: LibraryItem?,
    settings: Settings,
    downloadProgress: Float?,
    actions: NowPlayingActions,
    openSheet: (NowSheet) -> Unit,
) {
    Spacer(Modifier.height(6.dp))
    if (player.isVideo && controller != null) {
        VideoSurface(
            controller,
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(CardRadius)),
        )
    } else {
        // The art keeps a margin so the controls stay within reach.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ArtBlock(item, player.title, Modifier.fillMaxWidth(0.82f).aspectRatio(1f))
        }
    }
    Spacer(Modifier.height(16.dp))
    MusicTitle(player)
    if (downloadProgress != null) ProgressLine(fraction = downloadProgress, thickness = 1.dp, modifier = Modifier.padding(top = 6.dp))
    Spacer(Modifier.height(10.dp))
    Scrubber(positionMs = player.positionMs, durationMs = player.durationMs, enabled = player.hasItem, thin = true, onSeek = actions.seekTo)
    Spacer(Modifier.height(14.dp))
    MusicTransport(player, actions)
    Spacer(Modifier.height(16.dp))
    MusicActions(player, settings, actions, openSheet)
    if (player.casting) CastingNote()
    UpNext(player)
    Spacer(Modifier.height(16.dp))
}

/**
 * 0.5.0: remote mode, a YouTube Music item. YouTube Music's art, title and artist, the "via YouTube
 * Music" chip (tap opens YouTube Music), a greyed EQ chip with its reason, then YouTube Music's own
 * position on the scrubber and cyan transport. Without Notification access, or before YouTube
 * Music's session appears, one line and "Open YouTube Music" take the place of the controls, and
 * the Lumen queue does not move on by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteLayout(remote: RemoteUi, player: PlayerState, labels: List<LabelEntity>, actions: NowPlayingActions) {
    val c = Lumen.colours
    Spacer(Modifier.height(10.dp))
    RemoteArt(remote)
    Spacer(Modifier.height(16.dp))
    Text(remote.title.ifBlank { "YouTube Music" }, style = Lumen.type.displayS, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (remote.artist.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(remote.artist, style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(8.dp))
    // 0.6.1: the chips wrap onto a second line instead of running off the screen.
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(text = YtmLink.SOURCE_LABEL, onClick = actions.openYouTubeMusic)
        labels.take(2).forEach { LabelChip(name = it.name, hue = it.hue, onClick = actions.openLabelSheet) }
        StatusChip(text = "EQ", enabled = false, onClick = {})
    }
    Text("EQ is handled by YouTube Music", style = Lumen.type.bodyS, color = c.text3, modifier = Modifier.padding(top = 2.dp))
    Spacer(Modifier.height(8.dp))
    if (remote.connected) {
        Scrubber(positionMs = remote.positionMs, durationMs = remote.durationMs, enabled = true, thin = true, onSeek = actions.seekTo)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTap(LumenIcons.SkipPrevious, "Previous in YouTube Music", c.accentInk, size = 28.dp, onClick = actions.previous)
            PlayButton(isPlaying = remote.isPlaying, enabled = true, size = 72.dp, onClick = actions.playPause)
            IconTap(LumenIcons.SkipNext, "Next in YouTube Music", c.accentInk, size = 28.dp, onClick = actions.next)
        }
    } else {
        Spacer(Modifier.height(8.dp))
        Text("Plays in YouTube Music. Pause and skip it there", style = Lumen.type.body, color = c.text2)
        if (!remote.access && RemoteController.CONTROL_ENABLED) {
            Text(
                "Allow Lumen to control YouTube Music in Settings to see it here and move on to the next item",
                style = Lumen.type.bodyS,
                color = c.text3,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryPill("Open YouTube Music", icon = LumenIcons.Play, onClick = actions.openYouTubeMusic)
        // 0.6.1: the Lumen queue carries on from here, so a YouTube Music song is never a dead end.
        if (player.nextTitle != null) {
            Spacer(Modifier.height(10.dp))
            SecondaryPill("Next in Lumen", icon = LumenIcons.SkipNext, onClick = actions.next)
        }
    }
    UpNext(player)
    Spacer(Modifier.height(16.dp))
}

/** YouTube Music's own art from its session, or the flat block with the first letter. */
@Composable
private fun RemoteArt(remote: RemoteUi) {
    val c = Lumen.colours
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(CardRadius))
            .background(c.elevated),
        contentAlignment = Alignment.Center,
    ) {
        val art = remote.art
        if (art != null) {
            Image(bitmap = art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                remote.title.trim().take(1).uppercase().ifBlank { "Y" },
                style = Lumen.type.displayL.copy(fontSize = Lumen.type.displayL.fontSize * 4),
                color = c.line,
            )
            Text(
                YtmLink.FOLDER_NAME.uppercase(),
                style = Lumen.type.labelS,
                color = c.text2,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 12.dp),
            )
        }
    }
}

/** Embedded art from the cache, or a flat block with the first letter. Tint comes from data. */
@Composable
private fun ArtBlock(item: LibraryItem?, title: String, modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
    val c = Lumen.colours
    // Null means the art has not been read yet (the ViewModel is fetching it), "" means none.
    val path = item?.media?.artPath?.takeIf { it.isNotBlank() }
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
        modifier = modifier
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
    // 0.6.1: the screen stays on while a video plays (the picture itself holds it, so nothing
    // else on the screen has to know), and sleeps again when it pauses.
    val viewRef = remember { arrayOfNulls<PlayerView>(1) }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                viewRef[0]?.keepScreenOn = isPlaying
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = controller
                keepScreenOn = controller.isPlaying
                viewRef[0] = this
            }
        },
        update = { view -> if (view.player !== controller) view.player = controller },
        onRelease = { view ->
            view.keepScreenOn = false
            view.player = null
            viewRef[0] = null
        },
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
 * is a two second press on the top-right corner. The play pad is the cyan fill with a black
 * glyph; there is no scrubber here, so the ember never shows in Kids lock.
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
                            // Released or cancelled (finger slid off), the hold is over either
                            // way; a cancelled press must not unlock two seconds later.
                            tryAwaitRelease()
                            holding = false
                            job.cancel()
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
            .background(if (filled) c.accentFill else Color.Transparent)
            .border(1.dp, if (filled) c.accentFill else c.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (filled) c.onAccentFill else c.text, modifier = Modifier.size(32.dp))
    }
}

/** 0.6.1: why the current file cannot play, and Try again. */
@Composable
private fun PlaybackErrorLine(message: String, onRetry: () -> Unit) {
    val c = Lumen.colours
    ListCard(modifier = Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = Lumen.type.bodyS, color = c.text, modifier = Modifier.weight(1f))
            TextAction("Try again", onClick = onRetry)
        }
    }
}
