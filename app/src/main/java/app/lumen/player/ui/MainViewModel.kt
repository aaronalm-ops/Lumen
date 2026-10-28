package app.lumen.player.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lumen.player.LumenApp
import app.lumen.player.cast.CastAvailability
import app.lumen.player.data.BookmarkEntity
import app.lumen.player.data.ChapterEntity
import app.lumen.player.data.EqPreset
import app.lumen.player.data.FolderEntity
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.LabelRepository
import app.lumen.player.data.MediaEntity
import app.lumen.player.data.MediaLabelEntity
import app.lumen.player.data.MediaPermissions
import app.lumen.player.data.MediaRepository
import app.lumen.player.data.NotesEntity
import app.lumen.player.data.SegmentEntity
import app.lumen.player.data.ProgressEntity
import app.lumen.player.data.Settings
import app.lumen.player.data.SettingsRepository
import app.lumen.player.data.drive.DriveAuthException
import app.lumen.player.data.drive.DriveNode
import app.lumen.player.data.drive.DriveReconnectException
import app.lumen.player.data.drive.DriveRepository
import app.lumen.player.data.drive.MediaCount
import app.lumen.player.data.drive.isDrive
import app.lumen.player.data.isMediaStore
import app.lumen.player.diag.Diagnostics
import app.lumen.player.diag.RingLog
import app.lumen.player.notes.NoteMark
import app.lumen.player.notes.NotesExport
import app.lumen.player.playback.MediaItems
import app.lumen.player.playback.PlaybackService
import app.lumen.player.playback.driveRowReachable
import app.lumen.player.remote.RemoteController
import app.lumen.player.remote.YtmLink
import app.lumen.player.remote.YtmWatch
import app.lumen.player.remote.isYtm
import app.lumen.player.update.UpdateChecker
import app.lumen.player.update.UpdateInfo
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class Screen { Home, Library, Settings, DriveBrowser }

private const val KEY_LIBRARY_SOURCE = "library_source"

/** One row in Home or Library: a media file with its labels, copies and saved position. */
data class LibraryItem(
    val media: MediaEntity,
    val labelIds: List<Long>,
    val copies: Int,
    val positionMs: Long,
    val lastPlayedAt: Long,
    val folderName: String,
) {
    val uri: String get() = media.uri
}

data class PlayerState(
    val connected: Boolean = false,
    val hasItem: Boolean = false,
    val mediaId: String = "",
    val title: String = "",
    val artist: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
    val isVideo: Boolean = false,
    val nextTitle: String? = null,
    val queueSize: Int = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val ended: Boolean = false,
    /** 0.5.0: the session plays on a Cast receiver; sound settings stay on the phone. */
    val casting: Boolean = false,
    /** 0.6.1: why the current item cannot play, in plain words; null when it plays. */
    val error: String? = null,
)

/** 0.6.1 (red team round 2): a playback failure as one line the owner can act on. */
fun describePlaybackError(e: androidx.media3.common.PlaybackException, uri: String): String = when (e.errorCode) {
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        if (uri.startsWith("drive://")) "No connection. This file plays from Drive; Keep offline lets it play anywhere" else "No connection"
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This file has been moved or deleted"
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
        "Lumen can no longer open this file. Open it again from the app it came from"
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
        "Drive would not send this file. Try again, or sign in to Drive again in Settings"
    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
        "This file's format cannot be played on this phone"
    else -> "This file could not be played"
}

/**
 * 0.5.0: Now Playing's remote mode, while the current Lumen item is a YouTube Music link. With
 * [access] (Notification access) and [connected] (YouTube Music's session found) the title, artist,
 * art and position are YouTube Music's own; otherwise they are the Lumen row's and Now Playing
 * offers "Open YouTube Music".
 */
data class RemoteUi(
    val link: YtmLink,
    val access: Boolean,
    val connected: Boolean,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
)

/**
 * 0.4.0: one row of the speech Now Playing chapter list, a typed chapter or a bookmark, sorted
 * together by time. Exactly one of [chapter] and [bookmark] is set.
 */
data class TimelineMark(
    val positionMs: Long,
    val title: String,
    val chapter: ChapterEntity? = null,
    val bookmark: BookmarkEntity? = null,
) {
    val isChapter: Boolean get() = chapter != null
}

/** 0.7.0: one row of the Queue sheet. */
data class QueueEntry(val index: Int, val title: String, val current: Boolean)

/** 0.6.0: a line said in a transcribed recording that matches the Library search. */
data class TranscriptHit(val item: LibraryItem, val ms: Long, val text: String)

/** What the label sheet is editing: the labels of one item, or one label's own settings. */
sealed class LabelSheetTarget {
    data class ForItem(val item: LibraryItem) : LabelSheetTarget()
    data class EditLabel(val label: LabelEntity?) : LabelSheetTarget()
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = LumenApp.from(application)
    private val mediaRepo: MediaRepository = app.media
    private val labelRepo: LabelRepository = app.labels
    private val settingsRepo: SettingsRepository = app.settings
    private val store = app.store
    private val drive: DriveRepository = app.drive
    private val backup = app.backup

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val settings: StateFlow<Settings> = settingsRepo.settings.state(Settings())
    val labels: StateFlow<List<LabelEntity>> = labelRepo.labels.state(emptyList())
    private val links: StateFlow<List<MediaLabelEntity>> = labelRepo.links.state(emptyList())
    /** Picked folders first in the order added, then the phone's MediaStore buckets by name. */
    val folders: StateFlow<List<FolderEntity>> = mediaRepo.folders.map { sortFolders(it) }.state(emptyList())
    private val mediaRows: StateFlow<List<MediaEntity>> = mediaRepo.media.state(emptyList())
    private val progress: StateFlow<List<ProgressEntity>> = store.allFlow.state(emptyList())
    val scanning: StateFlow<Boolean> = mediaRepo.scanning
    val scanProgress: StateFlow<Int> = mediaRepo.scanProgress

    // Phase 1b: Drive, downloads, connectivity and backup.
    val driveScanning: StateFlow<Boolean> = drive.scanning
    val downloads: StateFlow<Map<String, Float>> = drive.downloads
    val online: StateFlow<Boolean> = app.connectivity.online
    val backupResult: StateFlow<String> = backup.lastResult
    val backupBusy: StateFlow<Boolean> = backup.busy

    /** Whether the whole-phone scan may run; refreshed on every start of the activity. */
    val mediaAccess = MutableStateFlow(MediaPermissions.granted(application))

    /** Set after the user asked for a scan and Android said no; cleared once access is granted. */
    val mediaAccessDenied = MutableStateFlow(false)
    private var scanPending = false

    /** All files, duplicates collapsed to the newest copy, newest first. Built off the main thread. */
    val items: StateFlow<List<LibraryItem>> = combine(mediaRows, links, progress, folders) { media, links, progress, folders ->
        buildItems(media, links, progress, folders)
    }.flowOn(Dispatchers.Default).state(emptyList())

