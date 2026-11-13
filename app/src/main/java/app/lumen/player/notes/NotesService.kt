package app.lumen.player.notes

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.lumen.player.LumenApp
import app.lumen.player.MainActivity
import app.lumen.player.R
import app.lumen.player.data.MediaEntity
import app.lumen.player.data.SegmentEntity
import app.lumen.player.data.drive.DriveApi
import app.lumen.player.data.drive.DriveRepository
import app.lumen.player.diag.RingLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * 0.6.0: writes a sermon's transcript and notes, one recording at a time, as a dataSync
 * foreground service so it survives the screen going off. The steps:
 * 1. upload the recording to Gemini's Files API (from the phone, the offline copy, or straight
 *    from Drive);
 * 2. ask for a timed transcript, carrying on past the output limit when a sermon is long;
 * 3. delete the uploaded recording from Google;
 * 4. ask for the notes from the transcript text alone, so they can hold only what was said.
 * A job cut short by the process dying is marked "Stopped before it finished" at the next start.
 */
class NotesService : Service() {

    internal var createClient: (String) -> GeminiClient = { GeminiClient(it) }

    /** 0.9.0: notes are written by Claude Sonnet when an Anthropic key is saved. */
    internal var createClaude: (String) -> ClaudeClient = { ClaudeClient(it) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pending = LinkedHashSet<String>()
    private var worker: Job? = null
    @Volatile private var lastStartId = 0
    private var lastStep = ""
    private var lastNotifiedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.getStringExtra(EXTRA_URI)
        if (!goForeground(lastStep.ifBlank { "Getting ready" })) {
            if (uri != null) LumenApp.from(this).appScope.launch {
                LumenApp.from(this@NotesService).notesRepo.dao.setStatus(uri, NotesRepository.STATUS_FAILED,
                    "Android could not keep the notes job running. Keep Lumen open and try again", System.currentTimeMillis())
            }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val idle = synchronized(pending) {
            lastStartId = startId
            if (uri != null && pending.add(uri)) queue.trySend(uri)
            pending.isEmpty()
        }
        if (worker == null) {
            worker = scope.launch {
                for (next in queue) {
                    run(next)
                    // Read the newest start id under the same lock that saw the queue empty, so a
                    // job queued meanwhile is never stopped with the service.
                    val stopId = synchronized(pending) {
                        pending.remove(next)
                        if (pending.isEmpty()) lastStartId else -1
                    }
                    if (stopId >= 0) stopSelfResult(stopId)
                }
            }
        }
        if (idle) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    /** Android 15 and later: the daily allowance for this kind of job ran out. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        RingLog.w("notes", "the system ended the notes job (time limit)")
        stopSelf()
    }

    override fun onDestroy() {
        // Anything still queued when the service goes cannot carry on; say so on its row.
        val left = synchronized(pending) { pending.toList() }
        if (left.isNotEmpty()) {
            val dao = LumenApp.from(this).notesRepo.dao
            LumenApp.from(this).appScope.launch {
                left.forEach { dao.setStatus(it, NotesRepository.STATUS_FAILED, "Stopped before it finished. Tap Write again", System.currentTimeMillis()) }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 0.6.1 (red team round 2): a foreground service does not keep the CPU or Wi-Fi awake, so a
     * long upload stalled once the screen locked. Both are held for the job, with a ceiling.
     */
    private suspend fun run(uri: String) {
        val power = getSystemService(android.os.PowerManager::class.java)
        val wake = power?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Lumen:notes")
        @Suppress("DEPRECATION")
        val wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
            ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Lumen:notes")
        try {
            wake?.acquire(JOB_CEILING_MS)
            wifi?.setReferenceCounted(false)
            wifi?.acquire()
            withTimeout(JOB_CEILING_MS) { runJob(uri) }
        } catch (error: TimeoutCancellationException) {
            LumenApp.from(this).notesRepo.dao.setStatus(uri, NotesRepository.STATUS_FAILED,
                "The notes job reached its time limit. Saved transcript text is kept; try again to continue", System.currentTimeMillis())
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            LumenApp.from(this).notesRepo.dao.setStatus(uri, NotesRepository.STATUS_FAILED, reasonFor(error), System.currentTimeMillis())
        } finally {
            try { if (wifi?.isHeld == true) wifi.release() } catch (_: Throwable) {}
            try { if (wake?.isHeld == true) wake.release() } catch (_: Throwable) {}
        }
    }

    private suspend fun runJob(uri: String) {
        val app = LumenApp.from(this)
        val dao = app.notesRepo.dao
        val started = System.currentTimeMillis()
        val jobContext = currentCoroutineContext()
        suspend fun step(text: String) {
            if (!jobContext.isActive) return
            dao.setStatus(uri, NotesRepository.STATUS_WORKING, text, System.currentTimeMillis())
            notify(text)
        }
        val recovering: (GeminiClient.Retry) -> Unit = { retry -> runBlocking {
            val seconds = (retry.delayMs + 999) / 1000
            step(if (retry.switchingModel) "Google could not finish this section (HTTP ${retry.code}). Trying another model in ${seconds}s; saved transcript text is kept"
                else "Google could not finish this section (HTTP ${retry.code}). Retrying automatically in ${seconds}s; saved transcript text is kept")
        } }
        var stage = "Starting"
        try {
            val media = app.db.mediaDao().mediaByUri(uri) ?: throw IOException("This recording is no longer in the library")
            val key = app.notesRepo.apiKey()
            if (key.isBlank()) throw IOException("Add your Gemini key in Settings, Sermon notes")
            val client = createClient(key)
            RingLog.i("notes", "start: ${media.title}")

            val checkpoint = TranscriptCheckpoint(this)
            val stored = dao.segmentsFor(uri)
            val savedState = checkpoint.read(media, stored)
            val existingNotes = dao.notes(uri)
            val legacyComplete = !checkpoint.hasRecord(uri) && savedState == null && existingNotes?.sizeBytes == media.sizeBytes &&
                SermonNotes.parse(existingNotes?.json.orEmpty()) != null && stored.isNotEmpty()
            var segments = if (savedState != null || legacyComplete) stored.map { it.startMs to it.text } else emptyList()
            if (legacyComplete) checkpoint.save(media, segments, true)
            if (savedState == null && !legacyComplete) {
                dao.deleteSegments(uri)
                checkpoint.save(media, emptyList(), false)
            }
            // 0.9.0: Gemini 3.5 Transcribe in 20-minute parts first; null means that path is not
            // available for this file (format, model), and the whole-file path below runs.
            val sliced = if (savedState?.complete != true && !legacyComplete) {
                stage = "Transcription"
                transcribeSliced(app, client, media, checkpoint, segments, savedState?.slicedThroughMs ?: -1L, { step(it) }, recovering)
            } else null
            if (sliced != null) {
                segments = sliced
            } else if (savedState?.complete != true && !legacyComplete) {
                stage = "Upload"
                step("Uploading 0%" + if (segments.isNotEmpty()) "; saved transcript resumes at ${stamp(segments.last().first)}" else "")
                val source = runInterruptible { openSource(app, media) }
                var shown = -1
                val file = try {
                    runInterruptible {
                        client.upload(source.stream, source.size, mimeFor(media), media.title) { fraction ->
                            val pct = (fraction * 100).toInt()
                            if (pct >= shown + 5) { shown = pct; runBlocking { step("Uploading $pct%") } }
                        }
                    }
                } finally { source.close() }
                try {
                    stage = "Preparing recording"
                    step("Google is preparing the recording")
                    runInterruptible { client.waitUntilActive(file) { elapsed -> runBlocking { step("Google is preparing the recording; ${elapsed / 1000} seconds elapsed") } } }
                    stage = "Transcription"
                    // 0.9.0: a fresh recording short enough for one Transcribe request goes there first.
                    val direct = if (segments.isEmpty() && media.durationMs in 1..TRANSCRIBE_DIRECT_MAX_MS) {
                        step("Transcribing with Google's speech model")
                        runInterruptible { transcribeOrNull(client, file, recovering) }
                            ?.let { result -> linesFrom(result, 0L, media.durationMs) }
                            ?.takeIf { it.isNotEmpty() }
                    } else null
                    if (direct != null) {
                        dao.insertSegments(direct.map { (ms, text) -> SegmentEntity(mediaUri = uri, startMs = ms, text = text) })
                        checkpoint.save(media, direct, true)
                        step("Transcript ready; ${direct.size} lines saved")
                        segments = direct
                    } else {
                    step("Transcribing; ${segments.size} lines saved. Text appears in Transcript as it arrives")
                    segments = runInterruptible {
                        TranscriptRunner(
                            generate = { after, onText ->
                                val parts = JSONArray()
                                    .put(JSONObject().put("file_data", JSONObject().put("mime_type", file.mimeType).put("file_uri", file.uri)))
                                    .put(JSONObject().put("text", NotesPrompts.transcriptRequest(after)))
                                client.generate(GeminiClient.MODELS, NotesPrompts.TRANSCRIPT_SYSTEM, parts,
                                    NotesPrompts.transcriptSchema, maxOutputTokens = 8_192, thinking = "low", onText = onText, onRetry = recovering)
                            },
                            persist = { added, all, complete -> runBlocking {
                                if (added.isNotEmpty()) dao.insertSegments(added.map { (ms, text) -> SegmentEntity(mediaUri = uri, startMs = ms, text = text) })
                                checkpoint.save(media, all, complete)
                                step(if (complete) "Transcript ready; ${all.size} lines saved" else "Transcribing; ${all.size} lines saved through ${stamp(all.last().first)}")
                            } },
                        ).run(media.durationMs, segments)
                    }
                    }
                } finally { withContext(NonCancellable + Dispatchers.IO) { client.delete(file) } }
            } else step("Using the saved transcript; no new audio upload needed")
            if (segments.isEmpty()) throw IOException("No speech was found in this recording")
            RingLog.i("notes", "transcript: ${segments.size} lines")
            stage = "Writing notes"
            step("Writing notes from ${segments.size} saved transcript lines")
            val transcript = segments.joinToString("\n") { (ms, text) -> "[${stamp(ms)}] $text" }
            var progressAt = 0L
            val claudeKey = app.notesRepo.claudeKey()
            val reply = if (claudeKey.isNotBlank()) {
                // 0.9.0: Claude Sonnet writes the notes from the saved transcript text alone.
                step("Claude is writing notes from ${segments.size} saved transcript lines")
                runInterruptible { createClaude(claudeKey).writeNotes(
                    NotesPrompts.NOTES_SYSTEM,
                    NotesPrompts.notesRequest(transcript),
                    NotesPrompts.notesSchema,
                    onRetry = { retry -> runBlocking { step("Claude is busy (HTTP ${retry.code}). Retrying in ${(retry.delayMs + 999) / 1000}s; the transcript is saved") } },
                    onProgress = { chars ->
                        val now = System.nanoTime()
                        if (now - progressAt > 2_000_000_000L) {
                            progressAt = now
                            runBlocking { step("Claude is writing notes; $chars characters so far. The transcript is already saved") }
                        }
                    },
                ) }
            } else runInterruptible { client.generate(
                GeminiClient.MODELS,
                NotesPrompts.NOTES_SYSTEM,
                JSONArray().put(JSONObject().put("text", NotesPrompts.notesRequest(transcript))),
                NotesPrompts.notesSchema,
                maxOutputTokens = 32_768,
                thinking = "medium",
                onRetry = recovering,
                onText = { text ->
                    val now = System.nanoTime()
                    if (now - progressAt > 2_000_000_000L) {
                        progressAt = now
                        runBlocking { step("Writing notes; ${text.length} characters received. The transcript is already saved") }
                    }
                },
            ) }
            val parsed = SermonNotes.parse(reply.text)
            if (parsed == null || parsed.sections.isEmpty()) throw IOException("The notes came back unreadable. Try again")
            val row = dao.notes(uri)
            val now = System.currentTimeMillis()
            dao.upsert(
                (row ?: return).copy(
                    status = NotesRepository.STATUS_DONE,
                    step = "",
                    json = reply.text,
                    model = reply.model,
                    updatedAt = now,
                ),
            )
            RingLog.i("notes", "done: ${media.title}, ${parsed.sections.size} sections, ${(now - started) / 1000} s")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val reason = "$stage failed: ${reasonFor(t)}"
            RingLog.e("notes", "failed: $reason", t)
            dao.setStatus(uri, NotesRepository.STATUS_FAILED, reason, System.currentTimeMillis())
        }
    }

    /** 0.9.0: Transcribe on an uploaded file, or null when that model cannot take it (the Flash path runs). */
    private fun transcribeOrNull(client: GeminiClient, file: GeminiClient.UploadedFile, onRetry: (GeminiClient.Retry) -> Unit): GeminiClient.Transcription? =
        try {
            client.transcribe(file, onRetry)
        } catch (e: GeminiClient.GeminiException) {
            if (e.code == 401 || e.code == 403) throw e
            RingLog.w("notes", "${GeminiClient.TRANSCRIBE_MODEL} unavailable (HTTP ${e.code}); using the Flash transcript path")
            null
        }

    private fun linesFrom(result: GeminiClient.Transcription, offsetMs: Long, lengthMs: Long): List<Pair<Long, String>> =
        if (result.words.isNotEmpty()) TranscriptLines.group(result.words, offsetMs)
        else TranscriptLines.spread(result.text, offsetMs, lengthMs)

    /**
     * 0.9.0: the recording in parts of about 20 minutes, each uploaded, transcribed with word
     * times by Gemini 3.5 Transcribe, saved, then deleted from Google. Progress is kept per part,
     * so a retry continues after the last finished part. Returns null, before uploading anything,
     * when the file cannot be cut (format) or Transcribe is unavailable; the caller then uses the
     * whole-file path.
     */
    private suspend fun transcribeSliced(
        app: LumenApp,
        client: GeminiClient,
        media: MediaEntity,
        checkpoint: TranscriptCheckpoint,
        saved: List<Pair<Long, String>>,
        slicedThroughMs: Long,
        step: suspend (String) -> Unit,
        recovering: (GeminiClient.Retry) -> Unit,
    ): List<Pair<Long, String>>? {
        if (!AudioSlicer.canTry(media.mime, media.title)) return null
        val dao = app.notesRepo.dao
        var temp: File? = null
        try {
            val localPath: String? = when {
                media.offlinePath?.let { File(it).exists() } == true -> media.offlinePath
                media.uri.startsWith(DriveRepository.URI_PREFIX) -> {
                    val target = File(cacheDir, "notes-source.audio").also { temp = it }
                    step("Downloading the recording from Drive")
                    val source = runInterruptible { openSource(app, media) }
                    try {
                        runInterruptible {
                            target.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                var copied = 0L
                                var shown = -1
                                while (true) {
                                    val n = source.stream.read(buffer)
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                    copied += n
                                    val pct = if (source.size > 0) (copied * 100 / source.size).toInt() else 0
                                    if (pct >= shown + 10) { shown = pct; runBlocking { step("Downloading the recording from Drive $pct%") } }
                                }
                            }
                        }
                    } finally { source.close() }
                    target.path
                }
                media.uri.startsWith("file:") -> Uri.parse(media.uri).path
                else -> null
            }
            val context = this
            val slicer = AudioSlicer { extractor ->
                if (localPath != null) extractor.setDataSource(localPath)
                else extractor.setDataSource(context, Uri.parse(media.uri), null)
            }
            val total = runInterruptible { slicer.durationMs() }.takeIf { it > 0 } ?: return null
            val resumeFrom = if (slicedThroughMs >= 0 && saved.isNotEmpty()) slicedThroughMs else 0L
            val lines = if (resumeFrom > 0) saved.toMutableList() else mutableListOf()
            var replaced = resumeFrom > 0 || saved.isEmpty()
            val plan = AudioSlicer.plan(total, resumeFrom)
            val firstPart = AudioSlicer.plan(total, 0L).size - plan.size
            val parts = firstPart + plan.size
            for ((i, range) in plan.withIndex()) {
                val part = firstPart + i + 1
                val (from, to) = range
                step("Preparing part $part of $parts")
                val out = File(cacheDir, "notes-slice-$part.audio")
                val slice = runInterruptible { slicer.cut(from, to, out) }
                if (slice == null) {
                    if (i == 0 && lines.isEmpty()) return null
                    break
                }
                try {
                    var shown = -1
                    val uploaded = runInterruptible {
                        slice.file.inputStream().use { input ->
                            client.upload(input, slice.file.length(), slice.mimeType, "${media.title} part $part") { fraction ->
                                val pct = (fraction * 100).toInt()
                                if (pct >= shown + 10) { shown = pct; runBlocking { step("Uploading part $part of $parts, $pct%") } }
                            }
                        }
                    }
                    val result = try {
                        runInterruptible { client.waitUntilActive(uploaded) }
                        step("Transcribing part $part of $parts; ${lines.size} lines saved")
                        runInterruptible { if (i == 0 && lines.isEmpty()) transcribeOrNull(client, uploaded, recovering) else client.transcribe(uploaded, recovering) }
                    } finally { withContext(NonCancellable + Dispatchers.IO) { client.delete(uploaded) } }
                    if (result == null) return null
                    val after = lines.lastOrNull()?.first ?: -1L
                    val added = linesFrom(result, slice.startMs, slice.endMs - slice.startMs).filter { it.first > after }
                    if (!replaced) {
                        // Lines from the older Flash path are replaced by this transcript.
                        dao.deleteSegments(media.uri)
                        replaced = true
                    }
                    if (added.isNotEmpty()) dao.insertSegments(added.map { (ms, text) -> SegmentEntity(mediaUri = media.uri, startMs = ms, text = text) })
                    lines += added
                    checkpoint.save(media, lines, false, to)
                    step("Part $part of $parts transcribed; ${lines.size} lines saved")
                } finally { out.delete() }
            }
            checkpoint.save(media, lines, true, total)
            step("Transcript ready; ${lines.size} lines saved")
            return lines
        } finally {
            temp?.delete()
        }
    }

    private class Source(val stream: InputStream, val size: Long, val close: () -> Unit)

    /** The kept copy when there is one, else Drive directly, else the phone's own file. */
    private fun openSource(app: LumenApp, media: MediaEntity): Source {
        media.offlinePath?.let { path ->
            val f = File(path)
            if (f.exists()) {
                val s = FileInputStream(f)
                return Source(s, f.length()) { s.close() }
            }
        }
        if (media.uri.startsWith(DriveRepository.URI_PREFIX)) {
            val token = app.drive.auth.tokenBlocking() ?: throw IOException("Sign in to Drive again from Settings")
            val (connection, input) = DriveApi.openMedia(token, DriveRepository.fileIdOf(media.uri))
            val size = connection.contentLengthLong.takeIf { it > 0 } ?: media.sizeBytes
            if (size <= 0) {
                connection.disconnect()
                throw IOException("Drive did not say how large this file is")
            }
            return Source(input, size) {
                try { input.close() } catch (_: Throwable) {}
                connection.disconnect()
            }
        }
        val uri = Uri.parse(media.uri)
        val size = media.sizeBytes.takeIf { it > 0 }
            ?: contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 }
            ?: throw IOException("Could not read the size of this file")
        val stream = contentResolver.openInputStream(uri) ?: throw IOException("Could not open this file")
        return Source(stream, size) { stream.close() }
    }

