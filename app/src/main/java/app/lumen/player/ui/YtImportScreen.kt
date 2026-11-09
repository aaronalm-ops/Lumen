package app.lumen.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.player.data.LabelEntity

class YtImportActions(
    val close: () -> Unit,
    val browse: () -> Unit,
    val showLink: () -> Unit,
    val openPlaylist: (app.lumen.player.remote.YoutubeApi.PlaylistSummary) -> Unit,
    val setLink: (String) -> Unit,
    val load: () -> Unit,
    val signIn: () -> Unit,
    val toggle: (String) -> Unit,
    val selectAll: (Boolean) -> Unit,
    val setTarget: (Long?) -> Unit,
    val setNewName: (String) -> Unit,
    val add: () -> Unit,
    val addAsOneLink: () -> Unit,
)

/**
 * 0.8.1: a YouTube or YouTube Music playlist (or album), song by song. Tick the songs wanted,
 * choose the Lumen playlist they go into (or a new one named after the YouTube playlist), and add
 * them in one go. Each song becomes its own row that plays in YouTube Music.
 */
@Composable
fun YtImportScreen(state: MainViewModel.YtImport, labels: List<LabelEntity>, actions: YtImportActions) {
    val c = Lumen.colours
    BackHandler { if (!state.adding) { if (state.tracks.isNotEmpty() && state.accountLoaded) actions.browse() else actions.close() } }
    Column(Modifier.fillMaxSize().background(c.base).pointerInput(Unit) {}.statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap(LumenIcons.Close, "Close", c.text, onClick = { if (!state.adding) actions.close() })
            Spacer(Modifier.width(4.dp))
            Text(
                state.title.ifBlank { "Songs from YouTube Music" },
                style = Lumen.type.displayS,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (state.loading || state.adding) ProgressLine(fraction = 1f, thickness = 1.dp)

        if (state.tracks.isEmpty()) {
            Row(Modifier.padding(horizontal = Gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("My playlists", active = state.browseAccount, onClick = { if (!state.signingIn) actions.browse() })
                StatusChip("Playlist link", active = !state.browseAccount, onClick = { if (!state.signingIn) actions.showLink() })
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = Gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        if (state.browseAccount) "Read your YouTube playlists, including private ones. Pick a playlist, then add all its songs or choose individual songs."
                        else "Paste a YouTube Music playlist or album link. You can also use links to public playlists you have saved.",
                        style = Lumen.type.bodyS, color = c.text2,
                    )
                }
                if (!state.browseAccount) item {
                    SearchField(value = state.linkText, placeholder = "https://music.youtube.com/playlist?list=\u2026", onChange = actions.setLink)
                    Spacer(Modifier.height(10.dp))
                    PrimaryPill("Show the songs", icon = LumenIcons.Music, enabled = state.linkText.isNotBlank() && !state.loading && !state.signingIn, onClick = actions.load)
                }
                if (state.loading) item { Text(if (state.browseAccount) "Reading your playlists" else "Reading all the songs", style = Lumen.type.body, color = c.text2) }
                state.error?.let { message -> item { Text(message, style = Lumen.type.bodyS, color = c.danger) } }
                if (state.needsSignIn || state.browseAccount) item {
                    PrimaryPill(
                        if (state.signingIn) "Connecting to Google" else if (state.accountLoaded && !state.needsSignIn) "Reconnect YouTube" else "Connect YouTube",
                        enabled = !state.signingIn && !state.loading, onClick = actions.signIn,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Read access only. Songs play in the official YouTube Music app.", style = Lumen.type.bodyS, color = c.text3)
                }
                if (state.browseAccount && state.accountLoaded && !state.loading && !state.needsSignIn) {
                    item {
                        SectionLabel("Your playlists", count = state.playlists.size.toString(), trailing = { TextAction("Refresh", onClick = actions.browse) })
                        Spacer(Modifier.height(6.dp))
                        Text("Shows playlists this YouTube account owns. For saved playlists, albums and mixes, try Playlist link. Some generated lists are unavailable through YouTube's API.", style = Lumen.type.bodyS, color = c.text3)
                    }
                    if (state.playlists.isEmpty()) item {
                        Text("No playlists found for this account. Reconnect with the account you use in YouTube Music, or try a playlist link.", style = Lumen.type.body, color = c.text2)
                    }
                    items(state.playlists, key = { it.id }) { playlist ->
                        ListCard {
                            Row(Modifier.fillMaxWidth().clickable { actions.openPlaylist(playlist) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(LumenIcons.Music, null, tint = c.accentInk, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.title, style = Lumen.type.bodyStrong, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${playlist.trackCount} songs" + if (playlist.privacy == "private") " \u00b7 Private" else "", style = Lumen.type.bodyS, color = c.text2)
                                }
                                Icon(LumenIcons.ChevronRight, "Open playlist", tint = c.text3, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                if (state.shared != null) item { TextAction("Add the whole playlist as one link instead", onClick = actions.addAsOneLink) }
            }
            return@Column
        }

        Column(Modifier.padding(horizontal = Gutter)) {
            TextAction("Choose another playlist", enabled = !state.adding, onClick = actions.browse)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${state.selected.size} of ${state.tracks.size} ticked",
                    style = Lumen.type.bodyS,
                    color = c.text2,
                    modifier = Modifier.weight(1f),
                )
                TextAction("Tick all", enabled = !state.adding, onClick = { actions.selectAll(true) })
                TextAction("Clear", enabled = state.selected.isNotEmpty() && !state.adding, onClick = { actions.selectAll(false) })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 8.dp)) {
            itemsIndexed(state.tracks, key = { _, t -> t.videoId }) { index, track ->
                val ticked = track.videoId in state.selected
                Row(
                    Modifier.fillMaxWidth().height(60.dp).toggleable(value = ticked, enabled = !state.adding, role = Role.Checkbox, onValueChange = { actions.toggle(track.videoId) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${index + 1}", style = Lumen.type.timeS, color = c.text3, modifier = Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = Lumen.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(track.artist.ifBlank { null }, track.durationMs.takeIf { it > 0 }?.let { formatTime(it) }).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (ticked) c.accentFill else c.surface)
                            .border(1.dp, if (ticked) c.accentFill else c.switchOffEdge, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (ticked) Icon(LumenIcons.Check, contentDescription = "Ticked", tint = c.onAccentFill, modifier = Modifier.size(16.dp))
                    }
                }
                if (index < state.tracks.lastIndex) HorizontalDivider(color = c.divider, thickness = 1.dp)
            }
        }

        // Where they go, and the one action.
        Column(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = Gutter, vertical = 10.dp)) {
            Text("ADD TO", style = Lumen.type.label, color = c.text2)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { StatusChip(text = "New playlist", icon = LumenIcons.Plus, active = state.targetLabelId == null, onClick = { if (!state.adding) actions.setTarget(null) }) }
                items(labels, key = { it.id }) { label ->
                    StatusChip(text = label.name, active = state.targetLabelId == label.id, onClick = { if (!state.adding) actions.setTarget(label.id) })
                }
            }
            if (state.targetLabelId == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.thumb)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (state.newName.isEmpty()) Text("New playlist name", style = Lumen.type.body, color = c.text3)
                    BasicTextField(
                        value = state.newName,
                        onValueChange = actions.setNewName,
                        singleLine = true,
                        enabled = !state.adding,
                        textStyle = Lumen.type.body.copy(color = c.text),
                        cursorBrush = SolidColor(c.accentInk),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            state.error?.let { Text(it, style = Lumen.type.bodyS, color = c.danger, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(8.dp))
            PrimaryPill(
                if (state.selected.isEmpty()) "Tick the songs you want" else "Add ${state.selected.size} song${if (state.selected.size == 1) "" else "s"}",
                icon = LumenIcons.Check,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selected.isNotEmpty() && !state.adding,
                onClick = actions.add,
            )
        }
    }
}
