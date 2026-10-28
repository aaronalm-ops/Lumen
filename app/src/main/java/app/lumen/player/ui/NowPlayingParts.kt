package app.lumen.player.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.player.data.EqPreset
import app.lumen.player.data.NotesEntity
import app.lumen.player.data.SegmentEntity
import app.lumen.player.data.Settings
import app.lumen.player.data.SettingsRepository
import app.lumen.player.data.drive.isDrive
import app.lumen.player.notes.NotesRepository
import app.lumen.player.notes.SermonNotes

/**
 * 0.7.0: Now Playing redesign (Aaron, 2026-10-28: Option B for sermons, Option A for music).
 * Nothing scrolls sideways and nothing needs a long press. Every action is an icon with a word
 * under it; anything used less lives in one More sheet in plain words.
 */

/** What the sermon panel and the action rows need beyond the player. */
data class SermonExtras(
    val notes: NotesEntity? = null,
    val segments: List<SegmentEntity> = emptyList(),
    /** Why notes cannot be written now (no key, no Wi-Fi), or null. */
    val notesBlocker: String? = null,
    val notesAvailable: Boolean = false,
    /** The moment just marked, for "Marked 47:12 · Name it". */
    val justMarked: Long? = null,
    /** A chapter or bookmark just removed, for "Removed · Undo". */
    val lastRemoved: TimelineMark? = null,
    /** A "No interruptions" label is on the item: the sleep timer is not offered. */
    val noInterruptions: Boolean = false,
)

enum class NowSheet { MORE, SPEED, SLEEP, SOUND, QUEUE }

