package app.lumen.player.notes

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.lumen.player.data.MediaEntity
import app.lumen.player.data.NotesDao
import app.lumen.player.data.NotesEntity
import app.lumen.player.data.SegmentEntity
import app.lumen.player.data.drive.Connectivity
import app.lumen.player.diag.RingLog
import app.lumen.player.remote.isYtm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 0.6.0: sermon notes and transcripts. The Gemini key lives in its own private preferences file,
 * outside the Room `kv` table, so it never travels in a backup.
 */
class NotesRepository(
    private val context: Context,
    val dao: NotesDao,
    private val connectivity: Connectivity,
) {
    private val secrets = context.getSharedPreferences("lumen_secrets", Context.MODE_PRIVATE)

    private val _hasKey = MutableStateFlow(apiKey().isNotBlank())
    val hasKey: StateFlow<Boolean> = _hasKey

    fun apiKey(): String = secrets.getString(KEY_GEMINI, "").orEmpty()

    /** The last four characters, for "Key ending 1a2b" in Settings. */
    fun keyHint(): String = apiKey().takeLast(4)

    fun setApiKey(key: String) {
        secrets.edit().putString(KEY_GEMINI, key.trim()).apply()
        _hasKey.value = key.isNotBlank()
        RingLog.i("notes", if (key.isBlank()) "Gemini key removed" else "Gemini key saved")
    }

    fun notes(uri: String): Flow<NotesEntity?> = dao.notesFlow(uri)

    fun segments(uri: String): Flow<List<SegmentEntity>> = dao.segmentsFlow(uri)

    val all: Flow<List<NotesEntity>> = dao.allFlow()

    suspend fun search(q: String): List<SegmentEntity> {
        val clean = q.trim()
        if (clean.length < 3) return emptyList()
        return dao.search(clean, SEARCH_LIMIT)
    }

    /** Why notes cannot be written for [media] right now, or null when they can. */
    fun blocker(media: MediaEntity): String? = when {
        media.isYtm -> "Notes need a recording on the phone or in Drive"
        media.isVideo -> "Notes are for audio recordings for now"
        apiKey().isBlank() -> "Add your Gemini key in Settings, Sermon notes"
        !connectivity.isOnline() -> "Offline. Notes need a connection"
        !connectivity.isUnmetered() -> "Connect to Wi-Fi to write notes. A sermon is too large for mobile data"
        else -> null
    }

    /** Marks the row as working and hands the job to [NotesService]. */
    suspend fun start(media: MediaEntity) {
        val now = System.currentTimeMillis()
        val old = dao.notes(media.uri)
        dao.upsert(
            NotesEntity(
                mediaUri = media.uri,
                title = media.title,
                sizeBytes = media.sizeBytes,
                status = STATUS_WORKING,
                step = "Waiting to start",
                json = old?.json.orEmpty(),
                model = old?.model.orEmpty(),
                createdAt = old?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        val intent = Intent(context, NotesService::class.java).putExtra(NotesService.EXTRA_URI, media.uri)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            dao.setStatus(media.uri, STATUS_FAILED, "Lumen could not start the notes job. Try again with Lumen open", System.currentTimeMillis())
            throw t
        }
        RingLog.i("notes", "queued: ${media.title}")
    }

    companion object {
        const val STATUS_WORKING = "working"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val SEARCH_LIMIT = 60
    }
}
