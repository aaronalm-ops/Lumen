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
            if (savedState?.complete != true && !legacyComplete) {
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
                    step("Transcribing; ${segments.size} lines saved. Text appears in Transcript as it arrives")
                    segments = runInterruptible {
                        TranscriptRunner(
                            generate = { after, onText ->
                                val parts = JSONArray()
                                    .put(JSONObject().put("file_data", JSONObject().put("mime_type", file.mimeType).put("file_uri", file.uri)))
                                    .put(JSONObject().put("text", NotesPrompts.transcriptRequest(after)))
                                client.generate(GeminiClient.MODELS, NotesPrompts.TRANSCRIPT_SYSTEM, parts,
                                    NotesPrompts.transcriptSchema, maxOutputTokens = 8_192, thinking = "low", onText = onText)
                            },
                            persist = { added, all, complete -> runBlocking {
                                if (added.isNotEmpty()) dao.insertSegments(added.map { (ms, text) -> SegmentEntity(mediaUri = uri, startMs = ms, text = text) })
                                checkpoint.save(media, all, complete)
                                step(if (complete) "Transcript ready; ${all.size} lines saved" else "Transcribing; ${all.size} lines saved through ${stamp(all.last().first)}")
                            } },
                        ).run(media.durationMs, segments)
                    }
                } finally { withContext(NonCancellable + Dispatchers.IO) { client.delete(file) } }
            } else step("Using the saved transcript; no new audio upload needed")
            if (segments.isEmpty()) throw IOException("No speech was found in this recording")
            RingLog.i("notes", "transcript: ${segments.size} lines")
            stage = "Writing notes"
            step("Writing notes from ${segments.size} saved transcript lines")
            val transcript = segments.joinToString("\n") { (ms, text) -> "[${stamp(ms)}] $text" }
            var progressAt = 0L
            val reply = runInterruptible { client.generate(
                GeminiClient.MODELS,
                NotesPrompts.NOTES_SYSTEM,
                JSONArray().put(JSONObject().put("text", NotesPrompts.notesRequest(transcript))),
                NotesPrompts.notesSchema,
                maxOutputTokens = 32_768,
                thinking = "medium",
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
        t is GeminiClient.GeminiException && (t.code == 400 && t.message.orEmpty().contains("key", ignoreCase = true)) ->
            "Google refused the Gemini key. Check it in Settings"
        t is GeminiClient.GeminiException && (t.code == 401 || t.code == 403) -> "Google refused the Gemini key. Check it in Settings"
        t is GeminiClient.GeminiException && t.code == 429 -> "Google's limit for this key is used up for now. Try again later"
        t is GeminiClient.GeminiException && t.code >= 500 -> "Google is busy. Try again in a few minutes"
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

        fun isPermissionNeeded(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
}
