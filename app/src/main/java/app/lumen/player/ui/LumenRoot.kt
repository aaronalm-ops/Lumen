package app.lumen.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator
import app.lumen.player.data.Appearance
import app.lumen.player.data.MediaRepository
import app.lumen.player.diag.RingLog
import app.lumen.player.remote.isYtm
import kotlinx.coroutines.CancellationException

class RootActions(
    val addFolder: () -> Unit,
    /** Runs the whole-phone scan, asking for the media permission first when it is not held. */
    val scanPhone: () -> Unit,
    /** The app's page in system settings, for a permission that was refused. */
    val openAppSettings: () -> Unit,
    val openUpdate: () -> Unit,
    val openGitHub: () -> Unit,
    // Phase 1b.
    /**
     * Authorises drive.readonly and drive.file (the Google sheet only when the grant is missing
     * or lacks drive.readonly) and opens Lumen's Drive folder browser.
     */
    val connectDrive: () -> Unit,
    /** "Sign in to Drive again" and "Reconnect": the authorisation alone, no browser. */
    val driveSignIn: () -> Unit,
    /** ACTION_CREATE_DOCUMENT for the backup file. */
    val backupNow: () -> Unit,
    /** ACTION_OPEN_DOCUMENT for a backup to merge. */
    val restoreBackup: () -> Unit,
    /** 0.5.0: Settings, Notification access, for the YouTube Music remote. Only from its Settings row. */
    val openNotificationAccess: () -> Unit,
    /** 0.6.0: Google AI Studio's key page, in the browser. */
    val openGeminiKeyPage: () -> Unit,
    /** 0.6.0: asks once for notifications (Android 13 and later) so the notes job can show progress. */
    val requestNotifications: () -> Unit,
    /** 0.8.1: the Google sheet for reading YouTube playlists. */
    val youtubeSignIn: () -> Unit = {},
)

