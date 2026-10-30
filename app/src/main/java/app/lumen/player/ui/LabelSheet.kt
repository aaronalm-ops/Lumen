package app.lumen.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.drive.isDrive
import app.lumen.player.remote.isYtm
import kotlinx.coroutines.launch

/**
 * The label sheet. For an item: tick the labels it carries, or open one to edit. For a label:
 * name, one of six muted hue dots, three toggles, Save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelSheet(
    target: LabelSheetTarget,
    labels: List<LabelEntity>,
    downloadProgress: Float?,
    onToggle: (LibraryItem, Long) -> Unit,
    onSave: suspend (LabelEntity) -> Boolean,
    onDelete: (LabelEntity) -> Unit,
    onToggleOffline: (LibraryItem) -> Unit,
    onDismiss: () -> Unit,
    /** 0.8.2: mark an item played or unplayed. */
    onTogglePlayed: (LibraryItem) -> Unit = {},
) {
    val c = Lumen.colours
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Keyed on identity, not equality, so live label changes do not reset the sheet.
    val targetKey = when (target) {
        is LabelSheetTarget.ForItem -> "item:" + target.item.uri
        is LabelSheetTarget.EditLabel -> "label:" + (target.label?.id ?: 0L)
    }
    var editing by remember(targetKey) { mutableStateOf<LabelEntity?>((target as? LabelSheetTarget.EditLabel)?.let { it.label ?: newLabel() }) }
    var creating by remember(targetKey) { mutableStateOf(target is LabelSheetTarget.EditLabel && target.label == null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
        Column(Modifier.fillMaxWidth().padding(horizontal = Gutter).padding(bottom = 24.dp).navigationBarsPadding()) {
            val label = editing
            if (label != null) {
                EditLabelBody(
                    label = label,
                    isNew = creating,
                    onBack = if (target is LabelSheetTarget.ForItem) ({ editing = null }) else onDismiss,
                    onSave = onSave,
                    onSaved = { if (target is LabelSheetTarget.ForItem) editing = null else onDismiss() },
                    onDelete = {
                        onDelete(label)
                        if (target is LabelSheetTarget.ForItem) editing = null else onDismiss()
                    },
                )
            } else if (target is LabelSheetTarget.ForItem) {
                ItemLabelsBody(
                    item = target.item,
                    labels = labels,
                    downloadProgress = downloadProgress,
                    onToggle = onToggle,
                    onEdit = { editing = it; creating = false },
                    onNew = { editing = newLabel(); creating = true },
                    onToggleOffline = { onToggleOffline(target.item) },
                    onTogglePlayed = { onTogglePlayed(target.item) },
                )
            }
        }
    }
}

private fun newLabel() = LabelEntity(name = "", hue = 5, lockKids = false, noInterruptions = false, shuffleOnOpen = false)

@Composable
private fun ItemLabelsBody(
    item: LibraryItem,
    labels: List<LabelEntity>,
    downloadProgress: Float?,
    onToggle: (LibraryItem, Long) -> Unit,
    onEdit: (LabelEntity) -> Unit,
    onNew: () -> Unit,
    onToggleOffline: () -> Unit,
    onTogglePlayed: () -> Unit,
) {
    val c = Lumen.colours
    val view = LocalView.current
    Spacer(Modifier.height(12.dp))
    Text(item.media.title, style = Lumen.type.displayS, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(4.dp))
    Text("Playlists", style = Lumen.type.label, color = c.text2)
    Spacer(Modifier.height(8.dp))
    labels.forEachIndexed { index, label ->
        val on = label.id in item.labelIds
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onToggle(item, label.id)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) c.accentFill else c.surface)
                    // Off is outlined like a toggle off (text3 on light, 3:1 on the sheet).
                    .border(1.dp, if (on) c.accentFill else c.switchOffEdge, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) Icon(LumenIcons.Check, contentDescription = null, tint = c.onAccentFill, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(14.dp))
            HueDot(label.hue)
            Spacer(Modifier.width(8.dp))
            Text(label.name, style = Lumen.type.body, color = c.text, modifier = Modifier.weight(1f))
            TextAction("Edit") { onEdit(label) }
        }
        if (index < labels.lastIndex) HorizontalDivider(color = c.divider, thickness = 1.dp)
    }
    Spacer(Modifier.height(8.dp))
    TextAction("New playlist", onClick = onNew)
    if (!item.media.isYtm) {
        // 0.8.2: played or not, by hand.
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = c.divider, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = item.finished || item.durationMs > 0, onClick = onTogglePlayed),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LumenIcons.Check, contentDescription = null, tint = c.text2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (item.finished) "Mark as unplayed" else "Mark as played", style = Lumen.type.body, color = if (item.finished || item.durationMs > 0) c.text else c.text3)
                Text(
                    if (item.finished) "Starts from the beginning next time" else if (item.durationMs > 0) "Shows the tick and leaves the Unplayed list" else "Play it once first so Lumen knows its length",
                    style = Lumen.type.bodyS,
                    color = c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (item.media.isDrive) {
        // Phase 1b: the long-press route to "Keep offline" for a Drive row.
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = c.divider, thickness = 1.dp)
        val kept = item.media.offlinePath != null
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = downloadProgress == null, onClick = onToggleOffline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (kept) LumenIcons.Downloaded else LumenIcons.Download,
                contentDescription = null,
                tint = if (downloadProgress == null) c.text2 else c.text3,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        downloadProgress != null -> "Saving for offline"
                        kept -> "Remove download"
                        else -> "Keep offline"
                    },
                    style = Lumen.type.body,
                    color = if (downloadProgress == null) c.text else c.text3,
                )
                Text(
                    if (kept) "Plays from this phone, no network needed" else "Downloads on Wi-Fi unless mobile data is allowed in Settings",
                    style = Lumen.type.bodyS,
                    color = c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (downloadProgress != null) ProgressLine(fraction = downloadProgress, thickness = 1.dp)
    }
}

