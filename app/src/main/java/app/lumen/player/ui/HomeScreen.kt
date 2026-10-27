package app.lumen.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.MediaRepository
import app.lumen.player.update.UpdateInfo

/** Home, light by day: wordmark, Continue card, label chips, Recent. One primary action. */
@Composable
fun HomeScreen(
    continueItem: LibraryItem?,
    /** Whether the Continue card's item takes the speech layout (override, Sermons label, 20 minutes). */
    continueIsSpeech: Boolean,
    player: PlayerState,
    labels: List<LabelEntity>,
    recent: List<LibraryItem>,
    hasLibrary: Boolean,
    scanning: Boolean,
    mediaAccessDenied: Boolean,
    update: UpdateInfo?,
    onSettings: () -> Unit,
    onResume: () -> Unit,
    onLabel: (LabelEntity) -> Unit,
    onLabelLongPress: (LabelEntity) -> Unit,
    onItem: (LibraryItem) -> Unit,
    onItemLongPress: (LibraryItem) -> Unit,
    onUpdate: () -> Unit,
    onScanPhone: () -> Unit,
    onAddFolder: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val c = Lumen.colours
    Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Lumen", style = Lumen.type.wordmark, color = c.text)
            Spacer(Modifier.weight(1f))
            IconTap(LumenIcons.Gear, "Settings", c.text, onClick = onSettings)
        }
        if (scanning) ProgressLine(fraction = 1f, thickness = 1.dp)
        if (update != null) UpdateRow(update.tag, onUpdate)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = 24.dp),
        ) {
            item {
                SectionLabel("Continue", modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                if (continueItem == null && !player.hasItem) {
                    if (hasLibrary) {
                        EmptyState(
                            title = "Nothing played yet",
                            body = "Pick a sermon or a song in the Library and it will be waiting here next time.",
                            action = "Open Library",
                            onAction = onOpenLibrary,
                        )
                    } else {
                        EmptyState(
                            title = if (scanning) "Scanning your phone" else "Scan your phone to begin",
                            body = "Lumen finds the sermons, music and video already on this phone. Nothing is uploaded.",
                            action = if (scanning) "Scanning" else "Scan my phone",
                            onAction = onScanPhone,
                            actionEnabled = !scanning,
                            secondaryAction = "Add a folder",
                            onSecondaryAction = onAddFolder,
                            notice = if (mediaAccessDenied) ({ PermissionNotice(onOpenSettings = onOpenAppSettings) }) else null,
                        )
                    }
                } else {
                    ContinueCard(continueItem, continueIsSpeech, player, labels, onResume)
                }
            }
            if (labels.isNotEmpty()) {
                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(labels, key = { it.id }) { label ->
                            LabelChip(
                                name = label.name,
                                hue = label.hue,
                                onClick = { onLabel(label) },
                                onLongClick = { onLabelLongPress(label) },
                            )
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    SectionLabel("Recent", count = recent.size.toString(), modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                }
                item {
                    ListCard {
                        recent.forEachIndexed { index, item ->
                            val playing = player.hasItem && player.mediaId == item.uri
                            val speech = item.media.durationMs > MediaRepository.SPEECH_THRESHOLD_MS ||
                                labels.any { it.id in item.labelIds && it.name == "Sermons" }
                            val time = if (playing) player.positionMs else item.positionMs
                            MediaRow(
                                title = item.media.title,
                                meta = rowMeta(item, labels),
                                time = when {
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
                                divider = index < recent.lastIndex,
                                onClick = { onItem(item) },
                                onLongClick = { onItemLongPress(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Sermon · 1:09:32 · Phone" style meta line. */
fun rowMeta(item: LibraryItem, labels: List<LabelEntity>): String {
    val kind = when {
        item.media.isVideo -> "Video"
        MediaRepository.isVoiceNote(item.media) -> "Voice note"
        item.media.durationMs > MediaRepository.SPEECH_THRESHOLD_MS -> "Sermon"
        else -> "Music"
    }
    val names = labels.filter { it.id in item.labelIds }.map { it.name }
    val parts = ArrayList<String>()
    parts += kind
    if (item.media.artist.isNotBlank()) parts += item.media.artist
    else if (names.isNotEmpty()) parts += names.first()
    if (item.media.durationMs > 0) parts += formatTime(item.media.durationMs)
    if (item.folderName.isNotBlank()) parts += item.folderName
    return parts.joinToString(" · ")
}

@Composable
private fun ContinueCard(item: LibraryItem?, speech: Boolean, player: PlayerState, labels: List<LabelEntity>, onResume: () -> Unit) {
    val c = Lumen.colours
    // The ViewModel hands over the player's own item (or null when it is not in the library)
    // whenever the player holds one, so a loaded player always means live figures here. The
    // uri check alone would miss a copy of the same file under another folder.
    val live = player.hasItem
    val title = if (live) player.title else item?.media?.title.orEmpty()
    val who = if (live && player.artist.isNotBlank()) player.artist else item?.media?.artist.orEmpty()
    val position = if (live) player.positionMs else item?.positionMs ?: 0L
    val duration = if (live && player.durationMs > 0) player.durationMs else item?.media?.durationMs ?: 0L
    val labelName = item?.let { i -> labels.firstOrNull { it.id in i.labelIds }?.name }
    val eyebrow = listOfNotNull(labelName, minutesLeft(position, duration).takeIf { it.isNotBlank() }).joinToString(" · ")
    ListCard(radius = HeroRadius) {
        Column(Modifier.padding(Gutter)) {
            if (eyebrow.isNotBlank()) {
                Text(eyebrow.uppercase(), style = Lumen.type.labelS, color = c.text2)
                Spacer(Modifier.height(10.dp))
            }
            Text(title, style = Lumen.type.displayM, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (who.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(who, style = Lumen.type.bodyS, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(18.dp))
            // Dark Home only (Appearance Dark, or Automatic with the phone dark): while a speech
            // item is held (not playing), the line ends in the amber stop point. Light screens
            // never show amber.
            val holding = c.isDark && speech && !(live && player.isPlaying) && position > 0 && duration > 0
            ProgressLine(fraction = if (duration > 0) position.toFloat() / duration else 0f, emberStop = holding)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                // 0.4.0, the owner's mock: on light, the playing time is set in the ink, as the
                // playing timecode is everywhere else. Black keeps text2.
                val playingInk = !c.isDark && live && player.isPlaying
                Text(formatTime(position), style = Lumen.type.timeS, color = if (playingInk) c.accentInk else c.text2)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) formatTime(duration) else "--:--", style = Lumen.type.timeS, color = c.text2)
            }
            Spacer(Modifier.height(16.dp))
            PrimaryPill(
                text = if (live && player.isPlaying) "Playing" else "Resume",
                icon = if (live && player.isPlaying) LumenIcons.Pause else LumenIcons.Play,
                onClick = onResume,
            )
        }
    }
}

@Composable
private fun UpdateRow(tag: String, onUpdate: () -> Unit) {
    val c = Lumen.colours
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .clickable(onClick = onUpdate)
            .padding(horizontal = Gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Update $tag available", style = Lumen.type.bodyS, color = c.text, modifier = Modifier.weight(1f))
        Text("Update", style = Lumen.type.bodyStrong, color = c.accentInk)
    }
}