/**
 * Wires the ViewModel to the screens. Home, Library and Settings follow Appearance (0.4.0:
 * Automatic follows the phone's dark theme, or Light, or Dark); Now Playing is always black.
 *
 * Phones keep one pane: Now Playing sits over everything while open. From 600 dp of window width
 * (WindowSizeClass medium, androidx.window) the layout is two panes, as in the mockup's foldable
 * frame: a two-icon rail and the Library side on the left, a hairline, and Now Playing on the
 * right, always shown. A half-opened fold held flat (tabletop) is passed to Now Playing, which
 * splits at the hinge. Kids lock always takes the whole window.
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
    val timeline by vm.timeline.collectAsState()
    val chapterSheetAt by vm.chapterSheetAt.collectAsState()
    val audioOnly by vm.audioOnly.collectAsState()
    val labelSheet by vm.labelSheet.collectAsState()
    val update by vm.update.collectAsState()
    val lastSaved by vm.lastSaved.collectAsState()
    val search by vm.librarySearch.collectAsState()
    val sortByName by vm.librarySortByName.collectAsState()
    val folderFilter by vm.libraryFolder.collectAsState()
    val librarySource by vm.librarySource.collectAsState()
    val librarySubfolder by vm.librarySubfolder.collectAsState()
    val driveScanning by vm.driveScanning.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val unreachable by vm.unreachable.collectAsState()
    val backupResult by vm.backupResult.collectAsState()
    val backupBusy by vm.backupBusy.collectAsState()
    val driveBrowser by vm.driveBrowser.collectAsState()
    val castAvailable by vm.castAvailable.collectAsState()
    val remote by vm.remote.collectAsState()
    val ytmAccess by vm.ytmAccess.collectAsState()
    val notesItem by vm.notesItem.collectAsState()
    val openNotes by vm.openNotes.collectAsState()
    val openSegments by vm.openSegments.collectAsState()
    val notesByUri by vm.notesByUri.collectAsState()
    val notesQuery by vm.notesQuery.collectAsState()
    val nowNotes by vm.nowNotes.collectAsState()
    val playlistEntries by vm.playlistEntries.collectAsState()
    val openPlaylist by vm.openPlaylist.collectAsState()
    val playlistPicker by vm.playlistPicker.collectAsState()
    val ytImport by vm.ytImport.collectAsState()
    val libraryUnplayed by vm.libraryUnplayed.collectAsState()
    val sermonSearch by vm.sermonSearch.collectAsState()
    val sermonsUnplayed by vm.sermonsUnplayed.collectAsState()
    val sermonHits by vm.sermonHits.collectAsState()
    val nowSegments by vm.nowSegments.collectAsState()
    val justMarked by vm.justMarked.collectAsState()
    val lastRemoved by vm.lastRemoved.collectAsState()
    val geminiKeyHint by vm.geminiKeyHint.collectAsState()
    val transcriptHits by vm.transcriptHits.collectAsState()

    // Kids lock forces Now Playing open; nothing else can be reached until the corner is held.
    LaunchedEffect(kidsLocked) { if (kidsLocked) vm.nowPlayingOpen.value = true }

    val activity = LocalView.current.context.findActivity()
    val wide = rememberWideWindow(activity)
    val hinge = rememberTabletopHinge(activity)
    val twoPane = wide && !kidsLocked

    // "Audio only": drop the video track while the surface is away, restore it when back. In two
    // panes the surface is always on screen.
    LaunchedEffect(nowPlayingOpen, audioOnly, player.isVideo, controller, twoPane) {
        if (player.isVideo && controller != null) vm.setVideoTrackEnabled(nowPlayingOpen || twoPane || !audioOnly)
    }

    // Appearance: Automatic reads the system dark theme from the configuration (uiMode).
    val systemDark = isSystemInDarkTheme()
    val appearanceDark = when (settings.appearance) {
        Appearance.DARK -> true
        Appearance.LIGHT -> false
        else -> systemDark
    }

    val nowPlayingActions = NowPlayingActions(
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
        toggleLayout = vm::toggleLayoutOverride,
        unlockKids = vm::unlockKids,
        setAudioOnly = { vm.audioOnly.value = it },
        openLabelSheet = { currentItem?.let { vm.openLabelSheet(LabelSheetTarget.ForItem(it)) } },
        toggleOffline = { currentItem?.let { vm.toggleOffline(it) } },
        openChapterSheet = vm::openChapterSheet,
        deleteMark = vm::deleteMark,
        exportNotes = { vm.exportNotes(context) },
        setLevelOutVoices = vm::setLevelOutVoices,
        setTrimSilences = vm::setTrimSilences,
        openYouTubeMusic = vm::openYouTubeMusic,
        openNotes = { currentItem?.let { vm.openNotesFor(it) } },
        retry = vm::retryPlayback,
        markNow = vm::markNow,
        nameMark = vm::nameMark,
        nameBookmark = vm::nameBookmark,
        removeMark = vm::removeMark,
        undoRemove = vm::undoRemove,
        newChapter = vm::newChapterHere,
        writeNotes = {
            currentItem?.let {
                actions.requestNotifications()
                vm.writeNotes(it)
            }
        },
        setUpNotes = {
            vm.nowPlayingOpen.value = false
            vm.screen.value = Screen.Settings
        },
        setSpeed = vm::setSpeed,
        setSleepMinutes = vm::setSleepMinutes,
        sleepAtEnd = vm::sleepAtEnd,
        setPreset = vm::setPreset,
        queue = vm::queueSnapshot,
        jumpToQueue = vm::jumpToQueue,
        remotePrevious = { vm.remoteSkip(forward = false) },
        remoteNext = { vm.remoteSkip(forward = true) },
    )

    val nowPlaying: @Composable (Boolean) -> Unit = { paned ->
        NowPlayingScreen(
            player = player,
            controller = controller,
            item = currentItem,
            labels = currentLabels,
            settings = settings,
            speech = speech,
            kidsLocked = kidsLocked,
            noInterruptions = noInterruptions,
            timeline = timeline,
            audioOnly = audioOnly,
            downloadProgress = currentItem?.let { downloads[it.uri] },
            actions = nowPlayingActions,
            paned = paned,
            hingeInWindow = hinge,
            castAvailable = castAvailable,
            remote = remote,
            extras = SermonExtras(
                notes = nowNotes,
                segments = nowSegments,
                notesBlocker = currentItem?.let { vm.notesBlocker(it) },
                notesAvailable = currentItem?.let { !it.media.isYtm && !it.media.isVideo } ?: false,
                justMarked = justMarked,
                lastRemoved = lastRemoved,
                noInterruptions = noInterruptions,
            ),
        )
    }

    val screens: @Composable () -> Unit = {
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
                    settings = settings,
                    source = librarySource,
                    scanning = scanning,
                    driveScanning = driveScanning,
                    mediaAccessDenied = mediaAccessDenied,
                    ytmAccess = ytmAccess,
                    unreachable = unreachable,
                    downloads = downloads,
                    search = search,
                    sortByName = sortByName,
                    folderFilter = folderFilter,
                    subfolder = librarySubfolder,
                    unplayedOnly = libraryUnplayed,
                    onToggleUnplayed = { vm.libraryUnplayed.value = !vm.libraryUnplayed.value },
                    transcriptHits = transcriptHits,
                    onHit = { hit -> vm.playAt(hit.item, hit.ms) },
                    onHitLongPress = { hit -> vm.openNotesFor(hit.item, search) },
                    onSearch = { vm.librarySearch.value = it },
                    onToggleSort = { vm.librarySortByName.value = !vm.librarySortByName.value },
                    onItem = { item, queue -> vm.play(item, queue) },
                    onItemLongPress = { vm.openLabelSheet(LabelSheetTarget.ForItem(it)) },
                    actions = LibraryActions(
                        onSource = vm::setLibrarySource,
                        onFolder = { vm.libraryFolder.value = it },
                        onSubfolder = { vm.librarySubfolder.value = it },
                        onScanPhone = actions.scanPhone,
                        onAddFolder = actions.addFolder,
                        onOpenAppSettings = actions.openAppSettings,
                        onChooseDriveFolder = actions.connectDrive,
                        onRescanDrive = vm::rescanDrive,
                        onDriveSignIn = actions.driveSignIn,
                        onOpenYouTubeMusic = vm::openYouTubeMusic,
                        onAllowYouTubeMusic = actions.openNotificationAccess,
                    ),
                )
                Screen.Sermons -> SermonsScreen(
                    items = items,
                    labels = labels,
                    notes = notesByUri,
                    player = player,
                    search = sermonSearch,
                    unplayedOnly = sermonsUnplayed,
                    hits = sermonHits,
                    unreachable = unreachable,
                    onSearch = { vm.sermonSearch.value = it },
                    onToggleUnplayed = { vm.sermonsUnplayed.value = !vm.sermonsUnplayed.value },
                    onPlay = { item, queue -> vm.play(item, queue) },
                    onLongPress = { vm.openLabelSheet(LabelSheetTarget.ForItem(it)) },
                    onNotes = { item, q -> vm.openNotesFor(item, q) },
                    onHit = { hit -> vm.playAt(hit.item, hit.ms) },
                )
                Screen.Playlists -> PlaylistsScreen(
                    labels = labels,
                    entries = playlistEntries,
                    items = items,
                    player = player,
                    openId = openPlaylist,
                    picker = playlistPicker,
                    actions = PlaylistActions(
                        open = { vm.openPlaylist.value = it },
                        newPlaylist = { vm.openLabelSheet(LabelSheetTarget.EditLabel(null)) },
                        edit = { vm.openLabelSheet(LabelSheetTarget.EditLabel(it)) },
                        play = { label, from, shuffle -> vm.playPlaylist(label, from, shuffle) },
                        reorder = vm::reorderPlaylist,
                        remove = vm::removeFromPlaylist,
                        openPicker = { vm.playlistPicker.value = it },
                        toggleMember = vm::togglePlaylistMember,
                        back = { vm.playlistBack() },
                        importYouTube = { vm.openYtImport() },
                    ),
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
                    backupResult = backupResult,
                    backupBusy = backupBusy,
                    ytmAccess = ytmAccess,
                    notesKeyHint = geminiKeyHint,
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
                        setAppearance = vm::setAppearance,
                        shareDiagnostics = { vm.shareDiagnostics(context) },
                        openUpdate = { if (update != null) actions.openUpdate() else vm.checkUpdateNow(context) },
                        openGitHub = actions.openGitHub,
                        connectDrive = actions.connectDrive,
                        driveSignIn = actions.driveSignIn,
                        rescanDrive = vm::rescanDrive,
                        disconnectDrive = vm::disconnectDrive,
                        setMobileDownloads = vm::setMobileDownloads,
                        setStopAfterSermon = vm::setStopAfterSermon,
                        setDriveAutoKeep = vm::setDriveAutoKeep,
                        backupNow = actions.backupNow,
                        restoreBackup = actions.restoreBackup,
                        setLevelOutVoices = vm::setLevelOutVoices,
                        setTrimSilences = vm::setTrimSilences,
                        setCrossfadeSeconds = vm::setCrossfadeSeconds,
                        openNotificationAccess = actions.openNotificationAccess,
                        saveGeminiKey = vm::saveGeminiKey,
                        openGeminiKeyPage = actions.openGeminiKeyPage,
                    ),
                )
                Screen.DriveBrowser -> DriveBrowserScreen(
                    state = driveBrowser,
                    actions = DriveBrowserActions(
                        back = vm::driveBrowserBack,
                        open = vm::driveBrowserOpen,
                        jump = vm::driveBrowserJump,
                        retry = vm::driveBrowserRetry,
                        count = vm::driveBrowserCount,
                        use = vm::driveBrowserUse,
                    ),
                )
            }
        }
    }

    // The label sheet takes the ground it was opened over: black over a single-pane Now Playing.
    val sheetDark = appearanceDark || (!twoPane && nowPlayingOpen)

    Box(Modifier.fillMaxSize()) {
        if (twoPane) {
            // Rail and Library side together: 45 percent, 300 to 440 dp, always leaving Now Playing
            // 320 dp (its speech transport needs 304 dp with the gutters).
            val windowWidth = LocalConfiguration.current.screenWidthDp.toFloat()
            val leftWidth = (windowWidth * 0.45f).coerceIn(300f, 440f).coerceAtMost(windowWidth - 320f).dp
            Row(Modifier.fillMaxSize()) {
                LumenTheme(dark = appearanceDark) {
                    val c = Lumen.colours
                    SystemBars(light = !appearanceDark, background = c.base)
                    Row(Modifier.width(leftWidth).fillMaxHeight().background(c.base)) {
                        NavRail(current = screen) { vm.screen.value = it }
                        // No bottom bar here, so the screens keep clear of the navigation bar themselves.
                        Box(Modifier.fillMaxSize().navigationBarsPadding()) { screens() }
                    }
                }
                LumenTheme(dark = true) {
                    val c = Lumen.colours
                    Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
                    Box(Modifier.fillMaxSize().background(c.base)) { nowPlaying(true) }
                }
            }
            BackHandler(enabled = screen != Screen.Home) {
                when {
                    screen == Screen.DriveBrowser -> vm.driveBrowserBack()
                    screen == Screen.Library && vm.libraryBack() -> Unit
                    screen == Screen.Playlists && vm.playlistBack() -> Unit
                    else -> vm.screen.value = Screen.Home
                }
            }
        } else {
            // Kids lock shows Now Playing (its lock screen) at once: the effect above opens it a
            // frame later, and leaving two panes for Kids lock must not flash Home in between.
            val showNowPlaying = nowPlayingOpen || kidsLocked
            val showingBlack = showNowPlaying || appearanceDark
            LumenTheme(dark = showingBlack) {
                val c = Lumen.colours
                SystemBars(light = !showingBlack, background = c.base)
                Box(Modifier.fillMaxSize().background(c.base)) {
                    if (showNowPlaying) {
                        nowPlaying(false)
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f).fillMaxWidth()) { screens() }
                            if (player.hasItem && (screen == Screen.Library || screen == Screen.Playlists || screen == Screen.Sermons)) {
                                // 0.5.0: in remote mode the bar shows YouTube Music's own title and time.
                                val r = remote
                                val positionMs = r?.positionMs ?: player.positionMs
                                val durationMs = r?.durationMs ?: player.durationMs
                                val remaining = if (durationMs > 0) "-" + formatTime((durationMs - positionMs).coerceAtLeast(0L)) else "--:--"
                                MiniBar(
                                    title = r?.title ?: player.title,
                                    time = formatTime(positionMs),
                                    meta = when {
                                        player.error != null -> player.error ?: ""
                                        r != null -> "$remaining · YouTube Music"
                                        else -> "$remaining · ${speedLabel(player.speed)}"
                                    },
                                    isPlaying = r?.isPlaying ?: player.isPlaying,
                                    onOpen = { vm.nowPlayingOpen.value = true },
                                    onPlayPause = vm::playPause,
                                )
                            }
                            if (screen != Screen.Settings && screen != Screen.DriveBrowser) {
                                BottomBar(current = screen) { vm.screen.value = it }
                            }
                        }
                        // The Drive browser goes up a level (and from its top level back to Settings).
                        BackHandler(enabled = screen != Screen.Home) {
                            when {
                                screen == Screen.DriveBrowser -> vm.driveBrowserBack()
                                screen == Screen.Library && vm.libraryBack() -> Unit
                                screen == Screen.Playlists && vm.playlistBack() -> Unit
                                else -> vm.screen.value = Screen.Home
                            }
                        }
                    }
                }
            }
        }

        labelSheet?.let { target ->
            // Re-resolve the item so the ticks follow the live label links.
            val live = when (target) {
                is LabelSheetTarget.ForItem -> LabelSheetTarget.ForItem(items.firstOrNull { it.uri == target.item.uri } ?: target.item)
                is LabelSheetTarget.EditLabel -> target
            }
            LumenTheme(dark = sheetDark) {
                LabelSheet(
                    target = live,
                    labels = labels,
                    downloadProgress = (live as? LabelSheetTarget.ForItem)?.let { downloads[it.item.uri] },
                    onToggle = vm::toggleLabelOnItem,
                    onSave = { vm.saveLabel(it) },
                    onDelete = vm::deleteLabel,
                    onToggleOffline = vm::toggleOffline,
                    onDismiss = vm::closeLabelSheet,
                    onTogglePlayed = vm::togglePlayed,
                )
            }
        }

        // 0.4.0: the chapter sheet, over Now Playing, so always on black.
        chapterSheetAt?.let { at ->
            LumenTheme(dark = true) {
                ChapterSheet(
                    positionMs = at,
                    itemTitle = player.title,
                    onSave = vm::saveChapter,
                    onDismiss = vm::closeChapterSheet,
                )
            }
        }

        // 0.6.0: a recording's notes and transcript, over everything, in the chosen appearance.
        val notesFor = notesItem
        if (notesFor != null && !kidsLocked) {
            // 0.7.0: opened from Now Playing it stays on black, as the red team asked.
            val notesDark = appearanceDark || nowPlayingOpen || twoPane
            LumenTheme(dark = notesDark) {
                val c = Lumen.colours
                SystemBars(light = !notesDark, background = c.base)
                NotesScreen(
                    item = notesFor,
                    notes = openNotes,
                    segments = openSegments,
                    blocker = vm.notesBlocker(notesFor),
                    playingMs = if (player.hasItem && player.mediaId == notesFor.uri) player.positionMs else null,
                    initialQuery = notesQuery,
                    onClose = vm::closeNotes,
                    onWrite = {
                        actions.requestNotifications()
                        vm.writeNotes(notesFor)
                    },
                    onPlayAt = { ms -> vm.playAt(notesFor, ms) },
                    onSetUp = {
                        vm.closeNotes()
                        vm.nowPlayingOpen.value = false
                        vm.screen.value = Screen.Settings
                    },
                )
            }
        }

        // 0.8.1: the YouTube playlist song picker, over everything.
        ytImport?.let { st ->
            LumenTheme(dark = appearanceDark) {
                val c = Lumen.colours
                SystemBars(light = !appearanceDark, background = c.base)
                YtImportScreen(
                    state = st,
                    labels = labels,
                    actions = YtImportActions(
                        close = vm::closeYtImport,
                        setLink = vm::setYtImportLink,
                        load = vm::loadYtImport,
                        signIn = actions.youtubeSignIn,
                        toggle = vm::toggleYtTrack,
                        selectAll = vm::selectAllYt,
                        setTarget = vm::setYtTarget,
                        setNewName = vm::setYtNewName,
                        add = vm::addYtSelected,
                        addAsOneLink = vm::addYtAsOneLink,
                    ),
                )
            }
        }

        // Cold start only: the lockup on black for 600 ms, then a fade. Nothing animates after.
        if (brand) BrandedSplash(onDone = { brand = false })
    }
}

/**
 * The two-pane rail (mockup, foldable frame): Home and Library as 56 by 30 dp capsules over their
 * mono labels, the same tokens as the bottom bar, then a 1 dp line on its right edge.
 */