@Composable
private fun EditLabelBody(
    label: LabelEntity,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: suspend (LabelEntity) -> Boolean,
    onSaved: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Lumen.colours
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var name by remember(label.id) { mutableStateOf(label.name) }
    var hue by remember(label.id) { mutableIntStateOf(label.hue) }
    var lockKids by remember(label.id) { mutableStateOf(label.lockKids) }
    var noInterruptions by remember(label.id) { mutableStateOf(label.noInterruptions) }
    var shuffleOnOpen by remember(label.id) { mutableStateOf(label.shuffleOnOpen) }
    var error by remember { mutableStateOf<String?>(null) }
    var nameFocused by remember { mutableStateOf(false) }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconTap(LumenIcons.ChevronLeft, "Back", c.text, onClick = onBack)
        Spacer(Modifier.width(4.dp))
        Text(if (isNew) "New playlist" else "Edit playlist", style = Lumen.type.displayS, color = c.text)
    }
    Spacer(Modifier.height(12.dp))
    Text("Name", style = Lumen.type.label, color = c.text2)
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(TouchTarget)
            .clip(RoundedCornerShape(CardRadius))
            .background(c.surface)
            .then(
                // Focus on light: a 1.5 dp ink border, as the search field. Black keeps 0.3.0.
                when {
                    error != null -> Modifier.border(1.dp, c.danger, RoundedCornerShape(CardRadius))
                    nameFocused && !c.isDark -> Modifier.border(1.5.dp, c.accentInk, RoundedCornerShape(CardRadius))
                    else -> Modifier.border(1.dp, c.line, RoundedCornerShape(CardRadius))
                },
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (name.isEmpty()) Text("Playlist name", style = Lumen.type.body, color = c.text3)
        BasicTextField(
            value = name,
            onValueChange = { name = it; error = null },
            singleLine = true,
            textStyle = Lumen.type.body.copy(color = c.text),
            cursorBrush = SolidColor(c.accentInk),
            modifier = Modifier.fillMaxWidth().onFocusChanged { nameFocused = it.isFocused },
        )
    }
    if (error != null) {
        Spacer(Modifier.height(4.dp))
        Text(error.orEmpty(), style = Lumen.type.bodyS, color = c.danger)
    }
    Spacer(Modifier.height(16.dp))
    Text("Colour", style = Lumen.type.label, color = c.text2)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LabelHues.forEachIndexed { index, h ->
            Box(
                Modifier
                    .size(TouchTarget)
                    .clip(CircleShape)
                    .clickable { hue = index },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .border(1.dp, if (hue == index) c.silver else c.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(16.dp).background(if (c.isDark) h.onDark else h.onLight, CircleShape))
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    ToggleRow("Lock for kids", "Only play, previous and next; hold the top-right corner to leave", lockKids) { lockKids = it }
    ToggleRow("No interruptions", "Keeps the screen on, dimmed, and hides the sleep timer", noInterruptions) { noInterruptions = it }
    ToggleRow("Shuffle on open", "Plays this playlist in a random order", shuffleOnOpen) { shuffleOnOpen = it }
    Spacer(Modifier.height(16.dp))
    PrimaryPill("Save", icon = LumenIcons.Check) {
        scope.launch {
            val ok = onSave(label.copy(name = name.trim(), hue = hue, lockKids = lockKids, noInterruptions = noInterruptions, shuffleOnOpen = shuffleOnOpen))
            if (ok) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onSaved()
            } else {
                error = if (name.isBlank()) "Give the playlist a name" else "A playlist with this name already exists"
            }
        }
    }
    if (!isNew) {
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextAction("Delete playlist", danger = true, onClick = onDelete)
        }
    }
}
