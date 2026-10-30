package app.lumen.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.drive.isDrive
import app.lumen.player.remote.isYtm
import kotlin.math.roundToInt

/** What the Playlists tab can ask for. */
class PlaylistActions(
    val open: (Long?) -> Unit,
    val newPlaylist: () -> Unit,
    val edit: (LabelEntity) -> Unit,
    val play: (LabelEntity, LibraryItem?, Boolean) -> Unit,
    val reorder: (Long, List<PlaylistEntry>) -> Unit,
    val remove: (Long, PlaylistEntry) -> Unit,
    val openPicker: (Boolean) -> Unit,
    val toggleMember: (Long, LibraryItem) -> Unit,
    val back: () -> Unit,
)

/**
 * 0.8.0: Playlists, the third tab. One list across every source: songs and sermons on the phone,
 * in Drive, Suno songs and YouTube Music links, in an order the owner sets by dragging. A playlist
 * is the same thing a label was (labels keep their Kids lock, No interruptions and Shuffle
 * settings); the tab gives it a home of its own.
 */
@Composable
fun PlaylistsScreen(
    labels: List<LabelEntity>,
    entries: Map<Long, List<PlaylistEntry>>,
    items: List<LibraryItem>,
    player: PlayerState,
    openId: Long?,
    picker: Boolean,
    actions: PlaylistActions,
) {
    val c = Lumen.colours
    val open = labels.firstOrNull { it.id == openId }
    BackHandler(enabled = open != null || picker) { actions.back() }
    Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding()) {
        when {
            open != null && picker -> Picker(open, entries[open.id].orEmpty(), items, labels, actions)
            open != null -> Detail(open, entries[open.id].orEmpty(), player, labels, actions)
            else -> Overview(labels, entries, actions)
        }
    }
}

@Composable
private fun Overview(labels: List<LabelEntity>, entries: Map<Long, List<PlaylistEntry>>, actions: PlaylistActions) {
    val c = Lumen.colours
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = Gutter, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Playlists", style = Lumen.type.displayM, color = c.text, modifier = Modifier.weight(1f))
        TextAction("New playlist", onClick = actions.newPlaylist)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "One list across the phone, Drive, Suno and YouTube Music. Add from here, or from any song with Playlists.",
                style = Lumen.type.bodyS,
                color = c.text2,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(labels, key = { it.id }) { label ->
            val members = entries[label.id].orEmpty()
            ListCard {
                Row(
                    Modifier.fillMaxWidth().clickable { actions.open(label.id) }.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.thumb), contentAlignment = Alignment.Center) {
                        HueDot(label.hue, size = 12.dp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label.name, style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(playlistMeta(members), style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconTap(LumenIcons.Play, "Play ${label.name}", if (members.isEmpty()) c.text3 else c.accentInk, enabled = members.isNotEmpty()) {
                        actions.play(label, null, label.shuffleOnOpen)
                    }
                }
            }
        }
    }
}

/** "12 · 3 h 10 min · Phone, Drive, YouTube Music". */
private fun playlistMeta(members: List<PlaylistEntry>): String {
    if (members.isEmpty()) return "Empty"
    val total = members.sumOf { it.item.media.durationMs.coerceAtLeast(0L) }
    val sources = buildList {
        if (members.any { !it.item.media.isDrive && !it.item.media.isYtm }) add("Phone")
        if (members.any { it.item.media.isDrive }) add("Drive")
        if (members.any { it.item.media.isYtm }) add("YouTube Music")
    }
    return listOfNotNull(
        "${members.size}",
        total.takeIf { it > 0 }?.let { durationWords(it) },
        sources.joinToString(", ").ifBlank { null },
    ).joinToString(" · ")
}