/** One action: a 64 dp tile, the icon over a word (or the current value, in ice). */
@Composable
fun ActionTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .height(64.dp)
            .clip(shape)
            .background(c.surface)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val tint = when {
            !enabled -> c.text3
            active -> c.accentInk
            else -> c.text
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            value ?: label,
            style = Lumen.type.labelS,
            color = when {
                !enabled -> c.text3
                active -> c.accentInk
                value != null -> c.ice
                else -> c.text2
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun speedValue(speed: Float): String = if (kotlin.math.abs(speed - 1f) < 0.01f) "Speed" else speedLabel(speed)

fun sleepValue(settings: Settings): String? {
    if (!settings.sleepActive) return null
    if (settings.sleepMinutes == -1) return "End"
    val left = ((settings.sleepEndAt - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
    return "$left min"
}

/** Sermon: Speed, Sleep, Mark, More. */
@Composable
fun SermonActions(player: PlayerState, settings: Settings, actions: NowPlayingActions, onSheet: (NowSheet) -> Unit, hideSleep: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionTile(LumenIcons.Speed, "Speed", Modifier.weight(1f), value = speedValue(player.speed).takeIf { it != "Speed" },
            active = false, enabled = !player.casting) { onSheet(NowSheet.SPEED) }
        if (!hideSleep) {
            ActionTile(LumenIcons.Moon, "Sleep", Modifier.weight(1f), value = sleepValue(settings), active = settings.sleepActive) { onSheet(NowSheet.SLEEP) }
        }
        ActionTile(LumenIcons.Bookmark, "Mark", Modifier.weight(1f), enabled = player.hasItem, onClick = actions.markNow)
        ActionTile(LumenIcons.More, "More", Modifier.weight(1f)) { onSheet(NowSheet.MORE) }
    }
}

/** Music: Sleep, Labels, Sound, Queue, More. */
@Composable
fun MusicActions(player: PlayerState, settings: Settings, actions: NowPlayingActions, onSheet: (NowSheet) -> Unit, hideSleep: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!hideSleep) {
            ActionTile(LumenIcons.Moon, "Sleep", Modifier.weight(1f), value = sleepValue(settings), active = settings.sleepActive) { onSheet(NowSheet.SLEEP) }
        }
        ActionTile(LumenIcons.Label, "Labels", Modifier.weight(1f), enabled = player.hasItem, onClick = actions.openLabelSheet)
        ActionTile(LumenIcons.Sound, "Sound", Modifier.weight(1f), value = settings.eqPreset, enabled = settings.eqEngine != "none") { onSheet(NowSheet.SOUND) }
        ActionTile(LumenIcons.Queue, "Queue", Modifier.weight(1f), enabled = player.queueSize > 1) { onSheet(NowSheet.QUEUE) }
        ActionTile(LumenIcons.More, "More", Modifier.weight(1f)) { onSheet(NowSheet.MORE) }
    }
}

/** "Marked 47:12 · Name it", or "Removed · Undo", for a few seconds after the act. */
@Composable
fun MarkFeedback(extras: SermonExtras, actions: NowPlayingActions) {
    val c = Lumen.colours
    val marked = extras.justMarked
    val removed = extras.lastRemoved
    if (marked == null && removed == null) return
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (marked != null) {
            Text("Marked ${formatTime(marked)}", style = Lumen.type.bodyS, color = c.text2, modifier = Modifier.weight(1f))
            TextAction("Name it", onClick = { actions.nameMark(marked) })
        } else if (removed != null) {
            Text("Removed ${removed.title}", style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextAction("Undo", onClick = actions.undoRemove)
        }
    }
}

/**
 * Option B: the sermon companion panel, filling the lower part of the screen. Notes, Chapters and
 * Transcript, each with its own scroll. Opens on Notes once they exist, else on Chapters when
 * there are marks, else on Notes with the way to write them.
 */
@Composable
fun SermonPanel(
    item: LibraryItem?,
    player: PlayerState,
    timeline: List<TimelineMark>,
    extras: SermonExtras,
    actions: NowPlayingActions,
    modifier: Modifier = Modifier,
) {
    val c = Lumen.colours
    val parsed = remember(extras.notes?.json) { extras.notes?.json?.takeIf { it.isNotBlank() }?.let { SermonNotes.parse(it) } }
    val defaultTab = when {
        parsed != null -> 0
        timeline.isNotEmpty() -> 1
        else -> 0
    }
    var tab by rememberSaveable(item?.uri) { mutableStateOf(defaultTab) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).background(c.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelTab("Notes", tab == 0) { tab = 0 }
            PanelTab(if (timeline.isEmpty()) "Chapters" else "Chapters ${timeline.size}", tab == 1) { tab = 1 }
            if (extras.segments.isNotEmpty()) PanelTab("Transcript", tab == 2) { tab = 2 }
            Spacer(Modifier.weight(1f))
            if (tab != 1 && (parsed != null || extras.segments.isNotEmpty())) {
                IconTap(LumenIcons.Expand, "Open notes full screen", c.text2, size = 20.dp, onClick = actions.openNotes)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab == 2 && extras.segments.isNotEmpty() -> TranscriptView(
                    segments = extras.segments,
                    query = "",
                    playingMs = player.positionMs,
                    onQuery = {},
                    onPlayAt = actions.seekTo,
                    follow = true,
                    showSearch = false,
                )
                tab == 1 -> ChaptersTab(player, timeline, actions)
                item == null -> Unit
                !extras.notesAvailable -> PanelEmpty("Notes are for audio recordings on the phone or in Drive")
                else -> NotesView(
                    item = item,
                    notes = extras.notes,
                    parsed = parsed,
                    blocker = extras.notesBlocker,
                    onWrite = actions.writeNotes,
                    onPlayAt = actions.seekTo,
                    onSetUp = actions.setUpNotes,
                    hereMs = player.positionMs,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun PanelTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(50)
    Box(Modifier.height(TouchTarget).clip(shape).clickable(onClick = onClick).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .height(30.dp)
                .clip(shape)
                .background(if (selected) c.chipSelected else Color.Transparent)
                .border(1.dp, if (selected) c.chipSelectedEdge else c.line, shape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text.uppercase(), style = Lumen.type.labelS, color = if (selected) c.onChipSelected else c.text)
        }
    }
}

@Composable
private fun PanelEmpty(text: String) {
    val c = Lumen.colours
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Lumen.type.bodyS, color = c.text2, textAlign = TextAlign.Center)
    }
}

/** Chapters and bookmarks by time. Tap plays from it; the ⋮ on each row names or removes it. */
@Composable
fun ChaptersTab(player: PlayerState, timeline: List<TimelineMark>, actions: NowPlayingActions) {
    val c = Lumen.colours
    if (timeline.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Tap Mark to save a moment. Name it to make it a chapter.", style = Lumen.type.bodyS, color = c.text2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            TextAction("New chapter here", enabled = player.hasItem, onClick = actions.newChapter)
        }
        return
    }
    val current = timeline.indexOfLast { it.positionMs <= player.positionMs }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 16.dp)) {
        itemsIndexed(timeline, key = { _, m -> "${m.chapter?.id}-${m.bookmark?.id}-${m.positionMs}" }) { index, mark ->
            ChapterRow(mark, isCurrent = index == current, actions)
            if (index < timeline.lastIndex) HorizontalDivider(color = c.line, thickness = 1.dp)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextAction("New chapter here", enabled = player.hasItem, onClick = actions.newChapter)
                TextAction("Share list", onClick = actions.exportNotes)
            }
        }
    }
}