    private fun mimeFor(media: MediaEntity): String {
        val mime = media.mime.lowercase()
        return when {
            mime == "audio/mpeg" || mime == "audio/mpeg3" || mime == "audio/x-mpeg" -> "audio/mp3"
            mime.startsWith("audio/") -> mime
            else -> when (media.relPath.substringAfterLast('.', "").lowercase()) {
                "m4a", "aac" -> "audio/aac"
                "wav" -> "audio/wav"
                "ogg", "opus" -> "audio/ogg"
                "flac" -> "audio/flac"
                else -> "audio/mp3"
            }
        }
    }

    private fun reasonFor(t: Throwable): String = when {
        // 0.9.0: Claude writes the notes when an Anthropic key is saved.
        t is ClaudeClient.ClaudeException && (t.code == 401 || t.code == 403) -> "Anthropic refused the Claude key. Check it in Settings"
        t is ClaudeClient.ClaudeException && t.message.orEmpty().contains("credit", ignoreCase = true) ->
            "Your Anthropic credit balance is too low for Claude notes. Top up, or remove the Claude key to use Gemini"
        t is ClaudeClient.ClaudeException && t.code == 429 -> "Claude's rate limit is reached for now. The transcript is saved; try again in a minute"
        t is ClaudeClient.ClaudeException && t.code >= 500 -> "Claude is busy (HTTP ${t.code}). The transcript is saved; try again later"
        t is ClaudeClient.ClaudeException -> "Claude said: ${t.message.orEmpty().take(160)}"
        t is GeminiClient.GeminiException && (t.code == 400 && t.message.orEmpty().contains("key", ignoreCase = true)) ->
            "Google refused the Gemini key. Check it in Settings"
        t is GeminiClient.GeminiException && (t.code == 401 || t.code == 403) -> "Google refused the Gemini key. Check it in Settings"
        t is GeminiClient.GeminiException && t.code == 429 -> "Google's limit for this key is used up for now. Try again later"
        t is GeminiClient.GeminiException && t.code >= 500 -> "Google is still unable to process this section (HTTP ${t.code}). Saved transcript text is kept; try again later"
        t is GeminiClient.GeminiException -> "Google said: ${t.message.orEmpty().take(160)}"
        t is java.net.SocketTimeoutException -> "Google did not finish this section in time. Saved transcript lines are kept; try again to continue"
        t is java.net.UnknownHostException || t is javax.net.ssl.SSLException || t is java.net.ConnectException ||
            t is java.net.SocketException -> "The connection dropped. Try again on Wi-Fi"
        t is IOException && t.message.orEmpty().let { it.contains(" 401") || it.contains(" 403") || it.contains("authError") } ->
            "Drive would not send this file. Sign in to Drive again in Settings"
        t is IOException && !t.message.isNullOrBlank() -> t.message!!
        else -> "Something went wrong (${t.javaClass.simpleName}). Try again"
    }

    // Notification.

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sermon notes", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Writing sermon notes")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    private fun goForeground(text: String): Boolean {
        channel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(text), type)
            return true
        } catch (t: Throwable) {
            // Past the daily allowance Android refuses; the job is marked and the service ends.
            RingLog.e("notes", "could not run in the foreground", t)
            return false
        }
    }

    private fun notify(text: String) {
        lastStep = text
        val now = System.currentTimeMillis()
        if (now - lastNotifiedAt < 1_000) return
        lastNotifiedAt = now
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(text))
    }

    companion object {
        const val EXTRA_URI = "app.lumen.player.notes.uri"
        private const val CHANNEL = "notes"
        private const val NOTIFICATION_ID = 4107
        private const val JOB_CEILING_MS = 60 * 60_000L

        /** 0.9.0: one Transcribe request with word times takes up to 30 minutes of audio. */
        private const val TRANSCRIBE_DIRECT_MAX_MS = 29 * 60_000L

        fun isPermissionNeeded(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
}
