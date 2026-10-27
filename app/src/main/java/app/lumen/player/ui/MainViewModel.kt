package app.lumen.player.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lumen.player.LumenApp
import app.lumen.player.data.BookmarkEntity
import app.lumen.player.data.EqPreset
import app.lumen.player.data.FolderEntity
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.LabelRepository
import app.lumen.player.data.MediaEntity
import app.lumen.player.data.MediaLabelEntity
import app.lumen.player.data.MediaPermissions
import app.lumen.player.data.MediaRepository
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
import app.lumen.player.playback.MediaItems
import app.lumen.player.playback.PlaybackService
import app.lumen.player.playback.driveRowReachable
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class Screen { Home, Library, Settings, DriveBrowser }

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
)

/** What the label sheet is editing: the labels of one item, or one label's own settings. */
sealed class LabelSheetTarget {
    data class ForItem(val item: LibraryItem) : LabelSheetTarget()
    data class EditLabel(val label: LabelEntity?) : LabelSheetTarget()
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
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
    val labelSheet = MutableStateFlow<LabelSheetTarget?>(null)
    val audioOnly = MutableStateFlow(false)
    val kidsUnlocked = MutableStateFlow(false)
    val update = MutableStateFlow<UpdateInfo?>(null)
    val lastSaved: StateFlow<String> = store.lastSaved

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

    val bookmarks: StateFlow<List<BookmarkEntity>> = player.map { it.mediaId }.flatMapLatest { uri ->
        if (uri.isBlank()) flowOf(emptyList()) else mediaRepo.bookmarks(uri)
    }.state(emptyList())

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
            update.value = UpdateChecker.check(application)
        }
        // Art is read lazily for whole-phone rows: fetch it for whatever is loaded in the player.
        viewModelScope.launch {
            currentItem.map { it?.uri }.distinctUntilChanged().collect { uri ->
                if (uri != null) mediaRepo.ensureArt(uri)
            }
        }
    }

    private fun sortFolders(folders: List<FolderEntity>): List<FolderEntity> {
        val picked = folders.filter { !it.isMediaStore && !it.isDrive }.sortedBy { it.addedAt }
        val cloud = folders.filter { it.isDrive }
        val buckets = folders.filter { it.isMediaStore }.sortedBy { it.name.lowercase() }
        return picked + cloud + buckets
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
        refreshMediaAccess()
        startObservingMediaStore()
        app.connectivity.start()
        if (controllerFuture != null) return
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

    fun disconnect() {
        stopObservingMediaStore()
        app.connectivity.stop()
        ticker?.cancel()
        ticker = null
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
        )
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
        val speech = MediaRepository.isSpeech(media, names, settings.value.layoutOverrides[media.uri])
        return MediaItems.forMedia(media, ids, speech)
    }

    private fun mediaItemFor(item: LibraryItem): MediaItem {
        val names = labels.value.filter { it.id in item.labelIds }.map { it.name }
        val speech = MediaRepository.isSpeech(item.media, names, settings.value.layoutOverrides[item.uri])
        return MediaItems.forMedia(item.media, item.labelIds, speech)
    }

    /** Runs now when connected, or as soon as the controller connects. */
    private fun withController(block: (MediaController) -> Unit) {
        val c = _controller.value
        if (c != null) block(c) else pendingAction = { _controller.value?.let(block) }
    }

    // Playback actions.

    /** Plays [item] with the rest of [queue] after it (queue defaults to the item alone). */
    fun play(item: LibraryItem, queue: List<LibraryItem> = listOf(item)) {
        val start = queue.indexOfFirst { it.uri == item.uri }.coerceAtLeast(0)
        val ordered = if (start > 0) queue.drop(start) + queue.take(start) else queue
        val mediaItems = ordered.map { mediaItemFor(it) }
        kidsUnlocked.value = false
        withController { c ->
            if (c.currentMediaItem?.mediaId == item.uri) {
                // Tapping the item already loaded resumes it; it never restarts.
                playOrResume(c)
                return@withController
            }
            c.shuffleModeEnabled = false
            c.setMediaItems(mediaItems)
            c.prepare()
            c.play()
            RingLog.i("ui", "queue of ${mediaItems.size} from ${item.media.title}")
        }
        nowPlayingOpen.value = true
    }

    /**
     * Plays a file handed over by another app. A library row with the same size and name is used
     * when there is one, so the saved position, labels and speech layout apply; otherwise the
     * sender's uri plays directly (its position is still saved under that uri).
     */
    fun openExternal(uri: Uri, displayName: String, sizeBytes: Long, mime: String) {
        viewModelScope.launch {
            val base = displayName.substringBeforeLast('.')
            val match = items.value.firstOrNull { li ->
                li.uri == uri.toString() ||
                    (sizeBytes > 0 && li.media.sizeBytes == sizeBytes &&
                        (li.media.title.equals(base, ignoreCase = true) || li.media.relPath.endsWith(displayName, ignoreCase = true)))
            } ?: items.value.firstOrNull { li -> sizeBytes > 0 && li.media.sizeBytes == sizeBytes }
            if (match != null) {
                RingLog.i("ui", "open with: matched library row ${match.media.title}")
                play(match)
                return@launch
            }
            val item = MediaItem.Builder()
                .setMediaId(uri.toString())
                .setUri(uri)
                .setMimeType(mime.takeIf { it.isNotBlank() })
                .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(base).build())
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
        if (current.hasItem) {
            withController { playOrResume(it) }
            nowPlayingOpen.value = true
            return
        }
        val item = continueItem.value
        if (item != null) play(item) else withController { playOrResume(it) }
    }

    fun playPause() {
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

    fun seekBack() = withController { it.seekBack() }
    fun seekForward() = withController { it.seekForward() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun cycleSpeed() {
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
    }

    private fun closeDriveBrowser() {
        browseJob?.cancel()
        browseJob = null
        cancelCounts()
        screen.value = Screen.Settings
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

    fun setNight(on: Boolean) {
        viewModelScope.launch { settingsRepo.setNight(on) }
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
        disconnect()
        super.onCleared()
    }
}
