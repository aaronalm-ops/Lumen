package app.lumen.player.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.player.data.FolderEntity
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.MediaRepository
import app.lumen.player.data.isMediaStore

/**
 * Library, light: title, DATE or NAME sort, search, folders with counts, all files. Folders mix
 * the phone's MediaStore buckets (phone icon) with picked folders (folder icon). The file list is
 * one lazy item per row so a whole phone of files composes as it scrolls, not all at once.
 */
@Composable
fun LibraryScreen(
    items: List<LibraryItem>,
    folders: List<FolderEntity>,
    labels: List<LabelEntity>,
    player: PlayerState,
    scanning: Boolean,
    mediaAccessDenied: Boolean,
    search: String,
    sortByName: Boolean,
    folderFilter: String?,
    onSearch: (String) -> Unit,
    onToggleSort: () -> Unit,
    onFolder: (String?) -> Unit,
    onItem: (LibraryItem, List<LibraryItem>) -> Unit,
    onItemLongPress: (LibraryItem) -> Unit,
    onScanPhone: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val c = Lumen.colours
    val visible = remember(items, search, sortByName, folderFilter, labels) {
        filterItems(items, labels, search, folderFilter, sortByName)
    }
    val counts = remember(items) { items.groupingBy { it.media.folderUri }.eachCount() }

    Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = Lumen.type.displayM, color = c.text)
            Spacer(Modifier.weight(1f))
            StatusChip(text = if (sortByName) "Name" else "Date", active = true, onClick = onToggleSort)
        }
        if (scanning) ProgressLine(fraction = 1f, thickness = 1.dp)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 24.dp),
        ) {
            item {
                SearchField(value = search, placeholder = "Search titles, folders and labels", onChange = onSearch)
            }
            if (folders.isEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    EmptyState(
                        title = if (scanning) "Scanning your phone" else "Nothing here yet",
                        body = "Lumen finds the audio and video already on this phone and keeps your place in every file.",
                        action = if (scanning) "Scanning" else "Scan my phone",
                        onAction = onScanPhone,
                        actionEnabled = !scanning,
                        secondaryAction = "Add a folder",
                        onSecondaryAction = onAddFolder,
                        notice = if (mediaAccessDenied) ({ PermissionNotice(onOpenSettings = onOpenAppSettings) }) else null,
                    )
                }
            } else {
                item {
                    SectionLabel("Folders", count = folders.size.toString(), modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                    ListCard {
                        folders.forEachIndexed { index, folder ->
                            FolderRow(
                                name = folder.name,
                                icon = if (folder.isMediaStore) LumenIcons.Phone else LumenIcons.Folder,
                                count = counts[folder.uri] ?: 0,
                                selected = folderFilter == folder.uri,
                                divider = index < folders.lastIndex,
                                onClick = { onFolder(if (folderFilter == folder.uri) null else folder.uri) },
                            )
                        }
                    }
                }
                item {
                    val heading = folderFilter?.let { f -> folders.firstOrNull { it.uri == f }?.name } ?: "All files"
                    SectionLabel(
                        heading,
                        count = visible.size.toString(),
                        modifier = Modifier.padding(top = 16.dp, bottom = 10.dp),
                        trailing = {
                            if (folderFilter != null) {
                                Text(
                                    "ALL",
                                    style = Lumen.type.label,
                                    color = c.accent,
                                    modifier = Modifier.clickable { onFolder(null) }.padding(8.dp),
                                )
                            } else {
                                Text(if (sortByName) "NAME" else "DATE ADDED", style = Lumen.type.label, color = c.text3)
                            }
                        },
                    )
                }
                if (visible.isEmpty()) {
                    item {
                        ListCard {
                            Text(
                                if (search.isNotBlank()) "Nothing matches \"$search\"" else if (scanning) "Scanning" else "No audio or video in this folder yet",
                                style = Lumen.type.bodyS,
                                color = c.text2,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                } else {
                    itemsIndexed(visible, key = { _, item -> item.uri }) { index, item ->
                        val playing = player.hasItem && player.mediaId == item.uri
                        val speech = item.media.durationMs > MediaRepository.SPEECH_THRESHOLD_MS ||
                            labels.any { it.id in item.labelIds && it.name == "Sermons" }
                        val time = if (playing) player.positionMs else item.positionMs
                        Box(Modifier.fillMaxWidth().cardSegment(first = index == 0, last = index == visible.lastIndex)) {
                            MediaRow(
                                title = item.media.title,
                                meta = rowMeta(item, labels),
                                time = formatTime(if (time > 0) time else item.media.durationMs),
                                icon = when {
                                    item.media.isVideo -> LumenIcons.Video
                                    speech -> LumenIcons.Speech
                                    else -> LumenIcons.Music
                                },
                                playing = playing,
                                copies = item.copies,
                                hues = labels.filter { it.id in item.labelIds }.map { it.hue },
                                divider = index < visible.lastIndex,
                                onClick = { onItem(item, visible) },
                                onLongClick = { onItemLongPress(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

fun filterItems(items: List<LibraryItem>, labels: List<LabelEntity>, search: String, folder: String?, byName: Boolean): List<LibraryItem> {
    val q = search.trim().lowercase()
    val labelNames = labels.associate { it.id to it.name.lowercase() }
    val filtered = items.filter { item ->
        (folder == null || item.media.folderUri == folder) && (
            q.isEmpty() ||
                item.media.title.lowercase().contains(q) ||
                item.media.artist.lowercase().contains(q) ||
                item.folderName.lowercase().contains(q) ||
                item.media.relPath.lowercase().contains(q) ||
                item.labelIds.any { labelNames[it]?.contains(q) == true }
            )
    }
    return if (byName) filtered.sortedBy { it.media.title.lowercase() } else filtered.sortedByDescending { it.media.dateAdded }
}

@Composable
private fun FolderRow(name: String, icon: ImageVector, count: Int, selected: Boolean, divider: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(if (c.isDark) c.elevated else c.base),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) c.accent else c.text2, modifier = Modifier.size(20.dp))
            }
            Text(
                name,
                style = Lumen.type.bodyStrong,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatCount(count), style = Lumen.type.timeS, color = c.text2)
            Spacer(Modifier.width(2.dp))
            Icon(LumenIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(20.dp))
        }
        if (divider) HorizontalDivider(color = c.line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
    }
}
