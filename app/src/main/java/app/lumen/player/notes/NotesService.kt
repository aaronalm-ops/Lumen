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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pending = LinkedHashSet<String>()
    private var worker: Job? = null
    @Volatile private var lastStartId = 0
    private var lastStep = ""
    private var lastNotifiedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(lastStep.ifBlank { "Getting ready" })
        val uri = intent?.getStringExtra(EXTRA_URI)
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

    private suspend fun run(uri: String) {
        val app = LumenApp.from(this)
        val dao = app.notesRepo.dao
        val started = System.currentTimeMillis()
        suspend fun step(text: String) {
            dao.setStatus(uri, NotesRepository.STATUS_WORKING, text, System.currentTimeMillis())
            notify(text)
        }
        try {
            val media = app.db.mediaDao().mediaByUri(uri) ?: throw IOException("This recording is no longer in the library")
            val key = app.notesRepo.apiKey()
            if (key.isBlank()) throw IOException("Add your Gemini key in Settings, Sermon notes")
            val client = GeminiClient(key)
            RingLog.i("notes", "start: ${media.title}")

            step("Uploading 0%")
            val source = openSource(app, media)
            var shown = -1
            val file = try {
                client.upload(source.stream, source.size, mimeFor(media), media.title) { f ->
                    val pct = (f * 100).toInt()
                    if (pct >= shown + 5) {
                        shown = pct
                        runBlocking { step("Uploading $pct%") }
                    }
                }
            } finally {
                source.close()
            }
            val segments = try {
                step("Google is preparing the recording")
                client.waitUntilActive(file)
                step("Transcribing. This takes a few minutes")
                transcribe(client, file) { n -> runBlocking { step("Transcribing. $n lines so far") } }
            } finally {
                client.delete(file)
            }
            if (segments.isEmpty()) throw IOException("No speech was found in this recording")
            dao.deleteSegments(uri)
            dao.insertSegments(segments.map { (ms, text) -> SegmentEntity(mediaUri = uri, startMs = ms, text = text) })
            RingLog.i("notes", "transcript: ${segments.size} lines")

            step("Writing notes from ${segments.size} transcript lines")
            val transcript = segments.joinToString("\n") { (ms, text) -> "[${stamp(ms)}] $text" }
            val reply = client.generate(
                GeminiClient.MODELS,
                NotesPrompts.NOTES_SYSTEM,
                JSONArray().put(JSONObject().put("text", NotesPrompts.notesRequest(transcript))),
                NotesPrompts.notesSchema,
                maxOutputTokens = 32_768,
                thinking = "medium",
            )
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
            val reason = reasonFor(t)
            RingLog.e("notes", "failed: $reason", t)
            dao.setStatus(uri, NotesRepository.STATUS_FAILED, reason, System.currentTimeMillis())
        }
    }

    /**
     * The timed transcript. A reply that stops at the output limit is carried on from its last
     * line, up to [MAX_ROUNDS] calls; lines at or before the last time already held are dropped.
     */
    private fun transcribe(client: GeminiClient, file: GeminiClient.UploadedFile, onLines: (Int) -> Unit): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        var after: String? = null
        var lastMs = -1L
        for (round in 1..MAX_ROUNDS) {
            val parts = JSONArray()
                .put(JSONObject().put("file_data", JSONObject().put("mime_type", file.mimeType).put("file_uri", file.uri)))
                .put(JSONObject().put("text", NotesPrompts.transcriptRequest(after)))
            val reply = client.generate(
                GeminiClient.MODELS,
                NotesPrompts.TRANSCRIPT_SYSTEM,
                parts,
                NotesPrompts.transcriptSchema,
                maxOutputTokens = 65_536,
                thinking = "low",
            )
            val lines = parseSegments(reply.text).filter { it.first > lastMs }
            out += lines
            if (lines.isNotEmpty()) lastMs = lines.last().first
            onLines(out.size)
            RingLog.i("notes", "transcript round $round: ${lines.size} lines, finish ${reply.finishReason}")
            if (reply.finishReason != "MAX_TOKENS" || lines.isEmpty()) break
            after = stamp(lastMs)
        }
        return out
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
        t is GeminiClient.GeminiException && t.code == 429 -> "Google's free limit is used up for now. Try again later"
        t is GeminiClient.GeminiException && t.code >= 500 -> "Google is busy. Try again in a few minutes"
        t is GeminiClient.GeminiException -> "Google said: ${t.message.orEmpty().take(160)}"
        t is java.net.UnknownHostException || t is java.net.SocketTimeoutException -> "The connection dropped. Try again on Wi-Fi"
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

    private fun goForeground(text: String) {
        channel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(text), type)
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
        private const val MAX_ROUNDS = 6

        fun isPermissionNeeded(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
}
