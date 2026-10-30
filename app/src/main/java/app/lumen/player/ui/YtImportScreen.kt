package app.lumen.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun YtImportScreen(state: MainViewModel.YtImport, labels: List<LabelEntity>, actions: YtImportActions) {
    val c = Lumen.colours
    BackHandler(onBack = actions.close)
    Column(Modifier.fillMaxSize().background(c.base).pointerInput(Unit) {}.statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTap(LumenIcons.Close, "Close", c.text, onClick = actions.close)
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
            Column(Modifier.padding(horizontal = Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(4.dp))
                when {
                    state.loading -> Text("Reading the playlist", style = Lumen.type.body, color = c.text2)
                    state.needsSignIn -> EmptyState(
                        title = "Let Lumen read your playlists",
                        body = "Lumen reads the song titles, artists and lengths of your YouTube Music playlists, " +
                            "including private ones, with your Google account. It never changes anything there.",
                        action = "Connect YouTube",
                        onAction = actions.signIn,
                    )
                    else -> {
                        Text(
                            "Paste a link to a YouTube Music playlist or album. In YouTube Music: open the playlist, tap Share, then Copy link.",
                            style = Lumen.type.bodyS,
                            color = c.text2,
                        )
                        SearchField(value = state.linkText, placeholder = "https://music.youtube.com/playlist?list=…", onChange = actions.setLink)
                        PrimaryPill("Show the songs", icon = LumenIcons.Music, enabled = state.linkText.isNotBlank() && !state.loading, onClick = actions.load)
                        state.error?.let { Text(it, style = Lumen.type.bodyS, color = c.danger) }
                    }
                }
                if (state.shared != null) TextAction("Add the whole playlist as one link instead", onClick = actions.addAsOneLink)
            }
            return@Column
        }

        Column(Modifier.padding(horizontal = Gutter)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${state.selected.size} of ${state.tracks.size} ticked",
                    style = Lumen.type.bodyS,
                    color = c.text2,
                    modifier = Modifier.weight(1f),
                )
                TextAction("Tick all", onClick = { actions.selectAll(true) })
                TextAction("Clear", enabled = state.selected.isNotEmpty(), onClick = { actions.selectAll(false) })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 8.dp)) {
            itemsIndexed(state.tracks, key = { _, t -> t.videoId }) { index, track ->
                val ticked = track.videoId in state.selected
                Row(
                    Modifier.fillMaxWidth().height(60.dp).clickable { actions.toggle(track.videoId) },
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(text = "New playlist", icon = LumenIcons.Plus, active = state.targetLabelId == null, onClick = { actions.setTarget(null) })
                labels.forEach { label ->
                    StatusChip(text = label.name, active = state.targetLabelId == label.id, onClick = { actions.setTarget(label.id) })
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
