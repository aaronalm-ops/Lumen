package app.lumen.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.NotesEntity
import app.lumen.player.notes.NotesRepository
import app.lumen.player.remote.isYtm

/**
 * 0.8.3: Sermons, a tab of their own. Everything that plays as a sermon (over 20 minutes, a large
 * Drive recording not yet played, or in the Sermons playlist), in three parts:
 * - Carry on: started and not finished, most recent first;
 * - With notes: the ones Gemini has written up, each with a Notes button;
 * - All sermons: newest first, with the Unplayed filter.
 * The search finds titles, and from three letters what was said in any transcript.
 */
@Composable
fun SermonsScreen(
    items: List<LibraryItem>,
    labels: List<LabelEntity>,
    notes: Map<String, NotesEntity>,
    player: PlayerState,
    search: String,
    unplayedOnly: Boolean,
    hits: List<TranscriptHit>,
    unreachable: Set<String>,
    onSearch: (String) -> Unit,
    onToggleUnplayed: () -> Unit,
    onPlay: (LibraryItem, List<LibraryItem>) -> Unit,
    onLongPress: (LibraryItem) -> Unit,
    onNotes: (LibraryItem, String) -> Unit,
    onHit: (TranscriptHit) -> Unit,
) {
    val c = Lumen.colours
    val sermons = remember(items, labels) {
        items.filter { !it.media.isYtm && looksLikeSpeech(it, labels) }.sortedByDescending { it.media.dateAdded }
    }
    val q = search.trim().lowercase()
    val matching = remember(sermons, q) {
        if (q.isEmpty()) sermons else sermons.filter { it.media.title.lowercase().contains(q) || it.media.artist.lowercase().contains(q) || it.folderName.lowercase().contains(q) }
    }
    val carryOn = remember(matching) { matching.filter { it.listened != null }.sortedByDescending { it.lastPlayedAt }.take(5) }
    val withNotes = remember(matching, notes) {
        matching.filter { notes[it.uri]?.status == NotesRepository.STATUS_DONE }
            .sortedByDescending { notes[it.uri]?.updatedAt ?: 0L }
    }
    val writing = remember(matching, notes) { matching.filter { notes[it.uri]?.status == NotesRepository.STATUS_WORKING } }
    val all = remember(matching, unplayedOnly) { if (unplayedOnly) matching.filter { !it.finished } else matching }
    val sermonHits = remember(hits, sermons) {
        val uris = sermons.map { it.uri }.toSet()
        hits.filter { it.item.uri in uris }
    }

    Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sermons", style = Lumen.type.displayM, color = c.text)
            Spacer(Modifier.weight(1f))
            StatusChip(text = "Unplayed", active = unplayedOnly, onClick = onToggleUnplayed)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 24.dp),
        ) {
            item {
                SearchField(value = search, placeholder = "Search sermons and what was said", onChange = onSearch)
            }
            if (sermons.isEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    ListCard {
                        Column(Modifier.padding(Gutter)) {
                            Text("No sermons yet", style = Lumen.type.displayS, color = c.text)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Recordings over 20 minutes show here, and anything you put in the Sermons playlist. " +
                                    "Connect your Drive folder or scan the phone from Library.",
                                style = Lumen.type.bodyS,
                                color = c.text2,
                            )
                        }
                    }
                }
                return@LazyColumn
            }
            if (sermonHits.isNotEmpty()) {
                section("Said in sermons", sermonHits.size) {
                    sermonHits.forEachIndexed { index, hit ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onHit(hit) }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(hit.item.media.title, style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(highlight(hit.text, search.trim(), c.accentInk), style = Lumen.type.body, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(formatTime(hit.ms), style = Lumen.type.timeS, color = c.accentInk, modifier = Modifier.padding(top = 2.dp))
                                Text(
                                    "Notes",
                                    style = Lumen.type.labelS,
                                    color = c.accentInk,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onNotes(hit.item, search.trim()) }.padding(6.dp),
                                )
                            }
                        }
                        if (index < sermonHits.lastIndex) HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
            if (carryOn.isNotEmpty() && !unplayedOnly) {
                section("Carry on", carryOn.size) {
                    carryOn.forEachIndexed { i, item ->
                        SermonRow(item, labels, notes[item.uri], player, item.uri in unreachable, i < carryOn.lastIndex,
                            onPlay = { onPlay(item, listOf(item)) }, onLongPress = { onLongPress(item) }, onNotes = { onNotes(item, "") })
                    }
                }
            }
            if (withNotes.isNotEmpty() || writing.isNotEmpty()) {
                section("With notes", withNotes.size) {
                    (writing + withNotes).forEachIndexed { i, item ->
                        SermonRow(item, labels, notes[item.uri], player, item.uri in unreachable, i < writing.size + withNotes.size - 1,
                            onPlay = { onPlay(item, listOf(item)) }, onLongPress = { onLongPress(item) }, onNotes = { onNotes(item, "") })
                    }
                }
            }
            section(if (unplayedOnly) "Unplayed" else "All sermons", all.size) {
                if (all.isEmpty()) {
                    Text(
                        if (q.isNotEmpty()) "No sermon titles match \"$search\"" else "Every sermon has been played",
                        style = Lumen.type.bodyS,
                        color = c.text2,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                all.forEachIndexed { i, item ->
                    SermonRow(item, labels, notes[item.uri], player, item.uri in unreachable, i < all.lastIndex,
                        onPlay = { onPlay(item, all) }, onLongPress = { onLongPress(item) }, onNotes = { onNotes(item, "") })
                }
            }
        }
    }
}

