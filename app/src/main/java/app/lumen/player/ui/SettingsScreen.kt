package app.lumen.player.ui

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.lumen.player.remote.RemoteController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.player.BuildConfig
import app.lumen.player.data.Appearance
import app.lumen.player.data.EqPreset
import app.lumen.player.data.FolderEntity
import app.lumen.player.data.Settings
import app.lumen.player.data.SettingsRepository
import app.lumen.player.data.drive.isDrive
import app.lumen.player.data.isMediaStore
import app.lumen.player.remote.isYtm
import app.lumen.player.update.UpdateInfo

class SettingsActions(
    val back: () -> Unit,
    val scanPhone: () -> Unit,
    val openAppSettings: () -> Unit,
    val addFolder: () -> Unit,
    val removeFolder: (FolderEntity) -> Unit,
    val rescan: () -> Unit,
    val setPreset: (String) -> Unit,
    val setCustomBands: (List<Float>) -> Unit,
    val setSkips: (Int, Int) -> Unit,
    /** 0.4.0: "auto", "light" or "dark", replacing the Night toggle. */
    val setAppearance: (String) -> Unit,
    val shareDiagnostics: () -> Unit,
    val openUpdate: () -> Unit,
    val openGitHub: () -> Unit,
    // Phase 1b.
    val connectDrive: () -> Unit,
    val driveSignIn: () -> Unit,
    val rescanDrive: () -> Unit,
    val disconnectDrive: () -> Unit,
    val setMobileDownloads: (Boolean) -> Unit,
    val backupNow: () -> Unit,
    val restoreBackup: () -> Unit,
    // 0.4.0: Sound.
    val setLevelOutVoices: (Boolean) -> Unit,
    val setTrimSilences: (Boolean) -> Unit,
    val setCrossfadeSeconds: (Int) -> Unit,
    /** 0.5.0: Settings, Notification access, for the YouTube Music remote. */
    val openNotificationAccess: () -> Unit,
    /** 0.6.0: saves the Gemini key; blank removes it. */
    val saveGeminiKey: (String) -> Unit,
    /** 0.6.0: Google AI Studio's key page in the browser. */
    val openGeminiKeyPage: () -> Unit,
)