@Composable
private fun ChapterRow(mark: TimelineMark, isCurrent: Boolean, actions: NowPlayingActions) {
    val c = Lumen.colours
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable { actions.seekTo(mark.positionMs) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when {
                isCurrent -> Box(Modifier.size(6.dp).background(c.accentInk, CircleShape))
                mark.bookmark != null -> Icon(LumenIcons.BookmarkFilled, contentDescription = null, tint = c.text2, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            mark.title,
            style = if (mark.isChapter) Lumen.type.body else Lumen.type.bodyS,
            color = if (mark.isChapter) c.text else c.text2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(formatTime(mark.positionMs), style = Lumen.type.timeS, color = if (isCurrent) c.accentInk else c.text2)
        Box {
            IconTap(LumenIcons.More, "More for ${mark.title}", c.text2, size = 18.dp) { menu = true }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = RoundedCornerShape(CardRadius),
                containerColor = c.elevated,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, c.line),
            ) {
                if (mark.bookmark != null) {
                    DropdownMenuItem(text = { Text("Name it", style = Lumen.type.body, color = c.text) }, onClick = {
                        menu = false
                        actions.nameBookmark(mark)
                    })
                }
                DropdownMenuItem(text = { Text("Remove", style = Lumen.type.body, color = c.danger) }, onClick = {
                    menu = false
                    actions.removeMark(mark)
                })
            }
        }
    }
}

/** A short "Notes, Chapters, Transcript" strip for layouts too short for the panel (landscape, tabletop). */
@Composable
fun CompanionLinks(extras: SermonExtras, timeline: List<TimelineMark>, actions: NowPlayingActions, onChapters: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (extras.notesAvailable) {
            ActionTile(LumenIcons.Notes, if (extras.notes?.status == NotesRepository.STATUS_WORKING) "Writing" else "Notes", Modifier.weight(1f),
                active = extras.notes?.status == NotesRepository.STATUS_DONE, onClick = actions.openNotes)
        }
        ActionTile(LumenIcons.Bookmark, if (timeline.isEmpty()) "Chapters" else "Chapters ${timeline.size}", Modifier.weight(1f), onClick = onChapters)
    }
}