private fun durationWords(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

private val PlaylistRowHeight = 64.dp

@Composable
private fun Detail(label: LabelEntity, members: List<PlaylistEntry>, player: PlayerState, labels: List<LabelEntity>, actions: PlaylistActions) {
    val c = Lumen.colours
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(LumenIcons.ChevronLeft, "All playlists", c.text, onClick = actions.back)
        Spacer(Modifier.width(4.dp))
        HueDot(label.hue, size = 10.dp)
        Spacer(Modifier.width(10.dp))
        Text(label.name, style = Lumen.type.displayS, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextAction("Edit", onClick = { actions.edit(label) })
    }
    Column(Modifier.padding(horizontal = Gutter)) {
        Text(playlistMeta(members), style = Lumen.type.bodyS, color = c.text2)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryPill("Play", icon = LumenIcons.Play, enabled = members.isNotEmpty()) { actions.play(label, null, false) }
            SecondaryPill("Shuffle", icon = LumenIcons.Shuffle, enabled = members.isNotEmpty()) { actions.play(label, null, true) }
            SecondaryPill("Add", icon = LumenIcons.Plus) { actions.openPicker(true) }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (members.isEmpty()) {
        Box(Modifier.padding(horizontal = Gutter)) {
            EmptyState(
                title = "Nothing in ${label.name} yet",
                body = "Add songs and sermons from the phone, Drive, Suno or YouTube Music. They play in the order you set here.",
                action = "Add songs",
                onAction = { actions.openPicker(true) },
            )
        }
        return
    }
    ReorderableList(label, members, player, labels, actions)
}

/**
 * The members in order. Drag the handle on the right to move one; the new order is saved when the
 * finger lifts. Tap a row to play the playlist from there.
 */
@Composable
private fun ReorderableList(label: LabelEntity, members: List<PlaylistEntry>, player: PlayerState, labels: List<LabelEntity>, actions: PlaylistActions) {
    val c = Lumen.colours
    var order by remember(members) { mutableStateOf(members) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { PlaylistRowHeight.toPx() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp)) {
        itemsIndexed(order, key = { _, e -> e.item.uri }) { index, entry ->
            val isDragging = dragging == entry.item.uri
            val playing = player.hasItem && player.mediaId == entry.item.uri
            var menu by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .background(if (isDragging) c.elevated else c.base),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(PlaylistRowHeight)
                        .clickable { actions.play(label, entry.item, false) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        style = Lumen.type.timeS,
                        color = if (playing) c.accentInk else c.text3,
                        modifier = Modifier.width(28.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(entry.item.media.title, style = Lumen.type.body, color = if (playing) c.accentInk else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(rowMeta(entry.item, labels), style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconTap(LumenIcons.More, "More for ${entry.item.media.title}", c.text2, size = 18.dp) { menu = true }
                        DropdownMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                            shape = RoundedCornerShape(CardRadius),
                            containerColor = c.elevated,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                            border = BorderStroke(1.dp, c.line),
                        ) {
                            DropdownMenuItem(text = { Text("Remove from ${label.name}", style = Lumen.type.body, color = c.danger) }, onClick = {
                                menu = false
                                actions.remove(label.id, entry)
                            })
                        }
                    }
                    Icon(
                        LumenIcons.Handle,
                        contentDescription = "Drag to move",
                        tint = c.text2,
                        modifier = Modifier
                            .size(44.dp)
                            .padding(10.dp)
                            .pointerInput(entry.item.uri) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragging = entry.item.uri
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val from = order.indexOfFirst { it.item.uri == entry.item.uri }
                                        if (from >= 0) {
                                            val to = (from + (dragOffset / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
                                            if (to != from) {
                                                order = order.toMutableList().apply { add(to, removeAt(from)) }
                                                dragOffset -= (to - from) * rowPx
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        dragging = null
                                        dragOffset = 0f
                                        actions.reorder(label.id, order)
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        dragOffset = 0f
                                    },
                                )
                            },
                    )
                }
                if (index < order.lastIndex) HorizontalDivider(color = c.divider, thickness = 1.dp)
            }
        }
    }
}

/** "Add to …": every file in the library, by section, with a tick that adds or removes it at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Picker(label: LabelEntity, members: List<PlaylistEntry>, items: List<LibraryItem>, labels: List<LabelEntity>, actions: PlaylistActions) {
    val c = Lumen.colours
    var search by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(LibrarySource.ALL) }
    val memberUris = remember(members) { members.map { it.item.uri }.toSet() }
    val sunoId = remember(labels) { sunoLabelId(labels) }
    val shown = remember(items, search, source, labels) {
        filterItems(items.filter { inSource(it, source, sunoId) }, labels, search, null, byName = false)
    }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(LumenIcons.ChevronLeft, "Back to ${label.name}", c.text) { actions.openPicker(false) }
        Spacer(Modifier.width(4.dp))
        Text("Add to ${label.name}", style = Lumen.type.displayS, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextAction("Done") { actions.openPicker(false) }
    }
    Column(Modifier.padding(horizontal = Gutter)) {
        SearchField(value = search, placeholder = "Search everything", onChange = { search = it })
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LibrarySource.entries.forEach { s ->
                StatusChip(text = s.title, icon = s.icon, active = s == source, onClick = { source = s })
            }
        }
        Text("${memberUris.size} in ${label.name}. Tap to add or take out.", style = Lumen.type.bodyS, color = c.text3, modifier = Modifier.padding(vertical = 6.dp))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp)) {
        itemsIndexed(shown, key = { _, it -> it.uri }) { index, item ->
            val inList = item.uri in memberUris
            Row(
                Modifier.fillMaxWidth().height(60.dp).clickable { actions.toggleMember(label.id, item) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.media.title, style = Lumen.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(rowMeta(item, labels), style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (inList) c.accentFill else c.surface)
                        .border(1.dp, if (inList) c.accentFill else c.switchOffEdge, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (inList) Icon(LumenIcons.Check, contentDescription = "In ${label.name}", tint = c.onAccentFill, modifier = Modifier.size(16.dp))
                }
            }
            if (index < shown.lastIndex) HorizontalDivider(color = c.divider, thickness = 1.dp)
        }
    }
}