@Composable
private fun NavRail(current: Screen, onSelect: (Screen) -> Unit) {
    val c = Lumen.colours
    Row(Modifier.fillMaxHeight()) {
        Column(
            Modifier
                .width(72.dp)
                .fillMaxHeight()
                .background(if (c.isDark) c.base else c.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RailItem("Home", LumenIcons.Home, current == Screen.Home) { onSelect(Screen.Home) }
            RailItem("Library", LumenIcons.Library, current == Screen.Library) { onSelect(Screen.Library) }
            RailItem("Sermons", LumenIcons.Speech, current == Screen.Sermons) { onSelect(Screen.Sermons) }
            RailItem("Playlists", LumenIcons.Playlist, current == Screen.Playlists) { onSelect(Screen.Playlists) }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
    }
}

@Composable
private fun RailItem(text: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val c = Lumen.colours
    Column(
        modifier = Modifier
            .size(width = 64.dp, height = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 30.dp)
                .background(if (selected) c.navSelected else Color.Transparent, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = text, tint = if (selected) c.onNavSelected else c.text3)
        }
        Spacer(Modifier.height(4.dp))
        Text(text.uppercase(), style = Lumen.type.labelS, color = if (selected) c.navSelectedLabel else c.text3)
    }
}

/** The activity behind a Compose view's context, unwrapping ContextWrappers. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * True from 600 dp of window width: the medium width class of androidx.window's WindowSizeClass
 * (BREAKPOINTS_V1), computed from the current window metrics. Recomputed on every configuration
 * change (fold, unfold, rotation, multi-window resize).
 */
@Composable
private fun rememberWideWindow(activity: Activity?): Boolean {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    return remember(activity, configuration.screenWidthDp, configuration.screenHeightDp, configuration.orientation, density) {
        // A device whose window library misbehaves falls back to the configuration's size.
        val bounds = activity?.let {
            try {
                WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(it).bounds
            } catch (t: Throwable) {
                RingLog.w("ui", "window metrics unavailable: $t")
                null
            }
        }
        val widthDp: Float
        val heightDp: Float
        if (bounds != null && density > 0f) {
            widthDp = bounds.width() / density
            heightDp = bounds.height() / density
        } else {
            widthDp = configuration.screenWidthDp.toFloat()
            heightDp = configuration.screenHeightDp.toFloat()
        }
        // 0.6.1 (red team round 2): a phone turned sideways is wide but short. Two panes need
        // height too (an unfolded foldable has both), so a landscape phone keeps one pane and a
        // video can take the whole screen.
        val sizeClass = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, heightDp)
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) &&
            sizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    }
}

/**
 * The bounds (window coordinates) of a fold that is half opened and horizontal, the tabletop
 * posture; null otherwise, and on every phone without a fold. From WindowInfoTracker's
 * WindowLayoutInfo, collected while the composition lives.
 */
@Composable
private fun rememberTabletopHinge(activity: Activity?): android.graphics.Rect? {
    val layoutInfo by produceState<WindowLayoutInfo?>(initialValue = null, activity) {
        if (activity == null) return@produceState
        try {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            RingLog.w("ui", "window layout info unavailable: $t")
        }
    }
    return layoutInfo?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull { it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL }
        ?.bounds
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
