package app.lumen.player.data

import android.content.Context
import app.lumen.player.diag.RingLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Single writer for playback progress. Every write goes to the Room table and is also appended
 * as one JSON line to filesDir/positions.jsonl so nothing is lost if the database is ever
 * corrupted.
 */
class PositionStore(context: Context, private val dao: ProgressDao) {

    companion object {
        const val KEY_LAST_ITEM = "last_key"
        const val RESUME_REWIND_MS = 3_000L
        const val UNKNOWN_DURATION = "unknown"

        /** Key = content URI string plus "|" plus duration when known. */
        fun keyFor(uri: String, durationMs: Long): String =
            if (durationMs > 0) "$uri|$durationMs" else "$uri|$UNKNOWN_DURATION"

        fun resumePosition(savedMs: Long): Long = maxOf(0L, savedMs - RESUME_REWIND_MS)
    }

    private val backupFile = File(context.filesDir, "positions.jsonl")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.UK)

    private val _lastSaved = MutableStateFlow("nothing saved yet")
    val lastSaved: StateFlow<String> = _lastSaved

    /** Fire-and-forget write used from player callbacks. */
    fun save(uri: String, title: String, positionMs: Long, durationMs: Long, reason: String) {
        scope.launch { saveNow(uri, title, positionMs, durationMs, reason) }
    }

    /** Blocking-capable write used from Service.onDestroy. */
    suspend fun saveNow(uri: String, title: String, positionMs: Long, durationMs: Long, reason: String) {
        val now = System.currentTimeMillis()
        val entity = ProgressEntity(
            key = keyFor(uri, durationMs),
            uri = uri,
            title = title,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = now,
        )
        try {
            dao.upsert(entity)
            dao.putKv(KvEntity(KEY_LAST_ITEM, entity.key))
            appendBackup(entity, reason)
            val stamp = synchronized(clock) { clock.format(Date(now)) }
            _lastSaved.value = "${formatTime(positionMs)} at $stamp ($reason)"
            RingLog.i("store", "saved ${entity.key} pos=$positionMs dur=$durationMs reason=$reason")
        } catch (t: Throwable) {
            RingLog.e("store", "save failed", t)
        }
    }

    suspend fun lastItem(): ProgressEntity? {
        val key = dao.kv(KEY_LAST_ITEM) ?: return null
        return dao.get(key)
    }

    suspend fun latestForUri(uri: String): ProgressEntity? = dao.latestForUri(uri)

    suspend fun dumpTable(): String {
        val rows = dao.all()
        if (rows.isEmpty()) return "(progress table is empty)\n"
        return buildString {
            append("key | title | position_ms | duration_ms | updated_at\n")
            rows.forEach { r ->
                append("${r.key} | ${r.title} | ${r.positionMs} | ${r.durationMs} | ${r.updatedAt}\n")
            }
        }
    }

    suspend fun backupTail(lines: Int): String = withContext(Dispatchers.IO) {
        if (!backupFile.exists()) return@withContext "(positions.jsonl does not exist)\n"
        try {
            backupFile.readLines().takeLast(lines).joinToString("\n", postfix = "\n")
        } catch (t: Throwable) {
            "(could not read positions.jsonl: $t)\n"
        }
    }

    private fun appendBackup(entity: ProgressEntity, reason: String) {
        val json = JSONObject()
            .put("key", entity.key)
            .put("uri", entity.uri)
            .put("title", entity.title)
            .put("position_ms", entity.positionMs)
            .put("duration_ms", entity.durationMs)
            .put("updated_at", entity.updatedAt)
            .put("reason", reason)
        backupFile.appendText(json.toString() + "\n")
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = maxOf(0L, ms) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.UK, "%02d:%02d", m, s)
    }
}
