package app.lumen.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.player.data.drive.DriveNode
import app.lumen.player.data.drive.DriveNodeKind
import app.lumen.player.data.drive.DriveRepository

/**
 * What the Drive folder browser shows. [path] runs from the top level (empty) to the folder on
 * screen; [counts] maps a folder id to its count line ("12 audio"), "" when there is nothing to
 * show, and has no entry while the count is still unknown. Counts are kept while browsing.
 */
data class DriveBrowserState(
    val path: List<DriveNode> = emptyList(),
    val entries: List<DriveNode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val counts: Map<String, String> = emptyMap(),
) {
    val current: DriveNode? get() = path.lastOrNull()
}

class DriveBrowserActions(
    /** Up one level; at the top level, back to Settings. */
    val back: () -> Unit,
    val open: (DriveNode) -> Unit,
    /** A breadcrumb: -1 is the top level, otherwise the index into the path. */
    val jump: (Int) -> Unit,
    val retry: () -> Unit,
    /** Asked once per folder row as it comes on screen. */
    val count: (DriveNode) -> Unit,
    val use: () -> Unit,
)

/**
 * Lumen's own Drive folder browser (0.3.3), in place of Google's picker. Top level: My Drive,
 * Shared with me and each shared drive; tapping a folder goes in. A breadcrumb row under the
 * title, a 1 dp line while loading (never a spinner), one line with Retry on an error, and a
 * bottom bar with the current folder's name and "Use this folder" (disabled on the top level and
 * on the three top-level entries themselves).
 */
@Composable
fun DriveBrowserScreen(state: DriveBrowserState, actions: DriveBrowserActions) {
    val c = Lumen.colours
    val current = state.current
    Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTap(LumenIcons.ChevronLeft, "Back", c.text, onClick = actions.back)
            Spacer(Modifier.width(4.dp))
            Text("Choose folder", style = Lumen.type.displayM, color = c.text)
        }
        Breadcrumbs(path = state.path, onJump = actions.jump)
        if (state.loading) ProgressLine(fraction = 1f, thickness = 1.dp) else Spacer(Modifier.height(1.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 12.dp, bottom = 16.dp),
        ) {
            val error = state.error
            when {
                error != null -> item {
                    ListCard {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(error, style = Lumen.type.bodyS, color = c.text2, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextAction("Retry", onClick = actions.retry)
                        }
                    }
                }
                !state.loading && state.entries.isEmpty() -> item {
                    ListCard {
                        Text(
                            if (current?.kind == DriveNodeKind.SharedWithMe) "No folders are shared with you" else "No folders in here",
                            style = Lumen.type.bodyS,
                            color = c.text2,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
                else -> itemsIndexed(state.entries, key = { _, node -> node.kind.name + ":" + node.id }) { index, node ->
                    if (node.selectable) LaunchedEffect(node.id) { actions.count(node) }
                    Box(Modifier.fillMaxWidth().cardSegment(first = index == 0, last = index == state.entries.lastIndex)) {
                        BrowserRow(
                            node = node,
                            detail = if (node.selectable) state.counts[node.id] else virtualDetail(node),
                            divider = index < state.entries.lastIndex,
                            onClick = { actions.open(node) },
                        )
                    }
                }
            }
        }

        // Bottom bar: the folder on screen and the one primary action.
        Column(Modifier.fillMaxWidth().background(if (c.isDark) c.base else c.surface)) {
            HorizontalDivider(color = c.line, thickness = 1.dp)
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Gutter, vertical = 12.dp)) {
                Text(
                    current?.name ?: "Google Drive",
                    style = Lumen.type.bodyStrong,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (current?.selectable == true) {
                        "Lumen plays the audio and video here and in subfolders up to ${DriveRepository.MAX_DEPTH} levels down"
                    } else {
                        "Open a folder to use it"
                    },
                    style = Lumen.type.bodyS,
                    color = c.text2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryPill("Use this folder", enabled = current?.selectable == true, onClick = actions.use)
            }
        }
    }
}

/** "DRIVE > My Drive > Sermons": every crumb but the last goes back to that level. */
@Composable
private fun Breadcrumbs(path: List<DriveNode>, onJump: (Int) -> Unit) {
    val c = Lumen.colours
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            // Reverse scrolling rests at the end, so the deepest crumb stays in view as the path grows.
            .horizontalScroll(scroll, reverseScrolling = true)
            .padding(horizontal = Gutter - 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Crumb("Drive", last = path.isEmpty()) { onJump(-1) }
        path.forEachIndexed { index, node ->
            Icon(LumenIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(14.dp))
            Crumb(node.name, last = index == path.lastIndex) { onJump(index) }
        }
    }
}

@Composable
private fun Crumb(text: String, last: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    Box(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !last, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (last) Lumen.type.bodyStrong else Lumen.type.bodyS,
            color = if (last) c.text else c.accentInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // A long folder name is cut at 180 dp so the trail stays readable.
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}

private fun virtualDetail(node: DriveNode): String = when (node.kind) {
    DriveNodeKind.MyDrive -> "Your own files"
    DriveNodeKind.SharedWithMe -> "Folders other people shared with you"
    DriveNodeKind.SharedDrive -> "Shared drive"
    DriveNodeKind.Folder -> ""
}

private fun iconFor(node: DriveNode): ImageVector = when (node.kind) {
    DriveNodeKind.MyDrive, DriveNodeKind.SharedDrive -> LumenIcons.Cloud
    DriveNodeKind.SharedWithMe, DriveNodeKind.Folder -> LumenIcons.Folder
}

/** 64 dp row: 36 dp icon tile, folder name, the count (or a short line for a top-level entry), chevron. */
@Composable
private fun BrowserRow(node: DriveNode, detail: String?, divider: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(c.thumb),
                contentAlignment = Alignment.Center,
            ) {
                Icon(iconFor(node), contentDescription = null, tint = c.text2, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(node.name.ifBlank { "Untitled folder" }, style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrBlank()) {
                    Text(
                        detail,
                        style = if (node.selectable) Lumen.type.timeS else Lumen.type.bodyS,
                        color = c.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(LumenIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(20.dp))
        }
        if (divider) HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
    }
}