    /**
     * Drive rows that cannot be played right now: offline, no kept copy, not fully cached.
     * Empty while online, so the cache is only consulted when the network is gone.
     */
    val unreachable: StateFlow<Set<String>> = combine(items, online, downloads) { items, online, _ ->
        if (online) emptySet()
        else items.asSequence()
            .filter { it.media.isDrive }
            .filter { !driveRowReachable(app, it.uri, it.media.offlinePath, online = false) }
            .map { it.uri }
            .toSet()
    }.flowOn(Dispatchers.IO).state(emptySet())

    /** The last eight played, from the progress table, resolved against the library. */
    val recent: StateFlow<List<LibraryItem>> = combine(items, progress, mediaRows) { items, progress, rows ->
        val fingerprintByUri = rows.associate { it.uri to it.fingerprint }
        val byFingerprint = items.associateBy { it.media.fingerprint }
        val seen = HashSet<String>()
        val out = ArrayList<LibraryItem>()
        for (p in progress) {
            val fingerprint = fingerprintByUri[p.uri] ?: continue
            if (!seen.add(fingerprint)) continue
            out += byFingerprint[fingerprint] ?: continue
            if (out.size >= 8) break
        }
        out
    }.state(emptyList())

    // Navigation and screen state.
    val screen = MutableStateFlow(Screen.Home)
    val nowPlayingOpen = MutableStateFlow(false)
    val librarySearch = MutableStateFlow("")
    val librarySortByName = MutableStateFlow(false)
    val libraryFolder = MutableStateFlow<String?>(null)

    /** 0.5.1: the Library section (All, Phone, Drive, YouTube Music, Suno), kept across launches. */
    val librarySource = MutableStateFlow(LibrarySource.ALL)

    /** 0.5.1: a first-level folder inside the Drive folder, or [DRIVE_TOP_LEVEL]. */
    val librarySubfolder = MutableStateFlow<String?>(null)

    /** Where the Drive browser goes back to from its top level. */
    private var browserReturn = Screen.Settings

    private var sourceChosen = false

    fun setLibrarySource(source: LibrarySource) {
        sourceChosen = true
        librarySource.value = source
        libraryFolder.value = null
        librarySubfolder.value = null
        viewModelScope.launch { settingsRepo.put(KEY_LIBRARY_SOURCE, source.key) }
    }

    /** Back inside the Library clears a folder first; returns false when there was none. */
    fun libraryBack(): Boolean {
        if (libraryFolder.value == null && librarySubfolder.value == null) return false
        libraryFolder.value = null
        librarySubfolder.value = null
        return true
    }
    val labelSheet = MutableStateFlow<LabelSheetTarget?>(null)
    val audioOnly = MutableStateFlow(false)
    val kidsUnlocked = MutableStateFlow(false)
    val update = MutableStateFlow<UpdateInfo?>(null)
    val lastSaved: StateFlow<String> = store.lastSaved

    // 0.5.0: Chromecast and the YouTube Music remote.

    /** A Cast device is on the network (the cast button shows). */
    val castAvailable: StateFlow<Boolean> = CastAvailability.available

    private val ytm: RemoteController = app.remote

    /** Notification access for YouTube Music, refreshed on every start of the activity. */
    val ytmAccess = MutableStateFlow(false)

    /** The YouTube Music item Lumen holds the queue on, or null. */
    private var remoteUri: String? = null

    /** Watches YouTube Music for the end of the item Lumen opened; null when it was not opened by Lumen. */
    private var ytmWatch: YtmWatch? = null

    /** True between the activity's onStart and onStop: only then may Lumen open YouTube Music. */
    private var foreground = false

    /** Ticks while a YouTube Music item is current, so the remote position moves on screen. */
    private val remoteTick = MutableStateFlow(0L)

    // Player connection.
    private val _player = MutableStateFlow(PlayerState())
    val player: StateFlow<PlayerState> = _player
    private val _controller = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = _controller
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var ticker: Job? = null
    private var pendingAction: (() -> Unit)? = null

    /** The library row for whatever the player holds, when it is a scanned file. */
    val currentItem: StateFlow<LibraryItem?> = combine(player, items, mediaRows) { p, items, rows ->
        if (!p.hasItem) null
        else items.firstOrNull { it.uri == p.mediaId }
            ?: rows.firstOrNull { it.uri == p.mediaId }?.let { row -> items.firstOrNull { it.media.fingerprint == row.fingerprint } }
    }.state(null)

    val currentLabels: StateFlow<List<LabelEntity>> = combine(currentItem, labels) { item, labels ->
        if (item == null) emptyList() else labels.filter { it.id in item.labelIds }
    }.state(emptyList())

    val currentIsSpeech: StateFlow<Boolean> = combine(player, currentItem, currentLabels, settings) { p, item, labels, s ->
        if (!p.hasItem) false
        else if (item != null) MediaRepository.isSpeech(item.media, labels.map { it.name }, s.layoutOverrides[item.uri])
        else when (s.layoutOverrides[p.mediaId]) {
            "speech" -> true
            "music" -> false
            else -> p.durationMs > MediaRepository.SPEECH_THRESHOLD_MS
        }
    }.state(false)

    val kidsLocked: StateFlow<Boolean> = combine(currentLabels, kidsUnlocked) { labels, unlocked ->
        !unlocked && labels.any { it.lockKids }
    }.state(false)

    val noInterruptions: StateFlow<Boolean> = currentLabels.map { labels -> labels.any { it.noInterruptions } }.state(false)

    /** 0.5.0: Now Playing's remote mode; null unless the current item is a YouTube Music link. */
    val remote: StateFlow<RemoteUi?> = combine(player, ytm.state, ytmAccess, remoteTick) { p, s, access, _ ->
        val link = if (p.hasItem) YtmLink.fromUri(p.mediaId) else null
        if (link == null) {
            null
        } else {
            val live = access && s.connected
            RemoteUi(
                link = link,
                access = access,
                connected = live,
                title = if (live) s.title.ifBlank { p.title } else p.title,
                artist = if (live) s.artist.ifBlank { p.artist } else p.artist,
                art = if (live) s.art else null,
                positionMs = if (live) s.positionNow(SystemClock.elapsedRealtime()) else 0L,
                durationMs = if (live) s.durationMs else 0L,
                isPlaying = live && s.isPlaying,
            )
        }
    }.state(null)

    val bookmarks: StateFlow<List<BookmarkEntity>> = player.map { it.mediaId }.distinctUntilChanged().flatMapLatest { uri ->
        if (uri.isBlank()) flowOf(emptyList()) else mediaRepo.bookmarks(uri)
    }.state(emptyList())

    /** 0.4.0: the typed chapters of the loaded item. */
    private val chapters: StateFlow<List<ChapterEntity>> = player.map { it.mediaId }.distinctUntilChanged().flatMapLatest { uri ->
        if (uri.isBlank()) flowOf(emptyList()) else mediaRepo.chapters(uri)
    }.state(emptyList())

    /** Chapters and bookmarks together by time; bookmarks keep their "Bookmark N" names. */
    val timeline: StateFlow<List<TimelineMark>> = combine(chapters, bookmarks) { chapters, bookmarks ->
        val marks = ArrayList<TimelineMark>(chapters.size + bookmarks.size)
        chapters.forEach { marks += TimelineMark(it.positionMs, it.title, chapter = it) }
        bookmarks.sortedBy { it.positionMs }.forEachIndexed { index, b ->
            marks += TimelineMark(b.positionMs, "Bookmark ${index + 1}", bookmark = b)
        }
        marks.sortedWith(compareBy<TimelineMark> { it.positionMs }.thenBy { if (it.isChapter) 0 else 1 })
    }.state(emptyList())