/** A labelled card section inside the list. */
private fun LazyListScope.section(title: String, count: Int, content: @Composable () -> Unit) {
    item {
        SectionLabel(title, count = count.toString(), modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
        ListCard { content() }
    }
}

/**
 * One sermon: title, where it is from and how long, how far through (a bar) or a tick, and a
 * Notes button when notes are written (Writing while they are being written).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SermonRow(
    item: LibraryItem,
    labels: List<LabelEntity>,
    notes: NotesEntity?,
    player: PlayerState,
    offline: Boolean,
    divider: Boolean,
    onPlay: () -> Unit,
    onLongPress: () -> Unit,
    onNotes: () -> Unit,
) {
    val c = Lumen.colours
    val playing = player.hasItem && player.mediaId == item.uri
    val done = notes?.status == NotesRepository.STATUS_DONE
    val writing = notes?.status == NotesRepository.STATUS_WORKING
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = RowHeight)
                .combinedClickable(enabled = !offline, onClick = onPlay, onLongClick = onLongPress)
                .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(c.thumb, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Icon(
                    if (item.finished) LumenIcons.Check else LumenIcons.Speech,
                    contentDescription = if (item.finished) "Played" else null,
                    tint = if (item.finished) c.accentInk else c.text2,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing) {
                        Box(Modifier.size(6.dp).background(c.accentInk, RoundedCornerShape(50)))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(item.media.title, style = Lumen.type.bodyStrong, color = if (offline) c.text3 else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val meta = listOfNotNull(
                    item.folderName.ifBlank { null },
                    when {
                        offline -> "Offline"
                        item.finished -> "Played"
                        item.listened != null && item.durationMs > 0 -> "${((item.durationMs - item.positionMs) / 60_000L).coerceAtLeast(1)} min left"
                        item.durationMs > 0 -> formatTime(item.durationMs)
                        else -> null
                    },
                ).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.listened?.let {
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(fraction = it, thickness = 2.dp, modifier = Modifier.fillMaxWidth(0.6f))
                }
            }
            if (done || writing) {
                Spacer(Modifier.width(8.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, if (done) c.accentInk else c.line, RoundedCornerShape(50))
                        .clickable(onClick = onNotes)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(LumenIcons.Notes, contentDescription = null, tint = if (done) c.accentInk else c.text2, modifier = Modifier.size(14.dp))
                    Text(if (done) "NOTES" else "WRITING", style = Lumen.type.labelS, color = if (done) c.accentInk else c.text2)
                }
            }
        }
        if (divider) HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
    }
}