/** Settings, light: one list under mono labels, no row icons. */
@Composable
fun SettingsScreen(
    settings: Settings,
    folders: List<FolderEntity>,
    folderCounts: Map<String, Int>,
    scanning: Boolean,
    scanProgress: Int,
    mediaAccess: Boolean,
    mediaAccessDenied: Boolean,
    update: UpdateInfo?,
    lastSaved: String,
    driveScanning: Boolean,
    backupResult: String,
    backupBusy: Boolean,
    /** 0.5.0: whether Notification access is on, so Lumen can control YouTube Music. */
    ytmAccess: Boolean,
    /** 0.6.0: the last four characters of the saved Gemini key, empty when none is saved. */
    notesKeyHint: String,
    actions: SettingsActions,
) {
    val c = Lumen.colours
    val picked = folders.filter { !it.isMediaStore && !it.isDrive && !it.isYtm }
    Column(Modifier.fillMaxSize().background(c.base).statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTap(LumenIcons.ChevronLeft, "Back", c.text, onClick = actions.back)
            Spacer(Modifier.width(4.dp))
            Text("Settings", style = Lumen.type.displayM, color = c.text)
        }
        if (scanning || driveScanning || backupBusy) ProgressLine(fraction = 1f, thickness = 1.dp)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gutter, end = Gutter, top = 4.dp, bottom = 8.dp),
        ) {
            item {
                LumenLockup(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp), height = 28.dp)
            }
            item {
                SectionLabel("Folders", modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                ListCard {
                    // Whole phone first: the primary way in since 0.2.1.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Scan whole phone", style = Lumen.type.bodyStrong, color = c.text)
                            Text(
                                when {
                                    scanning && scanProgress > 0 -> "Scanning, ${formatCount(scanProgress)} files so far"
                                    scanning -> "Scanning"
                                    settings.mediaStoreScanAt > 0L ->
                                        "${formatCount(settings.mediaStoreScanCount)} files · scanned ${relativeTime(settings.mediaStoreScanAt)}"
                                    mediaAccess -> "Audio and video Android already knows about"
                                    else -> "Audio and video Android already knows about. Asks for access once"
                                },
                                style = Lumen.type.bodyS,
                                color = c.text2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextAction(
                            when {
                                scanning -> "Scanning"
                                settings.mediaStoreScanAt > 0L && mediaAccess -> "Rescan"
                                else -> "Scan"
                            },
                            enabled = !scanning,
                            onClick = actions.scanPhone,
                        )
                    }
                    if (mediaAccessDenied && !mediaAccess) {
                        PermissionNotice(onOpenSettings = actions.openAppSettings, modifier = Modifier.padding(start = 14.dp, end = 4.dp, bottom = 6.dp))
                    }
                    HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))

                    picked.forEach { folder ->
                        Row(
                            Modifier.fillMaxWidth().height(RowHeight).padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(folder.name, style = Lumen.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${formatCount(folderCounts[folder.uri] ?: 0)} files", style = Lumen.type.bodyS, color = c.text2)
                            }
                            IconTap(LumenIcons.Trash, "Remove ${folder.name}", c.danger, size = 20.dp) { actions.removeFolder(folder) }
                        }
                        HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Add a folder", style = Lumen.type.bodyStrong, color = c.text)
                            Text(
                                "For folders Android keeps out of the phone scan, such as app-private audio",
                                style = Lumen.type.bodyS,
                                color = c.text2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextAction("Add", onClick = actions.addFolder)
                        if (picked.isNotEmpty()) {
                            TextAction(if (scanning) "Scanning" else "Rescan", enabled = !scanning, onClick = actions.rescan)
                        }
                    }
                }
            }

            item {
                SectionLabel("Google Drive", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    DriveBody(
                        settings = settings,
                        connected = settings.driveConnected,
                        scanning = driveScanning,
                        actions = actions,
                    )
                }
            }

            if (RemoteController.CONTROL_ENABLED) item {
                // 0.5.0: the YouTube Music remote. The system page is opened from this row only; Lumen
                // never asks on its own. 0.6.0: hidden while the control is off (Play Protect).
                SectionLabel("YouTube Music", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Allow Lumen to control YouTube Music", style = Lumen.type.bodyStrong, color = c.text)
                            Text(
                                if (ytmAccess) {
                                    "On. Lumen reads what YouTube Music is playing to show it and move your queue on"
                                } else {
                                    "Notification access lets Lumen show and control YouTube Music. Share a song to Lumen to add it"
                                },
                                style = Lumen.type.bodyS,
                                color = c.text2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextAction(if (ytmAccess) "Change" else "Allow", onClick = actions.openNotificationAccess)
                    }
                }
            }

            item {
                // 0.6.0: sermon notes. The key stays on this phone, outside backups.
                SectionLabel("Sermon notes", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Gemini key", style = Lumen.type.bodyStrong, color = c.text)
                            Text(
                                if (notesKeyHint.isNotBlank()) {
                                    "Saved, ending $notesKeyHint. Used only to write notes"
                                } else {
                                    "Notes are written by Google's Gemini. Get a free key from Google AI Studio and paste it below"
                                },
                                style = Lumen.type.bodyS,
                                color = c.text2,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (notesKeyHint.isNotBlank()) {
                            TextAction("Remove", danger = true, onClick = { actions.saveGeminiKey("") })
                        } else {
                            TextAction("Get a key", onClick = actions.openGeminiKeyPage)
                        }
                    }
                    if (notesKeyHint.isBlank()) {
                        KeyField(onSave = actions.saveGeminiKey)
                    }
                    HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
                    Text(
                        "Tap Notes on a sermon in Now Playing. On Wi-Fi, Lumen uploads the recording to Google, " +
                            "gets a timed transcript, deletes the recording from Google, then writes notes from the " +
                            "transcript alone: only what was said. On Google's free tier, Google may use what you send " +
                            "to improve its products.",
                        style = Lumen.type.bodyS,
                        color = c.text2,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            item {
                SectionLabel("Backup", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Back up now", style = Lumen.type.bodyStrong, color = c.text)
                            Text(
                                when {
                                    backupBusy -> "Working"
                                    backupResult.isNotBlank() -> backupResult
                                    settings.backupAt > 0L -> "Last backup ${relativeTime(settings.backupAt)}"
                                    else -> "Positions, labels, bookmarks, chapters and settings as one file you choose where to keep"
                                },
                                style = Lumen.type.bodyS,
                                color = c.text2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextAction("Back up", enabled = !backupBusy, onClick = actions.backupNow)
                    }
                    HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Restore", style = Lumen.type.bodyStrong, color = c.text)
                            Text(
                                if (settings.driveConnected) {
                                    "Merges a backup file; nothing is deleted. Drive keeps a copy in its Lumen folder" +
                                        (if (settings.backupDriveAt > 0L) ", last ${relativeTime(settings.backupDriveAt)}" else "")
                                } else {
                                    "Merges a backup file; nothing is deleted"
                                },
                                style = Lumen.type.bodyS,
                                color = c.text2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextAction("Restore", enabled = !backupBusy, onClick = actions.restoreBackup)
                    }
                }
            }

            item {
                SectionLabel("Equaliser", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    if (settings.eqEngine == "none") {
                        Text(
                            "This device refused both equaliser engines, so the EQ is off. Playback is unaffected.",
                            style = Lumen.type.bodyS,
                            color = c.text2,
                            modifier = Modifier.padding(14.dp),
                        )
                    } else {
                        EqualiserBody(settings, actions)
                    }
                }
            }

            item {
                SectionLabel("Sound", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    SoundBody(settings, actions)
                }
            }

            item {
                SectionLabel("Skip intervals", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    StepperRow("Back", settings.skipBackSeconds, listOf(5, 10, 15, 30)) { actions.setSkips(it, settings.skipForwardSeconds) }
                    HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
                    StepperRow("Forward", settings.skipForwardSeconds, listOf(10, 15, 30, 45, 60)) { actions.setSkips(settings.skipBackSeconds, it) }
                }
            }

            item {
                SectionLabel("Appearance", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Appearance.order.forEach { value ->
                                StatusChip(
                                    text = Appearance.label(value),
                                    active = settings.appearance == value,
                                    onClick = { actions.setAppearance(value) },
                                )
                            }
                        }
                        Text(
                            when (settings.appearance) {
                                Appearance.LIGHT -> "Home, Library and Settings stay light. Now Playing is always black"
                                Appearance.DARK -> "Home, Library and Settings stay black. Now Playing is always black"
                                else -> "Follows the phone's dark theme. Now Playing is always black"
                            },
                            style = Lumen.type.bodyS,
                            color = c.text2,
                        )
                    }
                }
            }

            item {
                SectionLabel("Diagnostics", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("Last saved position: $lastSaved", style = Lumen.type.bodyS, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Row {
                            TextAction("Share diagnostics", modifier = Modifier.padding(start = 0.dp), onClick = actions.shareDiagnostics)
                        }
                    }
                }
            }

            item {
                SectionLabel("About", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text("Lumen ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = Lumen.type.body, color = c.text)
                        Text(
                            if (update != null) "Update ${update.tag} is available" else "Up to date as of the last daily check",
                            style = Lumen.type.bodyS,
                            color = c.text2,
                        )
                        Row(Modifier.padding(top = 4.dp)) {
                            TextAction(if (update != null) "Get ${update.tag}" else "Check for updates", onClick = actions.openUpdate)
                            TextAction("GitHub", onClick = actions.openGitHub)
                        }
                    }
                }
            }

            item {
                MakerCredit(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * The Drive card. Not connected: "Choose folder" (the Google sheet appears only when the grant
 * is missing, then Lumen's folder browser). Connected: the folder name, its file count and last
 * scan, the account, then Choose folder, Rescan and Disconnect, and the mobile data toggle. One
 * quiet line at most: "Reconnect Drive to see the files in your folder" when the grant lacks
 * drive.readonly (a 0.3.0 grant), otherwise "Sign in to Drive again" after a 401 or 403.
 */
@Composable
private fun DriveBody(
    settings: Settings,
    connected: Boolean,
    scanning: Boolean,
    actions: SettingsActions,
) {
    val c = Lumen.colours
    if (!connected) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sermon folder on Drive", style = Lumen.type.bodyStrong, color = c.text)
                Text(
                    "Choose the folder Lumen plays from. Lumen only reads your Drive and never changes it",
                    style = Lumen.type.bodyS,
                    color = c.text2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextAction("Choose folder", onClick = actions.connectDrive)
        }
        DriveNotice(settings, actions)
        return
    }
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp)) {
        Text(
            settings.driveFolderName.ifBlank { "Drive folder" },
            style = Lumen.type.bodyStrong,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            when {
                scanning -> "Listing the folder"
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
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextAction("Choose folder", onClick = actions.connectDrive)
        TextAction(if (scanning) "Listing" else "Rescan", enabled = !scanning, onClick = actions.rescanDrive)
        Spacer(Modifier.weight(1f))
        TextAction("Disconnect", danger = true, onClick = actions.disconnectDrive)
    }
    DriveNotice(settings, actions)
    HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
    ToggleRow(
        "Allow downloads on mobile data",
        "Keep offline otherwise waits for Wi-Fi",
        settings.driveMobileDownloads,
        onChange = actions.setMobileDownloads,
    )
}

/** The one quiet line under the Drive card's actions, when Drive needs the owner. Never a dialog. */
@Composable
private fun DriveNotice(settings: Settings, actions: SettingsActions) {
    val c = Lumen.colours
    val text: String
    val action: String
    when {
        settings.driveNeedsReconnect && settings.driveConnected -> {
            text = "Reconnect Drive to see the files in your folder"
            action = "Reconnect"
        }
        settings.driveNeedsSignIn -> {
            text = "Drive refused the last request"
            action = "Sign in to Drive again"
        }
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Lumen.type.bodyS, color = c.text2, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        TextAction(action, onClick = actions.driveSignIn)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EqualiserBody(settings: Settings, actions: SettingsActions) {
    val c = Lumen.colours
    val custom = settings.eqPreset == EqPreset.CUSTOM
    val gains = EqPreset.gains(settings.eqPreset, settings.eqCustom)
    var draft by remember(settings.eqCustom, settings.eqPreset) { mutableStateOf(gains) }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EqPreset.order) { preset ->
                StatusChip(text = preset, active = preset == settings.eqPreset, onClick = { actions.setPreset(preset) })
            }
        }
        Spacer(Modifier.height(8.dp))
        EqPreset.bandHz.forEachIndexed { index, hz ->
            Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(bandLabel(hz), style = Lumen.type.timeS, color = c.text2, modifier = Modifier.width(52.dp))
                val interaction = remember { MutableInteractionSource() }
                val colours = SliderDefaults.colors(
                    thumbColor = c.text,
                    activeTrackColor = c.accentFill,
                    inactiveTrackColor = c.divider,
                    disabledThumbColor = c.text3,
                    disabledActiveTrackColor = c.silver,
                    disabledInactiveTrackColor = c.divider,
                )
                Slider(
                    value = draft.getOrElse(index) { 0f },
                    onValueChange = { v -> draft = draft.toMutableList().also { it[index] = kotlin.math.round(v * 2f) / 2f } },
                    modifier = Modifier.weight(1f),
                    enabled = custom,
                    onValueChangeFinished = { if (custom) actions.setCustomBands(draft) },
                    colors = colours,
                    interactionSource = interaction,
                    thumb = {
                        if (c.isDark) {
                            SliderDefaults.Thumb(interactionSource = interaction, colors = colours, enabled = custom)
                        } else {
                            LightBandThumb(enabled = custom)
                        }
                    },
                    valueRange = -12f..12f,
                )
                Text(
                    String.format(java.util.Locale.UK, "%+.1f dB", draft.getOrElse(index) { 0f }),
                    style = Lumen.type.timeS,
                    color = c.text2,
                    modifier = Modifier.width(64.dp),
                )
            }
        }
        Text(
            when {
                custom -> "Drag a band to set its gain. Custom is kept until you pick another preset."
                settings.eqEngine == "eq" -> "Shown on the nearest bands this device offers. Pick Custom to edit."
                else -> "Speech adds a gentle compressor and +3 dB loudness. Pick Custom to edit."
            },
            style = Lumen.type.bodyS,
            color = c.text3,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 0.4.0: Settings, Sound. Two switches for speech (they also sit behind the Clarity chip on the
 * speech Now Playing) and the Smooth transitions slider for music: Off, 2, 4 or 6 seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundBody(settings: Settings, actions: SettingsActions) {
    val c = Lumen.colours
    val levelOutAvailable = settings.eqEngine != "eq" && settings.eqEngine != "none"
    ToggleRow(
        "Level out voices",
        if (levelOutAvailable) "Sermons only: brings quiet and loud speakers closer" else "Needs an equaliser this phone does not offer",
        settings.levelOutVoices && levelOutAvailable,
        enabled = levelOutAvailable,
        onChange = actions.setLevelOutVoices,
    )
    HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
    ToggleRow(
        "Trim silences",
        "Sermons only: skips long pauses. Music is never trimmed",
        settings.trimSilences,
        onChange = actions.setTrimSilences,
    )
    HorizontalDivider(color = c.divider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
    val choices = SettingsRepository.crossfadeChoices
    var draft by remember(settings.crossfadeSeconds) { mutableStateOf(settings.crossfadeSeconds.toFloat()) }
    val shown = choices.minByOrNull { kotlin.math.abs(it - draft) } ?: 0
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Smooth transitions", style = Lumen.type.body, color = c.text)
                Text("Fades a song out and the next one in. Music only, never into a sermon", style = Lumen.type.bodyS, color = c.text2)
            }
            Spacer(Modifier.width(12.dp))
            Text(if (shown == 0) "Off" else "$shown s", style = Lumen.type.timeL, color = c.text, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        val interaction = remember { MutableInteractionSource() }
        val colours = SliderDefaults.colors(
            thumbColor = c.text,
            activeTrackColor = c.accentFill,
            inactiveTrackColor = c.divider,
            activeTickColor = c.onAccentFill,
            inactiveTickColor = c.line,
        )
        Slider(
            value = draft,
            onValueChange = { draft = it },
            // Read from the draft when the drag ends: a quick tap can finish before a recomposition
            // has updated [shown], which would save the previous choice.
            onValueChangeFinished = { actions.setCrossfadeSeconds(choices.minByOrNull { kotlin.math.abs(it - draft) } ?: 0) },
            valueRange = 0f..choices.last().toFloat(),
            steps = choices.size - 2,
            colors = colours,
            interactionSource = interaction,
            thumb = {
                if (c.isDark) {
                    SliderDefaults.Thumb(interactionSource = interaction, colors = colours, enabled = true)
                } else {
                    LightBandThumb(enabled = true)
                }
            },
            modifier = Modifier.fillMaxWidth().height(TouchTarget),
        )
        Row(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, value ->
                if (index > 0) Spacer(Modifier.weight(1f))
                Text(if (value == 0) "Off" else "$value s", style = Lumen.type.timeS, color = c.text3)
            }
        }
    }
}

private fun bandLabel(hz: Int): String = if (hz >= 1000) "${hz / 1000}k" else "$hz"

@Composable
private fun StepperRow(title: String, value: Int, choices: List<Int>, onChange: (Int) -> Unit) {
    val c = Lumen.colours
    val exact = choices.indexOf(value)
    val index = if (exact >= 0) exact else choices.indexOfFirst { choice -> choice >= value }.coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().height(RowHeight).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Lumen.type.body, color = c.text, modifier = Modifier.weight(1f))
        StepButton("Less", enabled = index > 0, minus = true) { onChange(choices[index - 1]) }
        Text("$value s", style = Lumen.type.timeL, color = c.text, modifier = Modifier.width(56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton("More", enabled = index < choices.lastIndex, minus = false) { onChange(choices[index + 1]) }
    }
}

@Composable
private fun StepButton(description: String, enabled: Boolean, minus: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    Box(
        Modifier
            .size(TouchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (minus) {
            Box(Modifier.size(width = 14.dp, height = 2.dp).background(if (enabled) c.text else c.text3))
        } else {
            Icon(LumenIcons.Plus, contentDescription = description, tint = if (enabled) c.text else c.text3, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * The equaliser thumb on light screens (Option C): an 18 dp charcoal disc with a 6 dp cyan
 * centre, the status-LED look of the other controls. Disabled, it is a plain text3 disc.
 */
@Composable
private fun LightBandThumb(enabled: Boolean) {
    val c = Lumen.colours
    Box(
        Modifier.size(18.dp).background(if (enabled) c.accentFill else c.text3, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (enabled) Box(Modifier.size(6.dp).background(c.onAccentFill, CircleShape))
    }
}

/** 0.6.0: the Gemini key, typed or pasted, hidden as it is entered; Save stores it on this phone. */
@Composable
private fun KeyField(onSave: (String) -> Unit) {
    val c = Lumen.colours
    var value by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(c.thumb)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text("Paste your key", style = Lumen.type.bodyS, color = c.text3)
            BasicTextField(
                value = value,
                onValueChange = { value = it.trim() },
                singleLine = true,
                textStyle = Lumen.type.bodyS.copy(color = c.text),
                cursorBrush = SolidColor(c.accentInk),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextAction("Save", enabled = value.length >= 20, onClick = {
            onSave(value)
            value = ""
        })
    }
}
