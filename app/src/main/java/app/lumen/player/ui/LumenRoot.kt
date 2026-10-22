package app.lumen.player.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import app.lumen.player.BuildConfig
import app.lumen.player.data.MediaRepository

class RootActions(
    val addFolder: () -> Unit,
    /** Runs the whole-phone scan, asking for the media permission first when it is not held. */
    val scanPhone: () -> Unit,
    /** The app's page in system settings, for a permission that was refused. */
    val openAppSettings: () -> Unit,
    val openUpdate: () -> Unit,
    val openGitHub: () -> Unit,
    // Phase 1b.
    /** Authorises drive.file (the Google sheet only when the grant is missing) and opens the folder picker. */
    val connectDrive: () -> Unit,
    /** "Sign in to Drive again": the authorisation alone, no picker. */
    val driveSignIn: () -> Unit,
    /** ACTION_CREATE_DOCUMENT for the backup file. */
    val backupNow: () -> Unit,
    /** ACTION_OPEN_DOCUMENT for a backup to merge. */
    val restoreBackup: () -> Unit,
)

/**
 * Wires the ViewModel to the screens. Home, Library and Settings are light (black with Night);
 * Now Playing is always black and sits over everything while open.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LumenRoot(vm: MainViewModel, actions: RootActions, showBrand: Boolean = false) {
    val context = LocalContext.current
    var brand by remember { mutableStateOf(showBrand) }
    val settings by vm.settings.collectAsState()
    val screen by vm.screen.collectAsState()
    val nowPlayingOpen by vm.nowPlayingOpen.collectAsState()
    val player by vm.player.collectAsState()
    val controller by vm.controller.collectAsState()
    val labels by vm.labels.collectAsState()
    val items by vm.items.collectAsState()
    val recent by vm.recent.collectAsState()
    val folders by vm.folders.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scanProgress by vm.scanProgress.collectAsState()
    val mediaAccess by vm.mediaAccess.collectAsState()
    val mediaAccessDenied by vm.mediaAccessDenied.collectAsState()
    val continueItem by vm.continueItem.collectAsState()
    val currentItem by vm.currentItem.collectAsState()
    val currentLabels by vm.currentLabels.collectAsState()
    val speech by vm.currentIsSpeech.collectAsState()
    val kidsLocked by vm.kidsLocked.collectAsState()
    val noInterruptions by vm.noInterruptions.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val audioOnly by vm.audioOnly.collectAsState()
    val labelSheet by vm.labelSheet.collectAsState()
    val update by vm.update.collectAsState()
    val lastSaved by vm.lastSaved.collectAsState()
    val search by vm.librarySearch.collectAsState()
    val sortByName by vm.librarySortByName.collectAsState()
    val folderFilter by vm.libraryFolder.collectAsState()
    val driveScanning by vm.driveScanning.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val unreachable by vm.unreachable.collectAsState()
    val backupResult by vm.backupResult.collectAsState()
    val backupBusy by vm.backupBusy.collectAsState()

    // Kids lock forces Now Playing open; nothing else can be reached until the corner is held.
    LaunchedEffect(kidsLocked) { if (kidsLocked) vm.nowPlayingOpen.value = true }

    // "Audio only": drop the video track while the surface is away, restore it when back.
    LaunchedEffect(nowPlayingOpen, audioOnly, player.isVideo, controller) {
        if (player.isVideo && controller != null) vm.setVideoTrackEnabled(nowPlayingOpen || !audioOnly)
    }

    val showingBlack = nowPlayingOpen || settings.night
    LumenTheme(dark = showingBlack) {
        val c = Lumen.colours
        SystemBars(light = !showingBlack, background = c.base)
        Box(Modifier.fillMaxSize().background(c.base)) {
            if (nowPlayingOpen) {
                NowPlayingScreen(
                    player = player,
                    controller = controller,
                    item = currentItem,
                    labels = currentLabels,
                    settings = settings,
                    speech = speech,
                    kidsLocked = kidsLocked,
                    noInterruptions = noInterruptions,
                    bookmarks = bookmarks,
                    audioOnly = audioOnly,
                    downloadProgress = currentItem?.let { downloads[it.uri] },
                    actions = NowPlayingActions(
                        close = { vm.nowPlayingOpen.value = false },
                        playPause = vm::playPause,
                        seekBack = vm::seekBack,
                        seekForward = vm::seekForward,
                        seekTo = vm::seekTo,
                        next = vm::next,
                        previous = vm::previous,
                        toggleShuffle = vm::toggleShuffle,
                        cycleRepeat = vm::cycleRepeat,
                        cycleSpeed = vm::cycleSpeed,
                        cyclePreset = vm::cyclePreset,
                        cycleSleep = vm::cycleSleep,
                        addBookmark = vm::addBookmark,
                        deleteBookmark = vm::deleteBookmark,
                        toggleLayout = vm::toggleLayoutOverride,
                        unlockKids = vm::unlockKids,
                        setAudioOnly = { vm.audioOnly.value = it },
                        openLabelSheet = { currentItem?.let { vm.openLabelSheet(LabelSheetTarget.ForItem(it)) } },
                        toggleOffline = { currentItem?.let { vm.toggleOffline(it) } },
                    ),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Crossfade(targetState = screen, animationSpec = tween(200), label = "screen") { s ->
                            when (s) {
                                Screen.Home -> HomeScreen(
                                    continueItem = continueItem,
                                    continueIsSpeech = if (player.hasItem) {
                                        speech
                                    } else {
                                        continueItem?.let { item ->
                                            MediaRepository.isSpeech(
                                                item.media,
                                                labels.filter { it.id in item.labelIds }.map { it.name },
                                                settings.layoutOverrides[item.uri],
                                            )
                                        } ?: false
                                    },
                                    player = player,
                                    labels = labels,
                                    recent = recent,
                                    hasLibrary = items.isNotEmpty(),
                                    scanning = scanning || driveScanning,
                                    mediaAccessDenied = mediaAccessDenied,
                                    update = update,
                                    onSettings = { vm.screen.value = Screen.Settings },
                                    onResume = vm::resume,
                                    onLabel = vm::playLabel,
                                    onLabelLongPress = { vm.openLabelSheet(LabelSheetTarget.EditLabel(it)) },
                                    onItem = { item -> vm.play(item, recent) },
                                    onItemLongPress = { vm.openLabelSheet(LabelSheetTarget.ForItem(it)) },
                                    onUpdate = actions.openUpdate,
                                    onScanPhone = actions.scanPhone,
                                    onAddFolder = actions.addFolder,
                                    onOpenAppSettings = actions.openAppSettings,
                                    onOpenLibrary = { vm.screen.value = Screen.Library },
                                )
                                Screen.Library -> LibraryScreen(
                                    items = items,
                                    folders = folders,
                                    labels = labels,
                                    player = player,
                                    scanning = scanning || driveScanning,
                                    mediaAccessDenied = mediaAccessDenied,
                                    unreachable = unreachable,
                                    downloads = downloads,
                                    search = search,
                                    sortByName = sortByName,
                                    folderFilter = folderFilter,
                                    onSearch = { vm.librarySearch.value = it },
                                    onToggleSort = { vm.librarySortByName.value = !vm.librarySortByName.value },
                                    onFolder = { vm.libraryFolder.value = it },
                                    onItem = { item, queue -> vm.play(item, queue) },
                                    onItemLongPress = { vm.openLabelSheet(LabelSheetTarget.ForItem(it)) },
                                    onScanPhone = actions.scanPhone,
                                    onAddFolder = actions.addFolder,
                                    onOpenAppSettings = actions.openAppSettings,
                                )
                                Screen.Settings -> SettingsScreen(
                                    settings = settings,
                                    folders = folders,
                                    folderCounts = items.groupingBy { it.media.folderUri }.eachCount(),
                                    scanning = scanning,
                                    scanProgress = scanProgress,
                                    mediaAccess = mediaAccess,
                                    mediaAccessDenied = mediaAccessDenied,
                                    update = update,
                                    lastSaved = lastSaved,
                                    driveScanning = driveScanning,
                                    driveConfigured = BuildConfig.PICKER_API_KEY.isNotBlank(),
                                    backupResult = backupResult,
                                    backupBusy = backupBusy,
                                    actions = SettingsActions(
                                        back = { vm.screen.value = Screen.Home },
                                        scanPhone = actions.scanPhone,
                                        openAppSettings = actions.openAppSettings,
                                        addFolder = actions.addFolder,
                                        removeFolder = vm::removeFolder,
                                        rescan = vm::rescan,
                                        setPreset = vm::setPreset,
                                        setCustomBands = vm::setCustomBands,
                                        setSkips = vm::setSkips,
                                        setNight = vm::setNight,
                                        shareDiagnostics = { vm.shareDiagnostics(context) },
                                        openUpdate = { if (update != null) actions.openUpdate() else vm.checkUpdateNow(context) },
                                        openGitHub = actions.openGitHub,
                                        connectDrive = actions.connectDrive,
                                        driveSignIn = actions.driveSignIn,
                                        rescanDrive = vm::rescanDrive,
                                        disconnectDrive = vm::disconnectDrive,
                                        setMobileDownloads = vm::setMobileDownloads,
                                        backupNow = actions.backupNow,
                                        restoreBackup = actions.restoreBackup,
                                    ),
                                )
                            }
                        }
                    }
                    if (player.hasItem && screen == Screen.Library) {
                        val remaining = if (player.durationMs > 0) "-" + formatTime((player.durationMs - player.positionMs).coerceAtLeast(0L)) else "--:--"
                        MiniBar(
                            title = player.title,
                            time = formatTime(player.positionMs),
                            meta = "$remaining · ${speedLabel(player.speed)}",
                            isPlaying = player.isPlaying,
                            onOpen = { vm.nowPlayingOpen.value = true },
                            onPlayPause = vm::playPause,
                        )
                    }
                    if (screen != Screen.Settings) {
                        BottomBar(current = screen) { vm.screen.value = it }
                    }
                }
                BackHandler(enabled = screen != Screen.Home) { vm.screen.value = Screen.Home }
            }

            labelSheet?.let { target ->
                // Re-resolve the item so the ticks follow the live label links.
                val live = when (target) {
                    is LabelSheetTarget.ForItem -> LabelSheetTarget.ForItem(items.firstOrNull { it.uri == target.item.uri } ?: target.item)
                    is LabelSheetTarget.EditLabel -> target
                }
                LabelSheet(
                    target = live,
                    labels = labels,
                    downloadProgress = (live as? LabelSheetTarget.ForItem)?.let { downloads[it.item.uri] },
                    onToggle = vm::toggleLabelOnItem,
                    onSave = { vm.saveLabel(it) },
                    onDelete = vm::deleteLabel,
                    onToggleOffline = vm::toggleOffline,
                    onDismiss = vm::closeLabelSheet,
                )
            }

            // Cold start only: the lockup on black for 600 ms, then a fade. Nothing animates after.
            if (brand) BrandedSplash(onDone = { brand = false })
        }
    }
}

/**
 * Dark status bar icons on light screens, light icons on black. The window background follows
 * the screen's base (#C9CED4 on light, black on black) so nothing of the old ground shows behind
 * the content during resizes or the Now Playing switch.
 */
@Composable
private fun SystemBars(light: Boolean, background: Color) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
        val argb = background.toArgb()
        LaunchedEffect(argb) {
            (view.context as? Activity)?.window?.setBackgroundDrawable(ColorDrawable(argb))
        }
    }
}