    /** The position a new chapter will be saved at while its sheet is open; null when closed. */
    val chapterSheetAt = MutableStateFlow<Long?>(null)

    /**
     * The item the open chapter sheet belongs to, taken with [chapterSheetAt]. A sermon can end
     * and the queue move on while the title is typed; the chapter still goes on the item it was
     * opened over, not on the next one at the old item's position.
     */
    private var chapterSheetUri: String? = null

    /**
     * What the Continue card shows. While the player holds an item the card shows that item
     * (null when it is not in the library, in which case the card reads the player directly);
     * only an empty player falls back to the most recent row. Resume then acts on what is shown.
     */
    val continueItem: StateFlow<LibraryItem?> = combine(player, currentItem, recent) { p, current, recent ->
        current ?: if (p.hasItem) null else recent.firstOrNull()
    }.state(null)

    init {
        viewModelScope.launch {
            val saved = LibrarySource.fromKey(settingsRepo.get(KEY_LIBRARY_SOURCE))
            // A share or a Drive pick that landed first wins over the remembered section.
            if (!sourceChosen) librarySource.value = saved
        }
        viewModelScope.launch {
            update.value = UpdateChecker.check(application)
        }
        // Art is read lazily for whole-phone rows: fetch it for whatever is loaded in the player.
        viewModelScope.launch {
            currentItem.map { it?.uri }.distinctUntilChanged().collect { uri ->
                if (uri != null && !YtmLink.isYtm(uri)) mediaRepo.ensureArt(uri)
            }
        }
        // 0.5.0: the YouTube Music item Lumen opened is over; the Lumen queue moves on.
        viewModelScope.launch {
            ytm.state.collect { state ->
                val watch = ytmWatch ?: return@collect
                if (watch.ended(state)) onRemoteEnded()
            }
        }
    }

    private fun sortFolders(folders: List<FolderEntity>): List<FolderEntity> {
        val picked = folders.filter { !it.isMediaStore && !it.isDrive && !it.isYtm }.sortedBy { it.addedAt }
        val cloud = folders.filter { it.isDrive }
        val youTubeMusic = folders.filter { it.isYtm }
        val buckets = folders.filter { it.isMediaStore }.sortedBy { it.name.lowercase() }
        return picked + cloud + youTubeMusic + buckets
    }

    private fun buildItems(
        media: List<MediaEntity>,
        links: List<MediaLabelEntity>,
        progress: List<ProgressEntity>,
        folders: List<FolderEntity>,
    ): List<LibraryItem> {
        val folderNames = folders.associate { it.uri to it.name }
        val labelsByUri = HashMap<String, MutableList<Long>>()
        links.forEach { labelsByUri.getOrPut(it.mediaUri) { ArrayList() }.add(it.labelId) }
        val latestByUri = HashMap<String, ProgressEntity>()
        progress.forEach { p -> if (latestByUri[p.uri] == null) latestByUri[p.uri] = p }
        val groups = media.groupBy { it.fingerprint }
        return groups.values.map { copies ->
            val played = copies.mapNotNull { latestByUri[it.uri] }.maxByOrNull { it.updatedAt }
            val representative = copies.firstOrNull { it.uri == played?.uri } ?: copies.maxByOrNull { it.dateAdded } ?: copies.first()
            val labelIds = copies.flatMap { labelsByUri[it.uri].orEmpty() }.distinct()
            LibraryItem(
                media = representative,
                labelIds = labelIds,
                copies = copies.size,
                positionMs = played?.positionMs ?: 0L,
                lastPlayedAt = played?.updatedAt ?: 0L,
                folderName = folderNames[representative.folderUri] ?: "",
            )
        }.sortedByDescending { it.media.dateAdded }
    }

    // Controller lifecycle, driven by MainActivity.onStart and onStop.

