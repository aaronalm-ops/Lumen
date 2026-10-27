package app.lumen.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.player.data.FolderEntity
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.LabelRepository
import app.lumen.player.data.MediaRepository
import app.lumen.player.data.Settings
import app.lumen.player.data.isMediaStore
import app.lumen.player.remote.RemoteController
import app.lumen.player.remote.YtmLink
import app.lumen.player.remote.isYtm

/** 0.5.1: what the Library's section cards can ask for. */
class LibraryActions(
    val onSource: (LibrarySource) -> Unit,
    val onFolder: (String?) -> Unit,
    val onSubfolder: (String?) -> Unit,
    val onScanPhone: () -> Unit,
    val onAddFolder: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onChooseDriveFolder: () -> Unit,
    val onRescanDrive: () -> Unit,
    val onDriveSignIn: () -> Unit,
    val onOpenYouTubeMusic: () -> Unit,
    val onAllowYouTubeMusic: () -> Unit,
)

/**
 * Library, in sections (0.5.1): All, Phone, Drive, YouTube Music, Suno, as a row of chips under the
 * title. Each section has its own folders, files and a short card on how it fills; All is every
 * file with no folders. The file list is one lazy item per row so a whole phone of files composes
 * as it scrolls, not all at once.
 */
@Composable
fun LibraryScreen(
    items: List<LibraryItem>,
    folders: List<FolderEntity>,
    labels: List<LabelEntity>,
    player: PlayerState,
    settings: Settings,
    source: LibrarySource,
    scanning: Boolean,
    driveScanning: Boolean,
    mediaAccessDenied: Boolean,
    ytmAccess: Boolean,
    /** Drive rows that cannot be played right now (offline, no copy, not cached). */
    unreachable: Set<String>,
    /** Running "Keep offline" downloads, uri to fraction. */
    downloads: Map<String, Float>,
    search: String,
    sortByName: Boolean,
    folderFilter: String?,
    subfolder: String?,
    /** 0.6.0: lines said in transcribed recordings that match the search. */
    transcriptHits: List<TranscriptHit> = emptyList(),
    onHit: (TranscriptHit) -> Unit = {},
    onHitLongPress: (TranscriptHit) -> Unit = {},
    onSearch: (String) -> Unit,
    onToggleSort: () -> Unit,
    onItem: (LibraryItem, List<LibraryItem>) -> Unit,
    onItemLongPress: (LibraryItem) -> Unit,
    actions: LibraryActions,
) {
    val c = Lumen.colours
    val sunoId = remember(labels) { sunoLabelId(labels) }
    val counts = remember(items, labels) { sourceCounts(items, labels) }
    val inSection = remember(items, source, sunoId) { items.filter { inSource(it, source, sunoId) } }
    val visible = remember(inSection, search, sortByName, folderFilter, subfolder, labels) {
        val byFolder = filterItems(inSection, labels, search, folderFilter, sortByName)
        if (subfolder == null) byFolder else byFolder.filter { (driveSubfolder(it) ?: DRIVE_TOP_LEVEL) == subfolder }
    }
    val phoneFolders = remember(folders) { folders.filter { it.isPhone() } }
    val folderCounts = remember(items) { items.groupingBy { it.media.folderUri }.eachCount() }
    val driveFolders = remember(inSection, source) {
        if (source != LibrarySource.DRIVE) emptyList() else driveFolderRows(inSection)
    }
    val busy = when (source) {
        LibrarySource.DRIVE -> driveScanning
        LibrarySource.PHONE -> scanning
        LibrarySource.ALL, LibrarySource.SUNO -> scanning || driveScanning
        LibrarySource.YTM -> false
    }

    Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = Lumen.type.displayM, color = c.text)
            Spacer(Modifier.weight(1f))
            // The sort chip shows a setting, not a selection: a rest chip on light, silver on black.
            StatusChip(text = if (sortByName) "Name" else "Date", active = c.isDark, onClick = onToggleSort)
        }
        SourceRow(
            selected = source,
            counts = counts,
            sunoHue = labels.firstOrNull { it.name == LabelRepository.SUNO }?.hue,
            onSource = actions.onSource,
        )
        if (busy) ProgressLine(fraction = 1f, thickness = 1.dp) else Spacer(Modifier.height(1.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 8.dp, bottom = 24.dp),
        ) {
            // The section's own card: how it fills, its status and its actions.
            when (source) {
                LibrarySource.PHONE -> if (phoneFolders.isEmpty()) {
                    item {
                        EmptyState(
                            title = if (scanning) "Scanning your phone" else "Nothing from the phone yet",
                            body = "Lumen finds the audio and video already on this phone and keeps your place in every file.",
                            action = if (scanning) "Scanning" else "Scan my phone",
                            onAction = actions.onScanPhone,
                            actionEnabled = !scanning,
                            secondaryAction = "Add a folder",
                            onSecondaryAction = actions.onAddFolder,
                            notice = if (mediaAccessDenied) ({ PermissionNotice(onOpenSettings = actions.onOpenAppSettings) }) else null,
                        )
                    }
                    return@LazyColumn
                }
                LibrarySource.DRIVE -> if (!settings.driveConnected) {
                    item {
                        EmptyState(
                            title = "Your Drive, in Lumen",
                            body = "Choose one Google Drive folder, such as your sermons. Lumen lists everything inside it, " +
                                "subfolders included, and streams a file when you tap it. Lumen only reads your Drive.",
                            action = "Choose a Drive folder",
                            onAction = actions.onChooseDriveFolder,
                        )
                    }
                    return@LazyColumn
                } else {
                    item { DriveCard(settings, driveScanning, actions) }
                }
                LibrarySource.YTM -> if (inSection.isEmpty()) {
                    item {
                        EmptyState(
                            title = "YouTube Music, in Lumen",
                            body = "In YouTube Music, tap Share on a song or playlist and choose Lumen. It lands here, " +
                                "ready for labels and playlists, and plays in YouTube Music when you tap it.",
                            action = "Open YouTube Music",
                            onAction = actions.onOpenYouTubeMusic,
                            notice = if (!ytmAccess && RemoteController.CONTROL_ENABLED) ({ YtmAccessNotice(actions.onAllowYouTubeMusic) }) else null,
                        )
                    }
                    return@LazyColumn
                } else if (!ytmAccess && RemoteController.CONTROL_ENABLED) {
                    item { HintCard("Lumen can pause and skip YouTube Music once it may read its playback controls", "Allow", actions.onAllowYouTubeMusic) }
                }
                LibrarySource.SUNO -> if (inSection.isEmpty()) {
                    item {
                        EmptyState(
                            title = "Your Suno songs",
                            body = "Download your songs from Suno into a folder with Suno in its name, on the phone or " +
                                "inside your Drive folder. Lumen gathers them here and labels them Suno.",
                            action = "Scan my phone",
                            onAction = actions.onScanPhone,
                            actionEnabled = !scanning,
                        )
                    }
                    return@LazyColumn
                }
                LibrarySource.ALL -> if (items.isEmpty()) {
                    item {
                        EmptyState(
                            title = if (scanning) "Scanning your phone" else "Nothing here yet",
                            body = "Lumen finds the audio and video already on this phone and keeps your place in every file.",
                            action = if (scanning) "Scanning" else "Scan my phone",
                            onAction = actions.onScanPhone,
                            actionEnabled = !scanning,
                            secondaryAction = "Add a folder",
                            onSecondaryAction = actions.onAddFolder,
                            notice = if (mediaAccessDenied) ({ PermissionNotice(onOpenSettings = actions.onOpenAppSettings) }) else null,
                        )
                    }
                    return@LazyColumn
                }
            }

            item {
                Spacer(Modifier.height(if (source == LibrarySource.DRIVE || (source == LibrarySource.YTM && !ytmAccess && RemoteController.CONTROL_ENABLED)) 12.dp else 0.dp))
                SearchField(
                    value = search,
                    placeholder = if (source == LibrarySource.ALL) "Search titles, labels and what was said" else "Search ${source.title}",
                    onChange = onSearch,
                )
            }

            // 0.6.0: what was said. Tap plays from the line; hold opens the transcript at the search.
            val hits = if (search.trim().length >= 3) transcriptHits.filter { inSource(it.item, source, sunoId) } else emptyList()
            if (hits.isNotEmpty()) {
                item {
                    SectionLabel("Said in recordings", count = hits.size.toString(), modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                    ListCard {
                        hits.forEachIndexed { index, hit ->
                            HitRow(hit, search.trim(), divider = index < hits.lastIndex, onClick = { onHit(hit) }, onLongClick = { onHitLongPress(hit) })
                        }
                    }
                }
            }

            // Folders: the phone's buckets and picked folders, or the first level inside the Drive folder.
            if (source == LibrarySource.PHONE && phoneFolders.size > 1) {
                item {
                    SectionLabel("Folders", count = phoneFolders.size.toString(), modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                    ListCard {
                        phoneFolders.forEachIndexed { index, folder ->
                            FolderRow(
                                name = folder.name,
                                icon = if (folder.isMediaStore) LumenIcons.Phone else LumenIcons.Folder,
                                count = folderCounts[folder.uri] ?: 0,
                                selected = folderFilter == folder.uri,
                                divider = index < phoneFolders.lastIndex,
                                onClick = { actions.onFolder(if (folderFilter == folder.uri) null else folder.uri) },
                            )
                        }
                    }
                }
            }
            if (source == LibrarySource.DRIVE && driveFolders.size > 1) {
                item {
                    SectionLabel("Folders", count = driveFolders.size.toString(), modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                    ListCard {
                        driveFolders.forEachIndexed { index, row ->
                            FolderRow(
                                name = row.name,
                                icon = LumenIcons.Folder,
                                count = row.count,
                                selected = subfolder == row.key,
                                divider = index < driveFolders.lastIndex,
                                onClick = { actions.onSubfolder(if (subfolder == row.key) null else row.key) },
                            )
                        }
                    }
                }
            }

            item {
                val heading = when {
                    folderFilter != null -> folders.firstOrNull { it.uri == folderFilter }?.name ?: "Files"
                    subfolder != null -> driveFolders.firstOrNull { it.key == subfolder }?.name ?: "Files"
                    source == LibrarySource.YTM || source == LibrarySource.SUNO -> "Songs"
                    else -> "All files"
                }
                SectionLabel(
                    heading,
                    count = visible.size.toString(),
                    modifier = Modifier.padding(top = 16.dp, bottom = 10.dp),
                    trailing = {
                        if (folderFilter != null || subfolder != null) {
                            Text(
                                "ALL",
                                style = Lumen.type.label,
                                color = c.accentInk,
                                modifier = Modifier.clickable {
                                    actions.onFolder(null)
                                    actions.onSubfolder(null)
                                }.padding(8.dp),
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
                            when {
                                search.isNotBlank() -> "Nothing matches \"$search\""
                                busy -> "Listing"
                                source == LibrarySource.DRIVE -> "No audio or video in this Drive folder yet"
                                else -> "No audio or video here yet"
                            },
                            style = Lumen.type.bodyS,
                            color = c.text2,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            } else {
                itemsIndexed(visible, key = { _, item -> item.uri }) { index, item ->
                    val playing = player.hasItem && player.mediaId == item.uri
                    val speech = looksLikeSpeech(item, labels)
                    val time = if (playing) player.positionMs else item.positionMs
                    val offline = item.uri in unreachable
                    val youTubeMusic = item.media.isYtm
                    Box(Modifier.fillMaxWidth().cardSegment(first = index == 0, last = index == visible.lastIndex)) {
                        MediaRow(
                            title = item.media.title,
                            meta = rowMeta(item, labels),
                            // A Drive row with no duration yet shows "--:--" until first play.
                            time = when {
                                // YouTube Music keeps no position and Lumen knows no duration.
                                youTubeMusic -> ""
                                offline -> "Offline"
                                time > 0 -> formatTime(time)
                                item.media.durationMs > 0 -> formatTime(item.media.durationMs)
                                else -> "--:--"
                            },
                            icon = when {
                                item.media.isVideo -> LumenIcons.Video
                                speech -> LumenIcons.Speech
                                else -> LumenIcons.Music
                            },
                            playing = playing,
                            copies = item.copies,
                            hues = labels.filter { it.id in item.labelIds }.map { it.hue },
                            divider = index < visible.lastIndex,
                            dimmed = offline,
                            progress = downloads[item.uri],
                            // In the YouTube Music section the chip would repeat the section's name.
                            badge = if (youTubeMusic && source != LibrarySource.YTM) YtmLink.SOURCE_LABEL else null,
                            // Offline with nothing cached: the tap does nothing and nothing pops up.
                            onClick = { if (!offline) onItem(item, visible) },
                            onLongClick = { onItemLongPress(item) },
                        )
                    }
                }
            }
        }
    }
}

/** A Drive row has no duration until first play: a large audio file (over 40 MB) reads as a sermon. */
fun looksLikeSpeech(item: LibraryItem, labels: List<LabelEntity>): Boolean =
    item.media.durationMs > MediaRepository.SPEECH_THRESHOLD_MS ||
        (item.media.durationMs <= 0L && !item.media.isVideo && item.media.sizeBytes > SPEECH_SIZE_GUESS) ||
        labels.any { it.id in item.labelIds && it.name == LabelRepository.SERMONS }

const val SPEECH_SIZE_GUESS = 40L * 1024 * 1024

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
    // 0.4.0: the WhatsApp voice notes folder always lists newest first, whatever the sort chip says.
    val newestFirst = !byName || folder == MediaRepository.VOICE_NOTES_FOLDER
    return if (newestFirst) filtered.sortedByDescending { it.media.dateAdded } else filtered.sortedBy { it.media.title.lowercase() }
}

private data class DriveFolderRow(val key: String, val name: String, val count: Int)

/** The Drive folder's first level, by name, then "Top of the folder" for loose files if any. */
private fun driveFolderRows(items: List<LibraryItem>): List<DriveFolderRow> {
    val groups = items.groupingBy { driveSubfolder(it) }.eachCount()
    val named = groups.filterKeys { it != null }
        .map { (name, n) -> DriveFolderRow(key = name!!, name = name, count = n) }
        .sortedBy { it.name.lowercase() }
    val loose = groups[null] ?: 0
    return if (loose > 0 && named.isNotEmpty()) named + DriveFolderRow(DRIVE_TOP_LEVEL, "Top of the folder", loose) else named
}

/** The chips under the title: one per section, each with its count. */
@Composable
private fun SourceRow(selected: LibrarySource, counts: Map<LibrarySource, Int>, sunoHue: Int?, onSource: (LibrarySource) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Gutter - 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibrarySource.entries.forEach { s ->
            SourceChip(
                text = s.title,
                count = counts[s] ?: 0,
                icon = if (s == LibrarySource.SUNO) null else s.icon,
                dotHue = if (s == LibrarySource.SUNO) (sunoHue ?: LabelRepository.HUE_TEAL) else null,
                selected = s == selected,
                onClick = { onSource(s) },
            )
        }
    }
}

/**
 * A section chip: the selected one takes the label chip's selected look (charcoal with cyan on
 * light, cyan with black on dark); the rest sit as quiet outlines. The count is mono, one step down.
 */
@Composable
private fun SourceChip(text: String, count: Int, icon: ImageVector?, dotHue: Int?, selected: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    val shape = RoundedCornerShape(50)
    val ink = if (selected) c.onChipSelected else c.text
    Box(
        modifier = Modifier.height(TouchTarget).clip(shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(shape)
                .background(if (selected) c.chipSelected else if (c.isDark) Color.Transparent else c.surface)
                .border(1.dp, if (selected) c.chipSelectedEdge else c.line, shape)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            if (dotHue != null) HueDot(dotHue, colour = hueColour(dotHue, onDarkGround = if (selected) !c.isDark else c.isDark))
            Text(text.uppercase(), style = Lumen.type.label, color = ink, maxLines = 1)
            if (count > 0) Text(formatCount(count), style = Lumen.type.label, color = if (selected) ink.copy(alpha = 0.7f) else c.text3, maxLines = 1)
        }
    }
}

/** The Drive section's card: the folder, how many files and when, the account, and what to do. */
@Composable
private fun DriveCard(settings: Settings, scanning: Boolean, actions: LibraryActions) {
    val c = Lumen.colours
    ListCard {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(c.thumb),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(LumenIcons.Cloud, contentDescription = null, tint = c.accentInk, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        settings.driveFolderName.ifBlank { "Drive folder" },
                        style = Lumen.type.bodyStrong,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            scanning -> "Listing your folder and its subfolders"
                            settings.driveScanAt > 0L -> "${formatCount(settings.driveScanCount)} files · listed ${relativeTime(settings.driveScanAt)}"
                            else -> "Not listed yet"
                        },
                        style = Lumen.type.bodyS,
                        color = c.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (settings.driveAccount.isNotBlank()) {
                        Text(settings.driveAccount, style = Lumen.type.bodyS, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val notice = when {
                settings.driveNeedsReconnect -> "Lumen can see the folder's name but not its files. Reconnect to give Lumen read access."
                settings.driveNeedsSignIn -> "Drive refused the last request. Sign in again."
                else -> "Tap a file to stream it. Hold it down for Keep offline, so it plays without a connection."
            }
            Text(
                notice,
                style = Lumen.type.bodyS,
                color = if (settings.driveNeedsReconnect || settings.driveNeedsSignIn) c.text else c.text2,
                modifier = Modifier.padding(end = 10.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (settings.driveNeedsReconnect || settings.driveNeedsSignIn) {
                    TextAction("Reconnect", onClick = actions.onDriveSignIn)
                }
                TextAction("Change folder", onClick = actions.onChooseDriveFolder)
                TextAction(if (scanning) "Listing" else "Rescan", enabled = !scanning, onClick = actions.onRescanDrive)
            }
        }
    }
}

/** One quiet line with one action, for a section that works but could do more. */
@Composable
private fun HintCard(text: String, action: String, onAction: () -> Unit) {
    val c = Lumen.colours
    ListCard {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = Lumen.type.bodyS, color = c.text2, modifier = Modifier.weight(1f))
            TextAction(action, onClick = onAction)
        }
    }
}

@Composable
private fun YtmAccessNotice(onAllow: () -> Unit) {
    val c = Lumen.colours
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Let Lumen pause and skip YouTube Music",
            style = Lumen.type.bodyS,
            color = c.text2,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextAction("Allow", onClick = onAllow)
    }
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
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(c.thumb),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) c.accentInk else c.text2, modifier = Modifier.size(20.dp))
            }
            Text(
                name,
                style = Lumen.type.bodyStrong,
                color = if (selected) c.accentInk else c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatCount(count), style = Lumen.type.timeS, color = c.text2)
            Spacer(Modifier.width(2.dp))
            Icon(LumenIcons.ChevronRight, contentDescription = null, tint = c.text3, modifier = Modifier.size(20.dp))
        }
        if (divider) HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
    }
}

/** 0.6.0: one transcript line found by the search: the recording, the line and its time. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HitRow(hit: TranscriptHit, query: String, divider: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Lumen.colours
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(hit.item.media.title, style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(highlight(hit.text, query, c.accentInk), style = Lumen.type.body, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Text(formatTime(hit.ms), style = Lumen.type.timeS, color = c.accentInk, modifier = Modifier.padding(top = 2.dp))
        }
        if (divider) HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
    }
}
