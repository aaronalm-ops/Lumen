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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lumen.player.BuildConfig
import app.lumen.player.data.EqPreset
import app.lumen.player.data.FolderEntity
import app.lumen.player.data.Settings
import app.lumen.player.data.drive.isDrive
import app.lumen.player.data.isMediaStore
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
    val setNight: (Boolean) -> Unit,
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
    /** False when BuildConfig.PICKER_API_KEY is empty: the Drive card says so and does nothing. */
    driveConfigured: Boolean,
    backupResult: String,
    backupBusy: Boolean,
    actions: SettingsActions,
) {
    val c = Lumen.colours
    val picked = folders.filter { !it.isMediaStore && !it.isDrive }
    val driveFolder = folders.firstOrNull { it.isDrive }
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
                    HorizontalDivider(color = c.line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))

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
                        HorizontalDivider(color = c.line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
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
                        configured = driveConfigured,
                        connected = settings.driveConnected,
                        count = driveFolder?.let { folderCounts[it.uri] } ?: 0,
                        scanning = driveScanning,
                        actions = actions,
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
                                    else -> "Positions, labels, bookmarks and settings as one file you choose where to keep"
                                },
                                style = Lumen.type.bodyS,
                                color = c.text2,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextAction("Back up", enabled = !backupBusy, onClick = actions.backupNow)
                    }
                    HorizontalDivider(color = c.line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
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
                SectionLabel("Skip intervals", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    StepperRow("Back", settings.skipBackSeconds, listOf(5, 10, 15, 30)) { actions.setSkips(it, settings.skipForwardSeconds) }
                    HorizontalDivider(color = c.line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
                    StepperRow("Forward", settings.skipForwardSeconds, listOf(10, 15, 30, 45, 60)) { actions.setSkips(settings.skipBackSeconds, it) }
                }
            }

            item {
                SectionLabel("Night", modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
                ListCard {
                    ToggleRow("Black Home and Library", "Now Playing is always black", settings.night, onChange = actions.setNight)
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
 * The Drive card. Not configured: one line, nothing to tap. Not connected: "Choose folder"
 * (the Google sheet appears only when the grant is missing, then Google's picker). Connected:
 * the folder, its count and last scan, Rescan and Change, the mobile data toggle, Disconnect,
 * and the one-line "Sign in to Drive again" after a 401 or 403.
 */
@Composable
private fun DriveBody(
    settings: Settings,
    configured: Boolean,
    connected: Boolean,
    count: Int,
    scanning: Boolean,
    actions: SettingsActions,
) {
    val c = Lumen.colours
    if (!configured) {
        Text(
            "Drive not configured in this build",
            style = Lumen.type.bodyS,
            color = c.text2,
            modifier = Modifier.padding(14.dp),
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (connected) settings.driveFolderName.ifBlank { "Drive folder" } else "Sermon folder on Drive",
                style = Lumen.type.bodyStrong,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    !connected -> "Pick the folder once with Google's picker. Lumen only ever sees that folder"
                    scanning -> "Listing the folder"
                    settings.driveScanAt > 0L -> "${formatCount(count)} files · listed ${relativeTime(settings.driveScanAt)}"
                    else -> "Not listed yet"
                },
                style = Lumen.type.bodyS,
                color = c.text2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (connected) {
            TextAction(if (scanning) "Listing" else "Rescan", enabled = !scanning, onClick = actions.rescanDrive)
            TextAction("Change", onClick = actions.connectDrive)
        } else {
            TextAction("Choose folder", onClick = actions.connectDrive)
        }
    }
    if (settings.driveNeedsSignIn) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Drive refused the last request", style = Lumen.type.bodyS, color = c.text2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextAction("Sign in to Drive again", onClick = actions.driveSignIn)
        }
    }
    if (connected) {
        HorizontalDivider(color = c.line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
        ToggleRow(
            "Allow downloads on mobile data",
            "Keep offline otherwise waits for Wi-Fi",
            settings.driveMobileDownloads,
            onChange = actions.setMobileDownloads,
        )
        HorizontalDivider(color = c.line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 14.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Removes the Drive rows and any kept copies; positions and labels stay",
                style = Lumen.type.bodyS,
                color = c.text2,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextAction("Disconnect", danger = true, onClick = actions.disconnectDrive)
        }
    }
}

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
                Slider(
                    value = draft.getOrElse(index) { 0f },
                    onValueChange = { v -> draft = draft.toMutableList().also { it[index] = kotlin.math.round(v * 2f) / 2f } },
                    onValueChangeFinished = { if (custom) actions.setCustomBands(draft) },
                    valueRange = -12f..12f,
                    enabled = custom,
                    colors = SliderDefaults.colors(
                        thumbColor = c.text,
                        activeTrackColor = c.accent,
                        inactiveTrackColor = c.line,
                        disabledThumbColor = c.text3,
                        disabledActiveTrackColor = c.silver,
                        disabledInactiveTrackColor = c.line,
                    ),
                    modifier = Modifier.weight(1f),
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