    fun connect(context: Context) {
        foreground = true
        refreshMediaAccess()
        startObservingMediaStore()
        app.connectivity.start()
        // 0.5.0: the cast button's device check, and Notification access (it may just have been
        // granted in system settings; a YouTube Music item already current is then followed).
        CastAvailability.start(context)
        ytmAccess.value = ytm.hasAccess()
        if (remoteUri != null) ytm.follow(true)
        if (controllerFuture != null) {
            // Kept connected while YouTube Music played (see disconnect): the screen ticks again.
            if (_controller.value != null && ticker == null) startTicker()
            return
        }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (controllerFuture !== future) return@addListener
                try {
                    val c = future.get()
                    c.addListener(controllerListener)
                    _controller.value = c
                    refresh(c)
                    startTicker()
                    // A YouTube Music item already holding the queue: follow it, but do not open it again.
                    c.currentMediaItem?.let { item -> MediaItems.uriOf(item).takeIf { YtmLink.isYtm(it) }?.let { enterRemote(it, open = false) } }
                    RingLog.i("ui", "controller connected")
                    val pending = pendingAction
                    pendingAction = null
                    if (pending != null) pending() else if (c.currentMediaItem == null) restoreLastItem(c)
                } catch (t: Throwable) {
                    RingLog.e("ui", "controller connection failed", t)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun disconnect() = disconnect(keepForRemote = true)

    /**
     * 0.5.0: with [keepForRemote], while a YouTube Music item Lumen opened is playing, the
     * controller stays connected after the activity stops, so the end of that item can still move
     * the Lumen queue on (Notification access needed). Everything else stops as before.
     */
    private fun disconnect(keepForRemote: Boolean) {
        foreground = false
        stopObservingMediaStore()
        app.connectivity.stop()
        ticker?.cancel()
        ticker = null
        if (keepForRemote && remoteUri != null && ytmWatch != null && _controller.value != null) {
            RingLog.i("ytm", "controller kept while YouTube Music plays")
            return
        }
        _controller.value?.let { c ->
            c.removeListener(controllerListener)
            // "Audio only when you leave" also covers leaving the app with Now Playing open:
            // the surface goes with the activity, so drop the video track before the controller
            // is released. Reconnecting re-enables it through the effect in LumenRoot.
            if (audioOnly.value && _player.value.isVideo) {
                try {
                    c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .build()
                } catch (t: Throwable) {
                    RingLog.w("ui", "audio only on leave failed: $t")
                }
            }
        }
        _controller.value = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _player.value = _player.value.copy(connected = false)
    }

    private val controllerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            _controller.value?.let { refresh(it) }
        }

        /** 0.5.0: a YouTube Music item became current (tapped, or reached in the queue): open it there. */
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val uri = mediaItem?.let { MediaItems.uriOf(it) }.orEmpty()
            if (YtmLink.isYtm(uri)) enterRemote(uri, open = true) else leaveRemote()
        }
    }

    private fun refresh(c: MediaController) {
        val item = c.currentMediaItem
        val metadata = c.mediaMetadata
        val next = if (c.hasNextMediaItem()) c.getMediaItemAt(c.nextMediaItemIndex).mediaMetadata.title?.toString() else null
        _player.value = PlayerState(
            connected = true,
            hasItem = item != null,
            mediaId = item?.let { MediaItems.uriOf(it) } ?: "",
            title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
                ?: item?.mediaMetadata?.title?.toString()
                ?: item?.localConfiguration?.uri?.lastPathSegment
                ?: item?.mediaId
                ?: "",
            artist = metadata.artist?.toString()?.takeIf { it.isNotBlank() }
                ?: metadata.albumArtist?.toString()?.takeIf { it.isNotBlank() }
                ?: "",
            positionMs = c.currentPosition.coerceAtLeast(0L),
            durationMs = c.duration.let { if (it == C.TIME_UNSET) 0L else it },
            isPlaying = c.isPlaying,
            speed = c.playbackParameters.speed,
            isVideo = MediaItems.isVideo(item),
            nextTitle = next,
            queueSize = c.mediaItemCount,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            ended = c.playbackState == Player.STATE_ENDED,
            casting = c.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE,
            error = c.playerError?.let { e -> describePlaybackError(e, item?.let { MediaItems.uriOf(it) } ?: "") },
        )
    }

    /** 0.6.1: Try again after a playback error. */
    fun retryPlayback() {
        withController { c ->
            c.prepare()
            c.play()
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                _controller.value?.let { c ->
                    _player.value = _player.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0L),
                        durationMs = c.duration.let { if (it == C.TIME_UNSET) 0L else it },
                    )
                }
                if (remoteUri != null) remoteTick.value = SystemClock.elapsedRealtime()
                delay(500)
            }
        }
    }

    /** Fresh process: load the last item paused so the title and timecode are there at once. */
    private fun restoreLastItem(c: MediaController) {
        viewModelScope.launch {
            val last = store.lastItem() ?: return@launch
            if (_controller.value !== c || c.currentMediaItem != null) return@launch
            RingLog.i("ui", "restoring last item paused: ${last.title}")
            val media = mediaRepo.byUri(last.uri)
            val item = if (media != null) mediaItemFor(media) else {
                val uri = Uri.parse(last.uri)
                MediaItem.Builder()
                    .setMediaId(last.uri)
                    .setUri(uri)
                    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(last.title).build())
                    .build()
            }
            c.setMediaItem(item)
            c.prepare()
            refresh(c)
        }
    }

    private suspend fun mediaItemFor(media: MediaEntity): MediaItem {
        val ids = labelRepo.labelIdsFor(media.uri)
        val names = ids.mapNotNull { labelRepo.byId(it)?.name }
        return queueItem(media, ids, names)
    }

    private fun mediaItemFor(item: LibraryItem): MediaItem {
        val names = labels.value.filter { it.id in item.labelIds }.map { it.name }
        return queueItem(item.media, item.labelIds, names)
    }

    /** The queue item with its 0.4.0 extras: resume (long voice notes) and voice note. */
    private fun queueItem(media: MediaEntity, labelIds: List<Long>, labelNames: List<String>): MediaItem {
        val override = settings.value.layoutOverrides[media.uri]
        return MediaItems.forMedia(
            media,
            labelIds,
            MediaRepository.isSpeech(media, labelNames, override),
            resume = MediaRepository.resumes(media, labelNames, override),
            voiceNote = MediaRepository.isVoiceNote(media),
        )
    }

    /** Runs now when connected, or as soon as the controller connects. */
    private fun withController(block: (MediaController) -> Unit) {
        val c = _controller.value
        if (c != null) block(c) else pendingAction = { _controller.value?.let(block) }
    }

    // Playback actions.

    /**
     * Plays [item] with the rest of [queue] after it (queue defaults to the item alone). 0.5.0: a
     * YouTube Music item is set as the current item without playing it here; the transition opens
     * it in YouTube Music. While casting, YouTube Music items stay out of the queue (the receiver
     * cannot play them), and tapping one opens YouTube Music on the phone.
     */
    fun play(item: LibraryItem, queue: List<LibraryItem> = listOf(item)) {
        val casting = _player.value.casting
        if (casting && item.media.isYtm) {
            YtmLink.fromUri(item.uri)?.let { ytm.open(it, comeBack = true) }
            return
        }
        val usable = if (casting) queue.filterNot { it.media.isYtm } else queue
        val start = usable.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
        val ordered = if (start > 0) usable.drop(start) + usable.take(start) else usable
        val mediaItems = ordered.map { mediaItemFor(it) }
        kidsUnlocked.value = false
        withController { c ->
            if (c.currentMediaItem?.mediaId == item.uri) {
                // Tapping the item already loaded resumes it; it never restarts. A YouTube Music
                // item opens in YouTube Music again.
                if (item.media.isYtm) enterRemote(item.uri, open = true) else playOrResume(c)
                return@withController
            }
            c.shuffleModeEnabled = false
            c.setMediaItems(mediaItems)
            if (!item.media.isYtm) {
                c.prepare()
                c.play()
            }
            RingLog.i("ui", "queue of ${mediaItems.size} from ${item.media.title}")
        }
        nowPlayingOpen.value = true
    }

    /**
     * Plays a file handed over by another app. A library row with the same size and name is used
     * when there is one, so the saved position, labels and speech layout apply; otherwise the
     * sender's uri plays directly (its position is still saved under that uri).
     */
    fun openExternal(uri: Uri, displayName: String, sizeBytes: Long, mime: String, persisted: Boolean = false) {
        viewModelScope.launch {
            val base = displayName.substringBeforeLast('.')
            // 0.6.1 (red team round 2): matched in the database, which is ready on a cold start
            // when the screen list is not; by uri, or by size together with the name. Size alone
            // is never enough (two different recordings can share a byte count).
            val rows = withContext(Dispatchers.IO) { mediaRepo.all() }
            val row = rows.firstOrNull { it.uri == uri.toString() }
                ?: rows.firstOrNull { m ->
                    sizeBytes > 0 && m.sizeBytes == sizeBytes &&
                        (m.title.equals(base, ignoreCase = true) || m.relPath.endsWith(displayName, ignoreCase = true))
                }
            if (row != null) {
                RingLog.i("ui", "open with: matched library row ${row.title}")
                val match = items.value.firstOrNull { it.uri == row.uri }
                    ?: LibraryItem(media = row, labelIds = labelRepo.labelIdsFor(row.uri), copies = 1, positionMs = 0L, lastPlayedAt = 0L, folderName = "")
                play(match)
                return@launch
            }
            val extras = android.os.Bundle().apply { putBoolean(MediaItems.EXTRA_ONE_OFF, !persisted) }
            val item = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setMimeType(mime.takeIf { it.isNotBlank() })
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(base).setExtras(extras).build())
                .build()
            kidsUnlocked.value = false
            withController { c ->
                c.shuffleModeEnabled = false
                c.setMediaItem(item)
                c.prepare()
                c.play()
            }
            nowPlayingOpen.value = true
        }
    }

    fun playLabel(label: LabelEntity) {
        val members = items.value.filter { label.id in it.labelIds }.sortedByDescending { it.media.dateAdded }
        if (members.isEmpty()) return
        val ordered = if (label.shuffleOnOpen) members.shuffled() else members
        play(ordered.first(), ordered)
    }

    /** Used by shortcuts before the UI flows have loaded: reads the label straight from Room. */
    private fun playLabelFromDb(name: String) {
        viewModelScope.launch {
            val label = labelRepo.byName(name) ?: return@launch
            val rows = labelRepo.mediaUrisFor(label.id).mapNotNull { mediaRepo.byUri(it) }
                .distinctBy { it.fingerprint }
                .sortedByDescending { it.dateAdded }
            if (rows.isEmpty()) return@launch
            val ordered = if (label.shuffleOnOpen) rows.shuffled() else rows
            val mediaItems = ordered.map { mediaItemFor(it) }
            kidsUnlocked.value = false
            withController { c ->
                c.shuffleModeEnabled = false
                c.setMediaItems(mediaItems)
                c.prepare()
                c.play()
            }
            nowPlayingOpen.value = true
        }
    }

    fun resume() {
        val current = _player.value
        if (current.hasItem && remoteLink() != null) {
            remotePlayPause()
            nowPlayingOpen.value = true
            return
        }
        if (current.hasItem) {
            withController { playOrResume(it) }
            nowPlayingOpen.value = true
            return
        }
        val item = continueItem.value
        if (item != null) play(item) else withController { playOrResume(it) }
    }

    fun playPause() {
        if (remoteLink() != null) {
            remotePlayPause()
            return
        }
        withController { c -> if (c.isPlaying) c.pause() else playOrResume(c) }
    }

    private fun playOrResume(c: MediaController) {
        if (c.currentMediaItem == null) {
            // Empty player: play() makes the session call onPlaybackResumption.
            RingLog.i("ui", "play with no item, requesting playback resumption")
            c.play()
            return
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
        c.play()
    }

    // 0.5.0: in remote mode (a YouTube Music item) the transport goes to YouTube Music.
    fun seekBack() {
        remote.value?.takeIf { it.connected }?.let { ytm.seekTo(it.positionMs - settings.value.skipBackSeconds * 1000L); return }
        withController { it.seekBack() }
    }

    fun seekForward() {
        remote.value?.takeIf { it.connected }?.let { ytm.seekTo(it.positionMs + settings.value.skipForwardSeconds * 1000L); return }
        withController { it.seekForward() }
    }

    fun seekTo(ms: Long) {
        if (remoteLink() != null) {
            ytm.seekTo(ms)
            return
        }
        withController { it.seekTo(ms) }
    }

    fun next() {
        // 0.6.1: without control of YouTube Music, Next moves Lumen's own queue on.
        if (remoteLink() != null && ytmAccess.value) {
            ytm.next()
            return
        }
        withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    }

    fun previous() {
        if (remoteLink() != null && ytmAccess.value) {
            ytm.previous()
            return
        }
        withController { it.seekToPrevious() }
    }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun cycleSpeed() {
        // 0.5.0: speed stays on the phone; the chip is disabled while casting.
        if (_player.value.casting) return
        val speeds = SettingsRepository.speeds
        val current = _player.value.speed
        val i = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        val next = speeds[(i + 1).mod(speeds.size)]
        withController { it.setPlaybackSpeed(next) }
        viewModelScope.launch { settingsRepo.setSpeed(next) }
    }

    fun cyclePreset() {
        val next = EqPreset.next(settings.value.eqPreset)
        viewModelScope.launch { settingsRepo.setPresetByUser(next) }
    }

    fun setPreset(preset: String) {
        viewModelScope.launch { settingsRepo.setPresetByUser(preset) }
    }

    fun setCustomBands(gains: List<Float>) {
        viewModelScope.launch { settingsRepo.setCustomBands(gains) }
    }

    fun cycleSleep() {
        val choices = SettingsRepository.sleepChoices
        val s = settings.value
        val current = if (s.sleepActive) s.sleepMinutes else 0
        val i = choices.indexOf(current).coerceAtLeast(0)
        val next = choices[(i + 1).mod(choices.size)]
        viewModelScope.launch { settingsRepo.setSleep(next) }
    }

    fun addBookmark() {
        val p = _player.value
        if (!p.hasItem) return
        viewModelScope.launch { mediaRepo.addBookmark(p.mediaId, p.positionMs) }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch { mediaRepo.deleteBookmark(bookmark) }
    }

    // 0.4.0: chapters and notes.

    /** Long press on the bookmark chip: the sheet opens holding the position of the press. */
    fun openChapterSheet() {
        val p = _player.value
        if (!p.hasItem || p.mediaId.isBlank()) return
        chapterSheetUri = p.mediaId
        chapterSheetAt.value = p.positionMs
    }

    fun closeChapterSheet() {
        renamingBookmarkAt = null
        chapterSheetAt.value = null
        chapterSheetUri = null
    }

    /** Saves the chapter at the position and on the item captured when the sheet opened. A blank title is refused. */
    fun saveChapter(title: String): Boolean {
        val at = chapterSheetAt.value ?: return false
        val uri = chapterSheetUri ?: return false
        val name = title.trim()
        if (name.isBlank()) return false
        val renaming = renamingBookmarkAt
        renamingBookmarkAt = null
        viewModelScope.launch {
            mediaRepo.addChapter(uri, at, name)
            // 0.7.0: a named bookmark becomes the chapter; the plain bookmark goes.
            if (renaming != null) mediaRepo.bookmarksFor(uri).filter { it.positionMs == renaming }.forEach { mediaRepo.deleteBookmark(it) }
        }
        chapterSheetAt.value = null
        chapterSheetUri = null
        return true
    }

    /** Long press on a row of the chapter list removes that chapter or bookmark. */
    fun deleteMark(mark: TimelineMark) {
        viewModelScope.launch {
            mark.chapter?.let { mediaRepo.deleteChapter(it) }
            mark.bookmark?.let { mediaRepo.deleteBookmark(it) }
        }
    }

    /** "Export notes": title, speaker, date, then every chapter and bookmark as "47:12  Title". */
    fun exportNotes(context: Context) {
        val p = _player.value
        if (!p.hasItem) return
        val item = currentItem.value
        val marks = timeline.value.map { NoteMark(it.positionMs, it.title) }
        val title = p.title.ifBlank { item?.media?.title.orEmpty() }
        val speaker = p.artist.ifBlank { item?.media?.artist.orEmpty() }
        val date = item?.media?.dateAdded ?: 0L
        viewModelScope.launch { NotesExport.share(context, title, speaker, date, marks) }
    }

    // 0.4.0: Sound.

    fun setLevelOutVoices(on: Boolean) {
        viewModelScope.launch { settingsRepo.setLevelOutVoices(on) }
    }

    fun setTrimSilences(on: Boolean) {
        viewModelScope.launch { settingsRepo.setTrimSilences(on) }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        viewModelScope.launch { settingsRepo.setCrossfadeSeconds(seconds) }
    }

    fun toggleLayoutOverride() {
        val p = _player.value
        if (!p.hasItem) return
        val next = if (currentIsSpeech.value) "music" else "speech"
        viewModelScope.launch { settingsRepo.setLayoutOverride(p.mediaId, next) }
    }

    fun unlockKids() {
        kidsUnlocked.value = true
    }

    /** "Audio only" for video: drop the video track while the surface is away. */
    fun setVideoTrackEnabled(enabled: Boolean) {
        withController { c ->
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
                .build()
        }
    }

    // Whole-phone scan and its permission.

    /** Runs the MediaStore scan in the application scope so leaving the screen does not cancel it. */
    fun scanMediaStore() {
        if (!MediaPermissions.granted(app)) return
        scanPending = false
        app.appScope.launch { mediaRepo.scanMediaStore() }
    }

    /** Result of the system dialog. A refusal shows the inline line once; the app never asks again on its own. */
    fun onMediaPermissionResult() {
        val granted = MediaPermissions.granted(app)
        mediaAccess.value = granted
        mediaAccessDenied.value = !granted
        RingLog.i("ui", "media permission ${if (granted) "granted" else "denied"}")
        if (granted) scanMediaStore() else scanPending = true
    }

    /** Called on every onStart: picks up a grant made in the system settings page. */
    private fun refreshMediaAccess() {
        val granted = MediaPermissions.granted(app)
        mediaAccess.value = granted
        if (granted) {
            mediaAccessDenied.value = false
            if (scanPending) scanMediaStore()
        }
    }

    private var mediaObserver: ContentObserver? = null
    private var observerRescan: Job? = null

    /** While the app is in the foreground, new downloads reach the library within a couple of seconds. */
    private fun startObservingMediaStore() {
        if (mediaObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                observerRescan?.cancel()
                observerRescan = viewModelScope.launch {
                    delay(2_000)
                    // A scan already running holds the lock; this one queues behind it and is a
                    // cheap no-change pass (one cursor, no file reads) when nothing is new.
                    if (MediaPermissions.granted(app)) {
                        RingLog.i("ui", "mediastore changed, rescanning")
                        app.appScope.launch { mediaRepo.scanMediaStore() }
                    }
                }
            }
        }
        try {
            val resolver = app.contentResolver
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            mediaObserver = observer
        } catch (t: Throwable) {
            RingLog.w("ui", "mediastore observer failed: $t")
        }
    }

    private fun stopObservingMediaStore() {
        observerRescan?.cancel()
        observerRescan = null
        mediaObserver?.let {
            try {
                app.contentResolver.unregisterContentObserver(it)
            } catch (t: Throwable) {
                RingLog.w("ui", "mediastore observer unregister failed: $t")
            }
        }
        mediaObserver = null
    }

    // Library and settings actions.

    fun addFolder(uri: Uri) {
        app.appScope.launch { mediaRepo.addFolder(uri) }
    }

    fun removeFolder(folder: FolderEntity) {
        app.appScope.launch { mediaRepo.removeFolder(folder.uri) }
    }

    fun rescan() {
        app.appScope.launch { mediaRepo.rescanAll() }
        if (settings.value.driveConnected) app.appScope.launch { drive.scan() }
    }

    // Phase 1b: Drive.

    /** "Use this folder" in the browser: store it and list it. */
    fun setDriveFolder(folderId: String, folderName: String) {
        app.appScope.launch { drive.connectFolder(folderId, folderName) }
    }

    /**
     * A grant came back (silently or from the sheet): clear the lines it answers and, with
     * drive.readonly held, refresh the listing (a no-op when no folder is chosen yet). [rescan]
     * is false when the browser opens next, since a new folder brings its own scan; a grant that
     * just gained drive.readonly rescans the kept folder either way.
     */
    fun onDriveSignedIn(hasReadonly: Boolean, rescan: Boolean) {
        app.appScope.launch {
            val wasMissingReadonly = settingsRepo.get(SettingsRepository.KEY_DRIVE_NEEDS_RECONNECT) == "1"
            drive.onSignedIn(hasReadonly)
            if (hasReadonly && (rescan || wasMissingReadonly)) drive.scan()
        }
    }

    // The Drive folder browser (0.3.3).

    val driveBrowser = MutableStateFlow(DriveBrowserState())
    private var browseJob: Job? = null
    private val countJobs = HashMap<String, Job>()

    /** At most four count requests in flight while a long list scrolls past. */
    private val countGate = Semaphore(4)

    /** Opens the browser at its top level (called once the grant holds drive.readonly). */
    fun openDriveBrowser() {
        if (screen.value != Screen.DriveBrowser) browserReturn = if (screen.value == Screen.Library) Screen.Library else Screen.Settings
        cancelCounts()
        driveBrowser.value = DriveBrowserState()
        screen.value = Screen.DriveBrowser
        loadDriveLevel(emptyList())
    }

    fun driveBrowserOpen(node: DriveNode) = loadDriveLevel(driveBrowser.value.path + node)

    /** A breadcrumb: -1 is the top level. */
    fun driveBrowserJump(index: Int) = loadDriveLevel(driveBrowser.value.path.take(index + 1))

    fun driveBrowserRetry() = loadDriveLevel(driveBrowser.value.path)

    /** Up one level; from the top level, back to Settings. */
    fun driveBrowserBack() {
        val path = driveBrowser.value.path
        if (path.isEmpty()) closeDriveBrowser() else loadDriveLevel(path.dropLast(1))
    }

    /** "Use this folder": only a real folder, never one of the three top-level entries. */
    fun driveBrowserUse() {
        val node = driveBrowser.value.current ?: return
        if (!node.selectable) return
        RingLog.i("drive", "folder chosen in the browser: ${node.name} (${node.id})")
        setDriveFolder(node.id, node.name)
        closeDriveBrowser()
        // 0.5.1: straight to the Drive section, where the listing shows as it runs.
        screen.value = Screen.Library
        setLibrarySource(LibrarySource.DRIVE)
    }

    private fun closeDriveBrowser() {
        browseJob?.cancel()
        browseJob = null
        cancelCounts()
        screen.value = browserReturn
    }

    private fun loadDriveLevel(path: List<DriveNode>) {
        browseJob?.cancel()
        driveBrowser.value = driveBrowser.value.copy(path = path, entries = emptyList(), loading = true, error = null)
        browseJob = viewModelScope.launch {
            try {
                val node = path.lastOrNull()
                val entries = if (node == null) drive.browseTop() else drive.browse(node)
                if (driveBrowser.value.path != path) return@launch
                driveBrowser.value = driveBrowser.value.copy(entries = entries, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                RingLog.w("drive", "browse failed: $t")
                if (driveBrowser.value.path != path) return@launch
                driveBrowser.value = driveBrowser.value.copy(loading = false, error = browseError(t))
            }
        }
    }

    /** The lazy count for one visible folder row; asked once per folder while the browser is open. */
    fun driveBrowserCount(node: DriveNode) {
        if (!node.selectable) return
        if (driveBrowser.value.counts.containsKey(node.id) || countJobs.containsKey(node.id)) return
        countJobs[node.id] = viewModelScope.launch {
            val text = try {
                countGate.withPermit { countLabel(drive.countMedia(node)) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Nothing is shown for a count that failed; the row still opens.
                RingLog.w("drive", "count failed for ${node.name}: $t")
                ""
            }
            val state = driveBrowser.value
            driveBrowser.value = state.copy(counts = state.counts + (node.id to text))
        }
    }

    private fun cancelCounts() {
        countJobs.values.forEach { it.cancel() }
        countJobs.clear()
    }

    private fun countLabel(count: MediaCount): String {
        val plus = if (count.more) "+" else ""
        return when {
            count.audio > 0 && count.video > 0 -> "${formatCount(count.audio)} audio · ${formatCount(count.video)} video$plus"
            count.audio > 0 -> "${formatCount(count.audio)}$plus audio"
            count.video > 0 -> "${formatCount(count.video)}$plus video"
            else -> ""
        }
    }

    private fun browseError(t: Throwable): String = when {
        t is DriveReconnectException -> "Reconnect Drive to see the files in your folder"
        t is DriveAuthException -> "Drive refused the request. Sign in to Drive again from Settings"
        !app.connectivity.isOnline() -> "Offline. Drive folders need a connection"
        else -> "Could not reach Drive"
    }

    fun rescanDrive() {
        app.appScope.launch { drive.scan() }
    }

    fun disconnectDrive() {
        app.appScope.launch { drive.disconnect() }
    }

    fun setMobileDownloads(allow: Boolean) {
        viewModelScope.launch { settingsRepo.setDriveMobileDownloads(allow) }
    }

    /** "Keep offline" or "Remove download" for one Drive row; runs in the app scope. */
    fun toggleOffline(item: LibraryItem) {
        if (!item.media.isDrive) return
        app.appScope.launch {
            if (item.media.offlinePath != null) drive.removeOffline(item.uri) else drive.keepOffline(item.uri)
        }
    }

    // Phase 1b: backup.

    fun backupTo(target: Uri) {
        app.appScope.launch { backup.backupTo(target) }
    }

    fun restoreFrom(source: Uri) {
        app.appScope.launch { backup.restoreFrom(source) }
    }

    fun openLabelSheet(target: LabelSheetTarget) {
        labelSheet.value = target
    }

    fun closeLabelSheet() {
        labelSheet.value = null
    }

    fun toggleLabelOnItem(item: LibraryItem, labelId: Long) {
        viewModelScope.launch { labelRepo.toggle(item.uri, labelId) }
    }

    /** Returns false when the name is blank or taken; the sheet shows the inline error. */
    suspend fun saveLabel(label: LabelEntity): Boolean = labelRepo.save(label) != null

    fun deleteLabel(label: LabelEntity) {
        viewModelScope.launch { labelRepo.delete(label.id) }
    }

    /** Appearance: "auto", "light" or "dark" for Home, Library and Settings. */
    fun setAppearance(value: String) {
        viewModelScope.launch { settingsRepo.setAppearance(value) }
    }

    fun setSkips(back: Int, forward: Int) {
        viewModelScope.launch { settingsRepo.setSkips(back, forward) }
    }

    /** The About button: opens the release when one is known, otherwise checks GitHub now. */
    fun checkUpdateNow(context: Context) {
        viewModelScope.launch { update.value = UpdateChecker.check(context, force = true) }
    }

    fun shareDiagnostics(context: Context) {
        viewModelScope.launch { Diagnostics.share(context) }
    }

    // 0.5.0: YouTube Music.

    private fun remoteLink(): YtmLink? {
        val p = _player.value
        return if (p.hasItem) YtmLink.fromUri(p.mediaId) else null
    }

    /** Play or pause in YouTube Music; without its session, open the item there instead. */
    private fun remotePlayPause() {
        val link = remoteLink() ?: return
        val state = ytm.state.value
        when {
            ytmAccess.value && state.connected && state.isPlaying -> ytm.pause()
            ytmAccess.value && state.connected -> ytm.play()
            else -> enterRemote(link.uri, open = true)
        }
    }

    /**
     * A YouTube Music item is current. Follows YouTube Music's session (with Notification access)
     * and, with [open], opens the item there: from the foreground with the brief trip out and back
     * ([RemoteController.RETURN_DELAY_MS]); from the background, where Android does not let Lumen
     * open an app, only by asking YouTube Music's session to play it, when that session allows it.
     */
    private fun enterRemote(uri: String, open: Boolean) {
        val link = YtmLink.fromUri(uri) ?: return
        remoteUri = uri
        ytmAccess.value = ytm.hasAccess()
        val following = ytm.follow(true)
        if (!open) return
        // Without access nothing can be watched, so the queue does not move on by itself.
        ytmWatch = if (following) YtmWatch(link) else null
        if (foreground) {
            ytm.open(link, comeBack = true)
        } else if (!ytm.playFromUri(link)) {
            RingLog.i("ytm", "reached ${link.uri} in the background; it opens when Lumen is next on screen")
        }
    }

    /** The queue left the YouTube Music item: stop following YouTube Music. */
    private fun leaveRemote() {
        if (remoteUri == null) return
        remoteUri = null
        ytmWatch = null
        ytm.follow(false)
    }

    /** YouTube Music finished the item Lumen opened: pause it and play the next Lumen item, if any. */
    private fun onRemoteEnded() {
        ytmWatch = null
        val c = _controller.value ?: return
        if (!c.hasNextMediaItem()) {
            RingLog.i("ytm", "item over, nothing after it in the Lumen queue")
            return
        }
        RingLog.i("ytm", "item over, Lumen moves on")
        ytm.pause()
        c.seekToNextMediaItem()
        val next = c.currentMediaItem
        if (next != null && !YtmLink.isYtm(MediaItems.uriOf(next))) {
            c.prepare()
            c.play()
        }
    }

    /** "Open YouTube Music" on Now Playing when Lumen cannot control it. */
    fun openYouTubeMusic() {
        ytm.openApp()
    }

    /** A song or playlist shared from YouTube Music: stored in the Library, then the label sheet. */
    fun addSharedYouTubeMusic(shared: YtmLink.Shared) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val media = shared.link.toMediaEntity(shared.title, shared.artist, now)
            mediaRepo.upsertYouTubeMusic(FolderEntity(uri = YtmLink.FOLDER_URI, name = YtmLink.FOLDER_NAME, addedAt = now), media)
            val stored = mediaRepo.byUri(media.uri) ?: media
            val ids = labelRepo.labelIdsFor(stored.uri)
            nowPlayingOpen.value = false
            screen.value = Screen.Library
            setLibrarySource(LibrarySource.YTM)
            labelSheet.value = LabelSheetTarget.ForItem(
                LibraryItem(media = stored, labelIds = ids, copies = 1, positionMs = 0L, lastPlayedAt = 0L, folderName = YtmLink.FOLDER_NAME),
            )
        }
    }


    // 0.6.0: sermon notes and transcript search.

    private val notesRepo = app.notesRepo

    /** The item whose notes are open, by uri; null when the notes screen is closed. */
    val notesOpen = MutableStateFlow<String?>(null)

    /** What the transcript tab opens searching for (from a Library hit). */
    val notesQuery = MutableStateFlow("")

    val notesItem: StateFlow<LibraryItem?> = combine(notesOpen, items) { uri, list ->
        if (uri == null) null else list.firstOrNull { it.uri == uri }
    }.state(null)

    val openNotes: StateFlow<NotesEntity?> = notesOpen.flatMapLatest { uri ->
        if (uri == null) flowOf(null) else notesRepo.notes(uri)
    }.state(null)

    val openSegments: StateFlow<List<SegmentEntity>> = notesOpen.flatMapLatest { uri ->
        if (uri == null) flowOf(emptyList()) else notesRepo.segments(uri)
    }.state(emptyList())

    /** Every notes row by uri, for the Notes chip's state on Now Playing. */
    val notesByUri: StateFlow<Map<String, NotesEntity>> = notesRepo.all.map { rows -> rows.associateBy { it.mediaUri } }.state(emptyMap())

    val geminiKeyHint = MutableStateFlow(notesRepo.keyHint())

    /** Lines said in any transcribed recording that match the Library search (three letters or more). */
    val transcriptHits: StateFlow<List<TranscriptHit>> = combine(librarySearch.debounce(250), items) { q, list -> q to list }
        .mapLatest { (q, list) ->
            if (q.trim().length < 3) {
                emptyList()
            } else {
                val byUri = list.associateBy { it.uri }
                notesRepo.search(q).mapNotNull { seg -> byUri[seg.mediaUri]?.let { TranscriptHit(it, seg.startMs, seg.text) } }
            }
        }
        .flowOn(Dispatchers.IO)
        .state(emptyList())

    fun openNotesFor(item: LibraryItem, query: String = "") {
        notesQuery.value = query
        notesOpen.value = item.uri
    }

    fun closeNotes() {
        notesOpen.value = null
        notesQuery.value = ""
    }

    fun notesBlocker(item: LibraryItem): String? = notesRepo.blocker(item.media)

    fun writeNotes(item: LibraryItem) {
        if (notesRepo.blocker(item.media) != null) return
        viewModelScope.launch {
            try {
                notesRepo.start(item.media)
            } catch (t: Throwable) {
                RingLog.e("notes", "could not start", t)
            }
        }
    }

    fun saveGeminiKey(key: String) {
        notesRepo.setApiKey(key)
        geminiKeyHint.value = notesRepo.keyHint()
    }

    /** Plays [item] from [ms]: a seek when it is already loaded, otherwise a queue of one starting there. */
    fun playAt(item: LibraryItem, ms: Long) {
        if (item.media.isYtm) return
        val target = ms.coerceAtLeast(0L)
        kidsUnlocked.value = false
        withController { c ->
            if (c.currentMediaItem?.mediaId == item.uri) {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.seekTo(target)
                c.play()
            } else {
                c.shuffleModeEnabled = false
                c.setMediaItems(listOf(mediaItemFor(item)), 0, target)
                c.prepare()
                c.play()
            }
            RingLog.i("notes", "play ${item.media.title} from $target")
        }
    }


    // 0.7.0: Now Playing redesign (Aaron, 2026-10-28: B for sermons, A for music).

    /** The notes and transcript of the item playing, for the sermon companion panel. */
    val nowNotes: StateFlow<NotesEntity?> = currentItem.map { it?.uri }.distinctUntilChanged().flatMapLatest { uri ->
        if (uri == null) flowOf(null) else notesRepo.notes(uri)
    }.state(null)

    val nowSegments: StateFlow<List<SegmentEntity>> = currentItem.map { it?.uri }.distinctUntilChanged().flatMapLatest { uri ->
        if (uri == null) flowOf(emptyList()) else notesRepo.segments(uri)
    }.state(emptyList())

    fun setSpeed(speed: Float) {
        if (_player.value.casting) return
        withController { it.setPlaybackSpeed(speed) }
        viewModelScope.launch { settingsRepo.setSpeed(speed) }
    }

    fun setSleepMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepo.setSleep(minutes) }
    }

    /** Sleep at the end of the item playing, at the current speed. */
    fun sleepAtEnd() {
        val p = _player.value
        if (!p.hasItem || p.durationMs <= 0) return
        val left = ((p.durationMs - p.positionMs).coerceAtLeast(0L) / p.speed.coerceAtLeast(0.5f)).toLong()
        viewModelScope.launch { settingsRepo.setSleepUntil(System.currentTimeMillis() + left + 2_000L) }
    }

    /** The moment just marked, for "Marked 47:12 · Name it"; cleared after a few seconds. */
    val justMarked = MutableStateFlow<Long?>(null)
    private var markJob: Job? = null

    fun markNow() {
        val p = _player.value
        if (!p.hasItem) return
        val at = p.positionMs
        viewModelScope.launch { mediaRepo.addBookmark(p.mediaId, at) }
        justMarked.value = at
        markJob?.cancel()
        markJob = viewModelScope.launch {
            delay(6_000)
            justMarked.value = null
        }
    }

    /** "Name it": the chapter sheet at the marked moment; the plain bookmark gives way to the chapter. */
    private var renamingBookmarkAt: Long? = null

    fun nameMark(at: Long) {
        val p = _player.value
        if (!p.hasItem) return
        renamingBookmarkAt = at
        chapterSheetUri = p.mediaId
        chapterSheetAt.value = at
        justMarked.value = null
    }

    fun nameBookmark(mark: TimelineMark) {
        val b = mark.bookmark ?: return
        renamingBookmarkAt = b.positionMs
        chapterSheetUri = b.mediaUri
        chapterSheetAt.value = b.positionMs
    }

    /** A removed chapter or bookmark, offered back as "Removed · Undo" for a few seconds. */
    val lastRemoved = MutableStateFlow<TimelineMark?>(null)
    private var removedJob: Job? = null

    fun removeMark(mark: TimelineMark) {
        deleteMark(mark)
        lastRemoved.value = mark
        removedJob?.cancel()
        removedJob = viewModelScope.launch {
            delay(6_000)
            lastRemoved.value = null
        }
    }

    fun undoRemove() {
        val mark = lastRemoved.value ?: return
        lastRemoved.value = null
        viewModelScope.launch {
            mark.chapter?.let { mediaRepo.addChapter(it.mediaUri, it.positionMs, it.title) }
            mark.bookmark?.let { mediaRepo.addBookmark(it.mediaUri, it.positionMs) }
        }
    }

    /** "New chapter here" from the More sheet. */
    fun newChapterHere() = openChapterSheet()

    /** The queue as it stands, for the Queue sheet. */
    fun queueSnapshot(): List<QueueEntry> {
        val c = _controller.value ?: return emptyList()
        return (0 until c.mediaItemCount).map { i ->
            val item = c.getMediaItemAt(i)
            QueueEntry(i, item.mediaMetadata.title?.toString()?.ifBlank { null } ?: item.mediaId.substringAfterLast('/'), i == c.currentMediaItemIndex)
        }
    }

    fun jumpToQueue(index: Int) {
        withController { c ->
            if (index in 0 until c.mediaItemCount) {
                c.seekToDefaultPosition(index)
                c.play()
            }
        }
    }

    /** Static app shortcuts: "resume", "worship", "kids". */
    fun handleShortcut(action: String?) {
        when (action) {
            "resume" -> withController { c ->
                playOrResume(c)
                nowPlayingOpen.value = true
            }
            "worship" -> playLabelFromDb(LabelRepository.WORSHIP)
            "kids" -> playLabelFromDb(LabelRepository.KIDS)
        }
    }

    override fun onCleared() {
        disconnect(keepForRemote = false)
        leaveRemote()
        super.onCleared()
    }
}