// Sheets.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(
    sheet: NowSheet,
    speech: Boolean,
    player: PlayerState,
    item: LibraryItem?,
    settings: Settings,
    extras: SermonExtras,
    downloadProgress: Float?,
    audioOnly: Boolean,
    actions: NowPlayingActions,
    onSheet: (NowSheet?) -> Unit,
) {
    val c = Lumen.colours
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { onSheet(null) },
        sheetState = state,
        containerColor = c.elevated,
        contentColor = c.text,
        shape = RoundedCornerShape(topStart = HeroRadius, topEnd = HeroRadius),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(c.line, RoundedCornerShape(2.dp)))
            }
        },
    ) {
        // The Queue list can be thousands long, so it is lazy and the sheet does not scroll around it.
        val scroll = if (sheet == NowSheet.QUEUE) Modifier else Modifier.verticalScroll(rememberScrollState())
        Column(
            Modifier
                .fillMaxWidth()
                .then(scroll)
                .padding(horizontal = Gutter)
                .padding(top = 8.dp, bottom = 20.dp)
                .navigationBarsPadding(),
        ) {
            when (sheet) {
                NowSheet.SPEED -> {
                    SheetTitle("Speed")
                    SettingsRepository.speeds.forEach { s ->
                        ChoiceRow(speedLabel(s), selected = kotlin.math.abs(player.speed - s) < 0.01f) {
                            actions.setSpeed(s)
                            onSheet(null)
                        }
                    }
                }
                NowSheet.SLEEP -> {
                    SheetTitle("Sleep timer")
                    val canEnd = player.hasItem && player.durationMs > 0
                    if (canEnd) {
                        ChoiceRow("End of this recording", detail = formatTime((player.durationMs - player.positionMs).coerceAtLeast(0L)) + " left",
                            selected = settings.sleepActive && settings.sleepMinutes == -1) {
                            actions.sleepAtEnd()
                            onSheet(null)
                        }
                    }
                    SettingsRepository.sleepSheetChoices.forEach { m ->
                        ChoiceRow(if (m >= 60 && m % 60 == 0) "${m / 60} hour${if (m > 60) "s" else ""}" else "$m minutes",
                            selected = settings.sleepActive && settings.sleepMinutes == m) {
                            actions.setSleepMinutes(m)
                            onSheet(null)
                        }
                    }
                    if (settings.sleepActive) {
                        ChoiceRow("Turn off", danger = true) {
                            actions.setSleepMinutes(0)
                            onSheet(null)
                        }
                    }
                }
                NowSheet.SOUND -> {
                    SheetTitle("Sound")
                    EqPreset.order.filter { it != EqPreset.CUSTOM || settings.eqCustom.any { g -> g != 0f } }.forEach { p ->
                        ChoiceRow(p, detail = presetDetail(p), selected = settings.eqPreset == p, enabled = settings.eqEngine != "none") {
                            actions.setPreset(p)
                            onSheet(null)
                        }
                    }
                }
                NowSheet.QUEUE -> {
                    SheetTitle("Queue")
                    val entries = remember { actions.queue() }
                    if (entries.isEmpty()) Text("Nothing queued", style = Lumen.type.bodyS, color = c.text2)
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                        initialFirstVisibleItemIndex = (entries.indexOfFirst { it.current } - 2).coerceAtLeast(0),
                    )
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), state = listState) {
                        itemsIndexed(entries, key = { _, e -> e.index }) { _, e ->
                            ChoiceRow(e.title, selected = e.current) {
                                actions.jumpToQueue(e.index)
                                onSheet(null)
                            }
                        }
                    }
                }
                NowSheet.MORE -> {
                    SheetTitle(player.title.ifBlank { "More" })
                    if (speech) {
                        MoreRow("Sound", settings.eqPreset, enabled = settings.eqEngine != "none") { onSheet(NowSheet.SOUND) }
                        val levelOutAvailable = settings.eqEngine != "eq" && settings.eqEngine != "none"
                        SwitchRow("Level out voices", if (levelOutAvailable) "Brings quiet and loud speakers closer" else "Needs an equaliser this phone does not offer",
                            checked = settings.levelOutVoices && levelOutAvailable, enabled = levelOutAvailable, onChange = actions.setLevelOutVoices)
                        SwitchRow("Trim silences", "Skips long pauses in speech", checked = settings.trimSilences, enabled = true, onChange = actions.setTrimSilences)
                        MoreRow("Labels", item?.let { "${it.labelIds.size}" }, enabled = item != null) {
                            onSheet(null)
                            actions.openLabelSheet()
                        }
                        MoreRow("New chapter here", formatTime(player.positionMs), enabled = player.hasItem) {
                            onSheet(null)
                            actions.newChapter()
                        }
                    } else {
                        MoreRow("Speed", speedLabel(player.speed), enabled = !player.casting) { onSheet(NowSheet.SPEED) }
                    }
                    if (item?.media?.isDrive == true) {
                        val kept = item.media.offlinePath != null
                        MoreRow(
                            if (kept) "Remove the downloaded copy" else "Keep offline",
                            when {
                                downloadProgress != null -> "Saving ${(downloadProgress * 100).toInt()}%"
                                kept -> "Kept"
                                else -> "Download over Wi-Fi"
                            },
                            enabled = downloadProgress == null,
                        ) {
                            onSheet(null)
                            actions.toggleOffline()
                        }
                    }
                    if (player.isVideo) {
                        SwitchRow("Audio only when you leave", "Keeps the sound and drops the picture while the screen is away",
                            checked = audioOnly, enabled = true, onChange = actions.setAudioOnly)
                    }
                    if (speech && extras.notesAvailable) {
                        MoreRow("Notes and transcript", "Full screen") {
                            onSheet(null)
                            actions.openNotes()
                        }
                    }
                    MoreRow(if (speech) "Show as music" else "Show as sermon", "For this file", enabled = player.hasItem) {
                        onSheet(null)
                        actions.toggleLayout()
                    }
                    if (speech) {
                        MoreRow("Share chapter list", "As a text file", enabled = player.hasItem) {
                            onSheet(null)
                            actions.exportNotes()
                        }
                    }
                    if (player.casting) {
                        Text("Casting: sound settings apply on the phone only", style = Lumen.type.bodyS, color = c.ice, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

private fun presetDetail(p: String): String = when (p) {
    EqPreset.SPEECH -> "Clearer voices"
    EqPreset.KIDS -> "Softer top, gentle on small ears"
    EqPreset.WORSHIP -> "Warm and open"
    EqPreset.BASS -> "More low end"
    EqPreset.FLAT -> "As recorded"
    else -> "Your own bands from Settings"
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = Lumen.type.displayS, color = Lumen.colours.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun ChoiceRow(text: String, detail: String? = null, selected: Boolean = false, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    val c = Lumen.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(text, style = Lumen.type.body, color = when {
                !enabled -> c.text3
                danger -> c.danger
                selected -> c.accentInk
                else -> c.text
            }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = Lumen.type.bodyS, color = c.text2)
        }
        if (selected) Icon(LumenIcons.Check, contentDescription = "Selected", tint = c.accentInk, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MoreRow(text: String, value: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Lumen.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Lumen.type.body, color = if (enabled) c.text else c.text3, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = Lumen.type.mono, color = c.ice)
    }
}

@Composable
private fun SwitchRow(title: String, explanation: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = Lumen.colours
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = Lumen.type.body, color = if (enabled) c.text else c.text3)
            Text(explanation, style = Lumen.type.bodyS, color = c.text2)
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) c.accentFill else c.surface)
                .border(1.dp, if (checked) c.accentFill else c.switchOffEdge, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(LumenIcons.Check, contentDescription = null, tint = c.onAccentFill, modifier = Modifier.size(16.dp))
        }
    }
}
