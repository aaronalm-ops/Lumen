package app.lumen.player.ui

import android.app.Activity
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi

class RootActions(
    val addFolder: () -> Unit,
    val openUpdate: () -> Unit,
    val openGitHub: () -> Unit,
)

/**
 * Wires the ViewModel to the screens. Home, Library and Settings are light (black with Night);
 * Now Playing is always black and sits over everything while open.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LumenRoot(vm: MainViewModel, actions: RootActions) {
    val context = LocalContext.current
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

    // Kids lock forces Now Playing open; nothing else can be reached until the corner is held.
    LaunchedEffect(kidsLocked) { if (kidsLocked) vm.nowPlayingOpen.value = true }

    // "Audio only": drop the video track while the surface is away, restore it when back.
    LaunchedEffect(nowPlayingOpen, audioOnly, player.isVideo, controller) {
        if (player.isVideo && controller != null) vm.setVideoTrackEnabled(nowPlayingOpen || !audioOnly)
    }

    val showingBlack = nowPlayingOpen || settings.night
    LumenTheme(dark = showingBlack) {
        SystemBars(light = !showingBlack)
        val c = Lumen.colours
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
                    ),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Crossfade(targetState = screen, animationSpec = tween(200), label = "screen") { s ->
                            when (s) {
                                Screen.Home -> HomeScreen(
                                    continueItem = continueItem,
                                    player = player,
                                    labels = labels,
                                    recent = recent,
                                    scanning = scanning,
                                    update = update,
                                    onSettings = { vm.screen.value = Screen.Settings },
                                    onResume = vm::resume,
                                    onLabel = vm::playLabel,
                                    onLabelLongPress = { vm.openLabelSheet(LabelSheetTarget.EditLabel(it)) },
                                    onItem = { item -> vm.play(item, recent) },
                                    onItemLongPress = { vm.openLabelSheet(LabelSheetTarget.ForItem(it)) },
                                    onUpdate = actions.openUpdate,
                                    onOpenSettingsFolders = { vm.screen.value = Screen.Settings },
                                )
                                Screen.Library -> LibraryScreen(
                                    items = items,
                                    folders = folders,
                                    labels = labels,
                                    player = player,
                                    scanning = scanning,
                                    search = search,
                                    sortByName = sortByName,
                                    folderFilter = folderFilter,
                                    onSearch = { vm.librarySearch.value = it },
                                    onToggleSort = { vm.librarySortByName.value = !vm.librarySortByName.value },
                                    onFolder = { vm.libraryFolder.value = it },
                                    onItem = { item, queue -> vm.play(item, queue) },
                                    onItemLongPress = { vm.openLabelSheet(LabelSheetTarget.ForItem(it)) },
                                    onAddFolder = actions.addFolder,
                                )
                                Screen.Settings -> SettingsScreen(
                                    settings = settings,
                                    folders = folders,
                                    folderCounts = items.groupingBy { it.media.folderUri }.eachCount(),
                                    scanning = scanning,
                                    update = update,
                                    lastSaved = lastSaved,
                                    actions = SettingsActions(
                                        back = { vm.screen.value = Screen.Home },
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
                                    ),
                                )
                            }
                        }
                    }
                    if (player.hasItem && screen == Screen.Library) {
                        val remaining = if (player.durationMs > 0) "-" + formatTime((player.durationMs - player.positionMs).coerceAtLeast(0L)) else "--:--"
                        MiniBar(
                            title = player.title,
                            meta = "${formatTime(player.positionMs)} · $remaining · ${speedLabel(player.speed)}",
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
                    onToggle = vm::toggleLabelOnItem,
                    onSave = { vm.saveLabel(it) },
                    onDelete = vm::deleteLabel,
                    onDismiss = vm::closeLabelSheet,
                )
            }
        }
    }
}

/** Dark status bar icons on light screens, light icons on black. */
@Composable
private fun SystemBars(light: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
    }
}
