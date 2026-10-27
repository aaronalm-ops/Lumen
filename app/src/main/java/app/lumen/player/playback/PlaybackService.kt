package app.lumen.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.lumen.player.LumenApp
import app.lumen.player.MainActivity
import app.lumen.player.data.EqPreset
import app.lumen.player.data.LabelRepository
import app.lumen.player.data.MediaEntity
import app.lumen.player.data.MediaRepository
import app.lumen.player.data.PositionStore
import app.lumen.player.data.Settings
import app.lumen.player.data.SettingsRepository
import app.lumen.player.data.drive.DriveRepository
import app.lumen.player.diag.RingLog
import app.lumen.player.widget.WidgetSnapshot
import app.lumen.player.widget.WidgetState
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The only place a player exists. The UI talks to it through a MediaController; the system
 * talks to it through the MediaSession (notification, lock screen, Bluetooth, resumption, Auto).
 * Settings reach it through the kv table: the UI writes, the service observes and applies.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val HEARTBEAT_MS = 30_000L
        private const val DESTROY_SAVE_TIMEOUT_MS = 1_500L
        private const val SLEEP_FADE_MS = 20_000L
        private const val SLEEP_FADE_STEP_MS = 500L

        /** Smooth transitions: volume step while a ramp runs, and the longest wait between checks. */
        private const val FADE_STEP_MS = 50L
        private const val FADE_IDLE_MAX_MS = 1_000L

        const val ROOT_ID = "root"
        const val CONTINUE_ID = "continue"
        const val LABELS_ID = "labels"
        const val SERMONS_ID = "sermons"
        const val RECENT_ID = "recent"
        const val LABEL_PREFIX = "label:"
    }

    /** ExoPlayer with mutable seek increments, so Settings can change them without a rebuild. */
    private class SkipPlayer(exo: ExoPlayer) : ForwardingPlayer(exo) {
        @Volatile
        var backMs: Long = 10_000L

        @Volatile
        var forwardMs: Long = 30_000L

        override fun getSeekBackIncrement(): Long = backMs

        override fun getSeekForwardIncrement(): Long = forwardMs

        override fun seekBack() {
            seekTo(currentMediaItemIndex, (currentPosition - backMs).coerceAtLeast(0L))
        }

        override fun seekForward() {
            val d = duration
            val target = currentPosition + forwardMs
            seekTo(currentMediaItemIndex, if (d == C.TIME_UNSET) target else target.coerceAtMost(d))
        }
    }

    private lateinit var exo: ExoPlayer
    private lateinit var player: SkipPlayer
    private lateinit var store: PositionStore
    private lateinit var mediaRepo: MediaRepository
    private lateinit var labelRepo: LabelRepository
    private lateinit var settingsRepo: SettingsRepository
    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var heartbeat: Job? = null
    private var sleepJob: Job? = null
    private var transitionJob: Job? = null

    /**
     * Media id of an item whose saved position is still being looked up after a transition.
     * While set, no snapshot of the player is written for that item: the player sits at 0 (or
     * wherever the queue put it) and a "ready", "isPlaying=false" or heartbeat save in that
     * window would overwrite the real position with it.
     */
    private var pendingResumeId: String? = null
    private val effects = AudioEffects()
    private var settings = Settings()

    /**
     * 0.4.0: whether the current item takes the speech layout. Drives "Level out voices" and
     * "Trim silences", which act on speech only. Set from the item's extras at once on a
     * transition and confirmed by [onNewItem] once the media row is resolved.
     */
    private val currentSpeech = MutableStateFlow(false)

    // 0.4.0, Smooth transitions: a fade through between music items using player volume only.
    // Never a second player, never on speech, voice notes, video or in Kids lock, never into
    // speech or a remote item. Every pause, seek, skip and non-automatic transition puts the
    // volume back at 1, and nothing here touches the volume while the sleep timer fades (sleep wins).
    private var fadeJob: Job? = null
    private var fadeInActive = false
    private var fadeOutActive = false

    /** Remaining time seen by the previous check; -1 when unknown (after a seek, pause or transition). */
    private var lastRemainingMs = -1L

    /** The current item may fade: local or Drive music, not a voice note or video, no Kids lock label. */
    private var itemFadeEligible = false

    /** Ids of labels with "Lock for kids", refreshed on every item; a queued item carrying one never fades. */
    private var kidsLabelIds: Set<Long> = emptySet()

    /** True while the sleep timer's own 20 s fade runs; the transition fades leave the volume alone. */
    private var sleepFading = false

    /** True while the sleep timer pauses the player itself, so that pause is not read as the user's. */
    private var sleepFiring = false

    /**
     * The fade length in ms, 0 when Smooth transitions is off. Set by its own settings collector,
     * so it is current before that collector restarts the fade loop.
     */
    private var fadeWindowMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val app = LumenApp.from(this)
        store = app.store
        mediaRepo = app.media
        labelRepo = app.labels
        settingsRepo = app.settings
        RingLog.i("service", "onCreate")

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // Phase 1b, the one change Drive streaming needs here: a data source factory that
        // keeps DefaultDataSource for local uris and routes drive:// through the cached HTTP
        // source. MediaItems still carry the drive:// uri, so the progress keys are unchanged.
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(lumenDataSourceFactory(this)))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        // A stable, explicit audio session so the effects bind before the first frame.
        val sessionId = (getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).generateAudioSessionId()
        exo.setAudioSessionId(sessionId)
        player = SkipPlayer(exo)
        player.addListener(playerListener)
        bindEffects(exo.audioSessionId)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaLibrarySession.Builder(this, player, SessionCallback())
            .setSessionActivity(sessionActivity)
            .setMediaButtonPreferences(mediaButtonPreferences(10, 30))
            .build()

        observeSettings()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        RingLog.i("service", "onDestroy")
        heartbeat?.cancel()
        sleepJob?.cancel()
        saveBlocking("destroy")
        publishWidget(forcePaused = true)
        scope.cancel()
        session?.let { s ->
            player.removeListener(playerListener)
            s.release()
            effects.release()
            exo.release()
        }
        session = null
        isRunning = false
        super.onDestroy()
    }

    // Settings observed from the kv table.

    private fun observeSettings() {
        scope.launch {
            var appliedBack = 10
            var appliedForward = 30
            settingsRepo.settings.collect { s ->
                settings = s
                player.backMs = s.skipBackSeconds * 1000L
                player.forwardMs = s.skipForwardSeconds * 1000L
                if (s.skipBackSeconds != appliedBack || s.skipForwardSeconds != appliedForward) {
                    appliedBack = s.skipBackSeconds
                    appliedForward = s.skipForwardSeconds
                    session?.setMediaButtonPreferences(mediaButtonPreferences(appliedBack, appliedForward))
                    RingLog.i("service", "skip intervals back=$appliedBack forward=$appliedForward")
                }
            }
        }
        scope.launch {
            // Level out voices joins the preset here: it applies only while a speech item is current.
            combine(
                settingsRepo.settings
                    .map { Triple(it.eqPreset, EqPreset.gains(it.eqPreset, it.eqCustom), it.levelOutVoices) }
                    .distinctUntilChanged(),
                currentSpeech,
            ) { (preset, gains, levelOut), speech -> Triple(preset, gains, levelOut && speech) }
                .distinctUntilChanged()
                .collect { (preset, gains, levelOut) ->
                    RingLog.i("fx", "preset $preset gains=$gains levelOut=$levelOut")
                    effects.apply(preset, gains, levelOut)
                }
        }
        scope.launch {
            // Trim silences: Media3's SilenceSkippingAudioProcessor, on speech items only.
            combine(settingsRepo.settings.map { it.trimSilences }.distinctUntilChanged(), currentSpeech) { trim, speech -> trim && speech }
                .distinctUntilChanged()
                .collect { skip ->
                    if (exo.skipSilenceEnabled != skip) {
                        exo.setSkipSilenceEnabled(skip)
                        RingLog.i("fx", "skip silence ${if (skip) "on" else "off"}")
                    }
                }
        }
        scope.launch {
            settingsRepo.settings.map { it.crossfadeSeconds }.distinctUntilChanged().collect { seconds ->
                fadeWindowMs = seconds * 1000L
                RingLog.i("fade", "smooth transitions ${if (seconds > 0) "$seconds s" else "off"}")
                resetFade("setting")
                restartFade()
            }
        }
        scope.launch {
            settingsRepo.settings.map { it.sleepEndAt }.distinctUntilChanged().collect { endAt -> scheduleSleep(endAt) }
        }
    }

    private fun bindEffects(sessionId: Int) {
        val engine = effects.bind(sessionId)
        scope.launch {
            if (engine.isNotEmpty() && settings.eqEngine != engine) settingsRepo.setEqEngine(engine)
        }
    }

    /** Fades volume over the last 20 s, pauses, then clears the timer. */
    private fun scheduleSleep(endAt: Long) {
        sleepJob?.cancel()
        sleepJob = null
        sleepFading = false
        sleepFiring = false
        // A timer replaced or cleared mid-fade must not leave the volume where the fade stopped.
        player.volume = 1f
        if (endAt <= 0L) return
        val now = System.currentTimeMillis()
        if (endAt <= now) {
            scope.launch { settingsRepo.clearSleep() }
            return
        }
        RingLog.i("sleep", "timer set, ends in ${(endAt - now) / 1000} s")
        sleepJob = scope.launch {
            val fadeStart = endAt - SLEEP_FADE_MS
            val wait = fadeStart - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            val steps = (SLEEP_FADE_MS / SLEEP_FADE_STEP_MS).toInt()
            // Sleep wins: from here the transition fades leave the volume to this loop.
            sleepFading = true
            for (i in 1..steps) {
                if (!isActive) return@launch
                player.volume = (1f - i.toFloat() / steps).coerceIn(0f, 1f)
                delay(SLEEP_FADE_STEP_MS)
            }
            sleepFiring = true
            player.pause()
            sleepFiring = false
            sleepFading = false
            player.volume = 1f
            RingLog.i("sleep", "timer fired, paused")
            settingsRepo.clearSleep()
        }
    }

    /** True for an item that streams: Drive or any http(s) uri. Never faded into. */
    private fun isRemote(item: MediaItem): Boolean {
        val uri = MediaItems.uriOf(item)
        return uri.startsWith(DriveRepository.URI_PREFIX) || uri.startsWith("http://") || uri.startsWith("https://")
    }

    /** Whether the item after this one may be faded into: local music with no Kids lock label. */
    private fun nextFadeEligible(): Boolean {
        if (player.repeatMode == Player.REPEAT_MODE_ONE) return false
        val index = player.nextMediaItemIndex
        if (index == C.INDEX_UNSET || index >= player.mediaItemCount) return false
        val next = player.getMediaItemAt(index)
        // An item without the speech extra is unknown here, so it is treated as speech.
        val speech = MediaItems.speechOrNull(next) ?: true
        return !speech &&
            !isRemote(next) &&
            !MediaItems.isVideo(next) &&
            !MediaItems.isVoiceNote(next) &&
            MediaItems.labelIds(next).none { it in kidsLabelIds }
    }

    /**
     * Ends any transition fade: flags cleared, the check loop stopped, and the volume back at 1
     * unless the sleep timer is fading it (sleep wins; its own end puts the volume back).
     */
    private fun resetFade(reason: String) {
        fadeJob?.cancel()
        fadeJob = null
        val wasFading = fadeInActive || fadeOutActive
        fadeInActive = false
        fadeOutActive = false
        lastRemainingMs = -1L
        if (!sleepFading && player.volume != 1f) player.volume = 1f
        if (wasFading) RingLog.i("fade", "reset on $reason")
    }

    /** Starts the check loop when a fade could happen on this item; otherwise leaves things be. */
    private fun restartFade() {
        fadeJob?.cancel()
        fadeJob = null
        if (fadeWindowMs <= 0L || !itemFadeEligible || !player.isPlaying) return
        fadeJob = scope.launch {
            while (isActive && fadeWindowMs > 0L) {
                delay(fadeTick())
            }
        }
    }

    /**
     * One check of the fade through. Fade-in runs over the first N seconds after an automatic
     * transition from a faded-out item; fade-out starts only when playback itself crosses into
     * the last N seconds (a seek into them does not fade), and only when the next item may be
     * faded into. Returns the wait before the next check: 50 ms inside a ramp, otherwise until
     * the window opens, at most a second.
     */
    private fun fadeTick(): Long {
        val window = fadeWindowMs
        if (window <= 0L) return FADE_IDLE_MAX_MS
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration
        var target = 1f
        var wait = FADE_IDLE_MAX_MS
        if (fadeInActive) {
            if (position < window) {
                target = minOf(target, position.toFloat() / window)
                wait = FADE_STEP_MS
            } else {
                fadeInActive = false
            }
        }
        if (duration != C.TIME_UNSET && duration > 0L) {
            val remaining = (duration - position).coerceAtLeast(0L)
            if (!fadeOutActive && remaining <= window && lastRemainingMs > window && nextFadeEligible()) {
                fadeOutActive = true
                RingLog.i("fade", "fading out over the last ${window / 1000} s")
            }
            lastRemainingMs = remaining
            if (fadeOutActive) {
                target = minOf(target, remaining.toFloat() / window)
                wait = FADE_STEP_MS
            } else if (remaining > window) {
                wait = minOf(wait, (remaining - window).coerceAtLeast(FADE_STEP_MS))
            }
        }
        if (!sleepFading) {
            val volume = target.coerceIn(0f, 1f)
            if (player.volume != volume) player.volume = volume
        }
        return wait
    }

    /**
     * Seek back and seek forward take the two slots the system would otherwise give to previous
     * and next, on the notification, the lock screen, Bluetooth displays and Android Auto.
     */
    private fun mediaButtonPreferences(backSeconds: Int, forwardSeconds: Int): List<CommandButton> {
        val backIcon = when (backSeconds) {
            5 -> CommandButton.ICON_SKIP_BACK_5
            15 -> CommandButton.ICON_SKIP_BACK_15
            30 -> CommandButton.ICON_SKIP_BACK_30
            else -> CommandButton.ICON_SKIP_BACK_10
        }
        val forwardIcon = when (forwardSeconds) {
            5 -> CommandButton.ICON_SKIP_FORWARD_5
            10 -> CommandButton.ICON_SKIP_FORWARD_10
            15 -> CommandButton.ICON_SKIP_FORWARD_15
            else -> CommandButton.ICON_SKIP_FORWARD_30
        }
        val back = CommandButton.Builder(backIcon)
            .setDisplayName("Back $backSeconds seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward = CommandButton.Builder(forwardIcon)
            .setDisplayName("Forward $forwardSeconds seconds")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        return listOf(back, forward)
    }

    // Progress persistence. Reads of the player must happen on the main thread, so the snapshot is
    // taken here and only the write moves to the IO dispatcher inside the store.

    private fun currentSnapshot(): Snapshot? {
        val item = player.currentMediaItem ?: return null
        val uri = MediaItems.uriOf(item)
        if (uri.isBlank()) return null
        val duration = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        val title = player.mediaMetadata.title?.toString()
            ?: item.mediaMetadata.title?.toString()
            ?: Uri.parse(uri).lastPathSegment
            ?: uri
        return Snapshot(uri, title, maxOf(0L, player.currentPosition), duration)
    }

    private data class Snapshot(val uri: String, val title: String, val positionMs: Long, val durationMs: Long)

    /** True while the current item's resume position is still being looked up. */
    private fun resumePending(): Boolean {
        val pending = pendingResumeId ?: return false
        return pending == player.currentMediaItem?.mediaId
    }

    private fun clearPending(id: String) {
        if (pendingResumeId == id) pendingResumeId = null
    }

    private fun save(reason: String) {
        if (resumePending()) {
            RingLog.i("store", "skipped $reason save: resume position still pending")
            return
        }
        val s = currentSnapshot() ?: return
        store.save(s.uri, s.title, s.positionMs, s.durationMs, reason)
    }

    private fun saveBlocking(reason: String) {
        if (resumePending()) {
            RingLog.i("store", "skipped $reason save: resume position still pending")
            return
        }
        val s = currentSnapshot() ?: return
        runBlocking {
            withTimeoutOrNull(DESTROY_SAVE_TIMEOUT_MS) {
                store.saveNow(s.uri, s.title, s.positionMs, s.durationMs, reason)
            }
        }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (player.isPlaying) {
                    save("heartbeat")
                    publishWidget()
                }
            }
        }
    }

    private fun publishWidget(forcePaused: Boolean = false) {
        val item = player.currentMediaItem
        val duration = player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        // Queued items carry the speech flag; the fallback mirrors isSpeech without the label lookup.
        val speech = when {
            item == null -> false
            else -> MediaItems.speechOrNull(item) ?: when (settings.layoutOverrides[MediaItems.uriOf(item)]) {
                "speech" -> true
                "music" -> false
                else -> duration > MediaRepository.SPEECH_THRESHOLD_MS
            }
        }
        WidgetState.publish(
            this,
            WidgetSnapshot(
                hasItem = item != null,
                title = player.mediaMetadata.title?.toString() ?: item?.mediaMetadata?.title?.toString() ?: "",
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                speed = player.playbackParameters.speed,
                isPlaying = if (forcePaused) false else player.isPlaying,
                isSpeech = speech,
            ),
        )
    }

    // Per-item behaviour on transitions: resume speech, start music at zero, pick speed and preset.

    private suspend fun resolveMedia(item: MediaItem): Pair<MediaEntity?, List<String>> {
        val uri = MediaItems.uriOf(item)
        val media = mediaRepo.byUri(uri)
        val ids = MediaItems.labelIds(item).ifEmpty { labelRepo.labelIdsFor(uri) }
        val names = ids.mapNotNull { labelRepo.byId(it)?.name }
        return media to names
    }

    private fun isSpeech(item: MediaItem, media: MediaEntity?, labelNames: List<String>): Boolean {
        MediaItems.speechOrNull(item)?.let { return it }
        val uri = MediaItems.uriOf(item)
        val override = settings.layoutOverrides[uri]
        if (media != null) return MediaRepository.isSpeech(media, labelNames, override)
        val duration = item.mediaMetadata.durationMs ?: player.duration.let { if (it == C.TIME_UNSET) 0L else it }
        return when (override) {
            "speech" -> true
            "music" -> false
            else -> labelNames.any { it.equals(LabelRepository.SERMONS, ignoreCase = true) } || duration > MediaRepository.SPEECH_THRESHOLD_MS
        }
    }

    /**
     * 0.4.0: whether the item resumes at its saved place. Speech always does; a WhatsApp voice
     * note over three minutes does too, though it plays in the music layout (item extras first,
     * then the media row, then the speech decision for an item outside the library).
     */
    private fun resumes(item: MediaItem, media: MediaEntity?, labelNames: List<String>, speech: Boolean): Boolean {
        MediaItems.resumeOrNull(item)?.let { return it }
        if (media != null) return MediaRepository.resumes(media, labelNames, settings.layoutOverrides[MediaItems.uriOf(item)])
        return speech
    }

    /** A library row as a queue item, with its speech, resume and voice-note extras. */
    private fun itemForMedia(media: MediaEntity, labelIds: List<Long>, labelNames: List<String>): MediaItem {
        val override = settings.layoutOverrides[media.uri]
        return MediaItems.forMedia(
            media,
            labelIds,
            MediaRepository.isSpeech(media, labelNames, override),
            resume = MediaRepository.resumes(media, labelNames, override),
            voiceNote = MediaRepository.isVoiceNote(media),
        )
    }

    private fun presetFor(speech: Boolean, labelNames: List<String>): String = when {
        labelNames.any { it.equals(LabelRepository.KIDS, ignoreCase = true) } -> EqPreset.KIDS
        labelNames.any { it.equals(LabelRepository.WORSHIP, ignoreCase = true) } -> EqPreset.WORSHIP
        speech || labelNames.any { it.equals(LabelRepository.SERMONS, ignoreCase = true) } -> EqPreset.SPEECH
        else -> settings.eqPresetUser
    }

    private fun onNewItem(item: MediaItem, lookUpPosition: Boolean) {
        transitionJob?.cancel()
        val id = item.mediaId
        // Set before the coroutine starts so no save can slip in between the transition and the
        // seek below. A cancelled earlier job only clears the flag for its own id.
        if (lookUpPosition) pendingResumeId = id
        // 0.4.0: the speech-only sound options follow the item at once when its extras say what it is.
        MediaItems.speechOrNull(item)?.let { currentSpeech.value = it }
        transitionJob = scope.launch {
            try {
                val (media, labelNames) = resolveMedia(item)
                if (player.currentMediaItem?.mediaId != id) return@launch
                val speech = isSpeech(item, media, labelNames)
                currentSpeech.value = speech
                // 0.4.0, item 4: a long voice note resumes like speech in the music layout, so the
                // saved-position lookup below keys on this rather than on the layout.
                val resume = resumes(item, media, labelNames, speech)
                player.setPlaybackSpeed(if (speech) settings.speed else 1.0f)
                val preset = presetFor(speech, labelNames)
                if (preset != settings.eqPreset) settingsRepo.setActivePreset(preset)
                if (lookUpPosition) {
                    val saved = if (resume) {
                        store.latestFor(MediaItems.uriOf(item), MediaItems.fingerprint(item) ?: media?.fingerprint)
                    } else {
                        null
                    }
                    if (player.currentMediaItem?.mediaId != id) return@launch
                    if (saved != null) {
                        val target = PositionStore.resumePosition(saved.positionMs)
                        RingLog.i("session", "transition resume $id at $target")
                        // The seek's own discontinuity save is skipped while the flag is set; the
                        // "resume" save below records the item as current without moving its
                        // stored position back by the rewind (see PositionStore.saveNow).
                        player.seekTo(player.currentMediaItemIndex, target)
                        clearPending(id)
                        save("resume")
                    } else {
                        // Music, or a resuming item never played: the player is at 0 and nothing
                        // stored can be lost. Write it so Continue points at this item.
                        clearPending(id)
                        save("transition")
                    }
                }
                // After the position work, so a speech item's resume seek never waits on it.
                updateFadeEligibility(item, media, speech)
                publishWidget()
            } finally {
                clearPending(id)
            }
        }
    }

    /**
     * Smooth transitions for the item just loaded. Not eligible: speech, a voice note, video, or
     * any label with Lock for kids; then any fade-in carried over from the last item stops and
     * the volume goes back to 1.
     */
    private suspend fun updateFadeEligibility(item: MediaItem, media: MediaEntity?, speech: Boolean) {
        kidsLabelIds = try {
            labelRepo.all().filter { it.lockKids }.map { it.id }.toSet()
        } catch (t: Throwable) {
            RingLog.w("fade", "label read failed: $t")
            emptySet()
        }
        if (player.currentMediaItem?.mediaId != item.mediaId) return
        val ids = MediaItems.labelIds(item).ifEmpty { labelRepo.labelIdsFor(MediaItems.uriOf(item)) }
        if (player.currentMediaItem?.mediaId != item.mediaId) return
        val voiceNote = MediaItems.isVoiceNote(item) || (media != null && MediaRepository.isVoiceNote(media))
        itemFadeEligible = !speech && !voiceNote && !MediaItems.isVideo(item) && ids.none { it in kidsLabelIds }
        if (!itemFadeEligible) {
            if (fadeInActive || fadeOutActive) resetFade("ineligible item") else if (!sleepFading && player.volume != 1f) player.volume = 1f
        }
        restartFade()
    }

    /**
     * Phase 1b: a Drive row is listed with duration 0 and learns it from the player on first
     * play. This writes the media row only; the progress key for the current item was already
     * settled by the "ready" save above, so the position promise is untouched.
     */
    private fun learnDriveDuration() {
        val item = player.currentMediaItem ?: return
        val uri = MediaItems.uriOf(item)
        if (!uri.startsWith(DriveRepository.URI_PREFIX)) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val app = LumenApp.from(this)
        app.appScope.launch {
            try {
                app.drive.setDuration(uri, duration)
            } catch (t: Throwable) {
                RingLog.w("drive", "duration write failed: $t")
            }
        }
    }

    /** Duration of the window a discontinuity left, when that window is still in the timeline. */
    private fun durationOf(info: Player.PositionInfo): Long {
        val index = info.mediaItemIndex
        val timeline = player.currentTimeline
        if (index < 0 || index >= timeline.windowCount) return 0L
        val window = timeline.getWindow(index, Timeline.Window())
        if (window.uid != info.windowUid || window.durationMs == C.TIME_UNSET) return 0L
        return window.durationMs
    }

    private val playerListener = object : Player.Listener {
        /**
         * 0.4.0. Any pause puts a transition fade back to full volume. A pause the user asked for
         * (play or pause in the app, the notification, the widget or a headset button) also
         * cancels the sleep timer; a pause from audio focus loss (a call), becoming noisy or the
         * timer itself does not.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) return
            resetFade("pause")
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST && !sleepFiring && sleepJob != null) {
                RingLog.i("sleep", "timer cancelled by a manual pause")
                scope.launch { settingsRepo.clearSleep() }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            RingLog.i("player", "isPlaying=$isPlaying")
            // Buffering also stops isPlaying; the volume stays where the fade had it until play
            // resumes or the user pauses (onPlayWhenReadyChanged above).
            if (isPlaying) restartFade() else {
                fadeJob?.cancel()
                fadeJob = null
            }
            if (isPlaying) {
                startHeartbeat()
            } else {
                heartbeat?.cancel()
                // Covers pause, audio focus loss, becoming noisy and the end of the item.
                save("isPlaying=false")
            }
            publishWidget()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // 0.4.0: a seek, skip or removal ends any transition fade before anything else here.
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                reason == Player.DISCONTINUITY_REASON_SKIP ||
                reason == Player.DISCONTINUITY_REASON_REMOVE
            ) {
                resetFade("seek")
                restartFade()
            }
            // Queue moved on (auto, next/previous, or a new queue set at the same index): keep
            // the old item's last position. A new queue gives every period a new uid, so the
            // index alone would miss the case where the replacement lands at the same index.
            val leftItem = oldPosition.mediaItemIndex != newPosition.mediaItemIndex ||
                oldPosition.periodUid != newPosition.periodUid
            if (leftItem) {
                val old = oldPosition.mediaItem ?: return
                val uri = MediaItems.uriOf(old)
                val title = old.mediaMetadata.title?.toString() ?: uri
                // The player's duration for that window, not the scanner's: the two can differ by
                // a few ms and the progress key is uri|duration. Zero lets the store write into
                // the row the uri already has.
                val duration = durationOf(oldPosition)
                store.save(uri, title, oldPosition.positionMs.coerceAtLeast(0L), duration, "left item")
                return
            }
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                save("seek")
                publishWidget()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            RingLog.i("player", "item transition reason=$reason id=${mediaItem?.mediaId}")
            // 0.4.0: only an automatic transition out of a faded item fades in; every other
            // transition (skip, new queue, repeat) starts the new item at full volume.
            val fadedOut = fadeOutActive
            fadeJob?.cancel()
            fadeJob = null
            fadeOutActive = false
            lastRemainingMs = -1L
            fadeInActive = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && fadedOut && fadeWindowMs > 0L && mediaItem != null
            if (!fadeInActive && !sleepFading && player.volume != 1f) player.volume = 1f
            if (mediaItem == null) return
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> {
                    // Start position already applied by onSetMediaItems or onPlaybackResumption.
                    save("transition")
                    onNewItem(mediaItem, lookUpPosition = false)
                }
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> onNewItem(mediaItem, lookUpPosition = true)
                else -> onNewItem(mediaItem, lookUpPosition = false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            RingLog.i("player", "state=$playbackState")
            if (playbackState == Player.STATE_READY) {
                save("ready")
                publishWidget()
                learnDriveDuration()
            }
            if (playbackState == Player.STATE_ENDED) save("ended")
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            RingLog.i("player", "audio session id=$audioSessionId")
            bindEffects(audioSessionId)
        }

        override fun onPlayerError(error: PlaybackException) {
            RingLog.e("player", "error ${error.errorCodeName}", error)
        }
    }

    private inner class SessionCallback : MediaLibrarySession.Callback {

        /** Controllers send items without a URI across the binder; put it back from the id. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { resolve(it) }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /** Same as above, but also looks up the stored position for the first item. */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resolved = mediaItems.map { resolve(it) }
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            if (resolved.isEmpty() || startPositionMs != C.TIME_UNSET) {
                future.set(MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs))
                return future
            }
            val index = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, resolved.size - 1)
            val first = resolved[index]
            val uri = MediaItems.uriOf(first)

            // The item already loaded keeps its place: the store may be up to a heartbeat behind
            // the player, and a queue that starts with the current item is a continuation.
            val current = player.currentMediaItem
            val state = player.playbackState
            if (current != null && MediaItems.uriOf(current) == uri && state != Player.STATE_IDLE && state != Player.STATE_ENDED) {
                val position = player.currentPosition.coerceAtLeast(0L)
                RingLog.i("session", "setMediaItems uri=$uri continues at $position")
                future.set(MediaSession.MediaItemsWithStartPosition(resolved, index, position))
                return future
            }

            scope.launch {
                // Speech resumes at saved minus 3 s; music starts at the top. An item without the
                // speech extra and without a media row (a Phase 0 uri) resumes, as Phase 0 did.
                // 0.4.0, item 4: the resume extra decides first, so a voice note over three
                // minutes resumes though it plays in the music layout.
                val resume = MediaItems.resumeOrNull(first) ?: MediaItems.speechOrNull(first) ?: run {
                    val (media, names) = resolveMedia(first)
                    if (media == null) true else MediaRepository.resumes(media, names, settings.layoutOverrides[uri])
                }
                val saved = if (resume) store.latestFor(uri, MediaItems.fingerprint(first)) else null
                val position = if (saved == null) C.TIME_UNSET else PositionStore.resumePosition(saved.positionMs)
                RingLog.i("session", "setMediaItems uri=$uri resume=$resume resumeAt=$position")
                future.set(MediaSession.MediaItemsWithStartPosition(resolved, index, position))
            }
            return future
        }

        /**
         * Called by the system (notification after process death, Bluetooth play, boot, Auto)
         * when there is no current item. Rebuilds the last item from the store and seeks 3 s back.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val last = store.lastItem()
                if (last == null) {
                    RingLog.w("session", "resumption requested but nothing stored")
                    future.setException(UnsupportedOperationException("Nothing to resume"))
                    return@launch
                }
                val media = mediaRepo.byUri(last.uri)
                val item = if (media != null) {
                    val ids = labelRepo.labelIdsFor(media.uri)
                    val names = ids.mapNotNull { labelRepo.byId(it)?.name }
                    itemForMedia(media, ids, names)
                } else {
                    val uri = Uri.parse(last.uri)
                    MediaItem.Builder()
                        .setMediaId(last.uri)
                        .setUri(uri)
                        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(last.title)
                                .setIsPlayable(true)
                                .setIsBrowsable(false)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                                .build(),
                        )
                        .build()
                }
                val position = PositionStore.resumePosition(last.positionMs)
                RingLog.i("session", "resumption forPlayback=$isForPlayback uri=${last.uri} at=$position")
                future.set(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, position))
            }
            return future
        }

        // Android Auto browse tree, served from Room.

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItems.folder(ROOT_ID, "Lumen"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                try {
                    val children = childrenOf(parentId)
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                } catch (t: Throwable) {
                    RingLog.e("library", "children of $parentId failed", t)
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            scope.launch {
                val item = when {
                    mediaId == ROOT_ID -> MediaItems.folder(ROOT_ID, "Lumen")
                    mediaId == CONTINUE_ID -> MediaItems.folder(CONTINUE_ID, "Continue")
                    mediaId == LABELS_ID -> MediaItems.folder(LABELS_ID, "Labels")
                    mediaId == SERMONS_ID -> MediaItems.folder(SERMONS_ID, "Sermons")
                    mediaId == RECENT_ID -> MediaItems.folder(RECENT_ID, "Recent")
                    mediaId.startsWith(LABEL_PREFIX) -> mediaId.removePrefix(LABEL_PREFIX).toLongOrNull()
                        ?.let { labelRepo.byId(it) }?.let { MediaItems.folder(mediaId, it.name) }
                    else -> mediaRepo.byUri(mediaId)?.let { itemFor(it) }
                }
                if (item == null) future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                else future.set(LibraryResult.ofItem(item, null))
            }
            return future
        }

        private suspend fun itemFor(media: MediaEntity): MediaItem {
            val ids = labelRepo.labelIdsFor(media.uri)
            val names = ids.mapNotNull { labelRepo.byId(it)?.name }
            return itemForMedia(media, ids, names)
        }

        private suspend fun childrenOf(parentId: String): List<MediaItem> = when {
            parentId == ROOT_ID -> listOf(
                MediaItems.folder(CONTINUE_ID, "Continue"),
                MediaItems.folder(LABELS_ID, "Labels"),
                MediaItems.folder(SERMONS_ID, "Sermons"),
                MediaItems.folder(RECENT_ID, "Recent"),
            )
            parentId == CONTINUE_ID -> {
                val last = store.lastItem()
                val media = last?.let { mediaRepo.byUri(it.uri) }
                if (media != null) listOf(itemFor(media)) else emptyList()
            }
            parentId == LABELS_ID -> labelRepo.all().map { MediaItems.folder(LABEL_PREFIX + it.id, it.name) }
            parentId == SERMONS_ID -> {
                val all = mediaRepo.all()
                val sermons = labelRepo.byName(LabelRepository.SERMONS)?.let { labelRepo.mediaUrisFor(it.id) }?.toSet() ?: emptySet()
                all.filter { it.durationMs > MediaRepository.SPEECH_THRESHOLD_MS || it.uri in sermons }
                    .sortedByDescending { it.dateAdded }
                    .distinctBy { it.fingerprint }
                    .map { itemFor(it) }
            }
            parentId == RECENT_ID -> {
                val rows = store.recent(8)
                rows.mapNotNull { mediaRepo.byUri(it.uri) }.distinctBy { it.fingerprint }.map { itemFor(it) }
            }
            parentId.startsWith(LABEL_PREFIX) -> {
                val id = parentId.removePrefix(LABEL_PREFIX).toLongOrNull()
                if (id == null) emptyList()
                else labelRepo.mediaUrisFor(id).mapNotNull { mediaRepo.byUri(it) }
                    .sortedByDescending { it.dateAdded }
                    .distinctBy { it.fingerprint }
                    .map { itemFor(it) }
            }
            else -> emptyList()
        }

        private fun resolve(item: MediaItem): MediaItem {
            if (item.localConfiguration != null) return item
            val uri = item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
            return item.buildUpon().setUri(uri).build()
        }
    }
}
