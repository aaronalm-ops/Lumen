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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        /**
         * 0.6.1: where a saved item starts. Within the last [FINISHED_TAIL_MS] (or 97 percent) it
         * counts as finished and starts again at 0; otherwise saved minus the rewind.
         */
        fun resumePosition(saved: ProgressEntity): Long =
            if (isFinished(saved.positionMs, saved.durationMs)) 0L else resumePosition(saved.positionMs)

        fun isFinished(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && (positionMs >= durationMs - FINISHED_TAIL_MS || positionMs >= durationMs * 97 / 100)

        const val FINISHED_TAIL_MS = 30_000L

        /** A load-time save near the start never replaces a place further in than this. */
        private const val GUARD_FLOOR_MS = 30_000L
        private const val GUARD_START_MS = 5_000L

        /**
         * Saves made right after an item is loaded or resumed. The player then sits at the saved
         * position minus the rewind, and writing that back would move the stored position 3 s
         * earlier on every launch or transition without a second of playback.
         */
        private val RESUME_REASONS = setOf("transition", "ready", "resume")
    }

    private val backupFile = File(context.filesDir, "positions.jsonl")
    // 0.6.1: one save at a time, in the order asked for, so "left item" never lands after the next
    // item's first save and points Continue at the wrong file.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /**
     * Saves are fire-and-forget from player callbacks, so two can be in flight at once (an item
     * left and the next one started, for example). The lock makes them land in the order they
     * were requested; its waiters are served first in, first out.
     */
    private val writeLock = Mutex()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.UK)

    private val _lastSaved = MutableStateFlow("nothing saved yet")
    val lastSaved: StateFlow<String> = _lastSaved

    /** Fire-and-forget write used from player callbacks. */
    fun save(uri: String, title: String, positionMs: Long, durationMs: Long, reason: String, remember: Boolean = true) {
        scope.launch { saveNow(uri, title, positionMs, durationMs, reason, remember) }
    }

    /** Blocking-capable write used from Service.onDestroy. */
    suspend fun saveNow(uri: String, title: String, positionMs: Long, durationMs: Long, reason: String, remember: Boolean = true): Unit = writeLock.withLock {
        val now = System.currentTimeMillis()
        var effectiveDuration = durationMs
        if (durationMs <= 0) {
            // An unknown duration would create a second "|unknown" row beside the real one. When
            // this uri already has a row, write into that row (keeping its duration) instead of
            // creating a duplicate; a brand new uri keeps the "|unknown" key until READY.
            val existing = try {
                dao.latestForUri(uri)
            } catch (t: Throwable) {
                null
            }
            if (existing != null) {
                if (existing.durationMs <= 0 && reason != "ready") {
                    // Same unknown row; nothing better to write yet.
                    RingLog.i("store", "skipped unknown-duration save for $uri reason=$reason")
                    return@withLock
                }
                effectiveDuration = existing.durationMs
            }
        }
        val key = keyFor(uri, effectiveDuration)
        var effectivePosition = positionMs
        if (reason in RESUME_REASONS) {
            // Loading an item puts the player at the stored position minus the rewind. That is
            // not progress, so keep the stored position; only a real move forward or a user seek
            // (which saves under its own reason) changes it.
            val prior = try {
                dao.get(key)
            } catch (t: Throwable) {
                null
            }
            if (prior != null && prior.positionMs > positionMs && prior.positionMs - positionMs <= RESUME_REWIND_MS) {
                effectivePosition = prior.positionMs
            }
            // 0.6.1 (red team round 2, P0): an item loaded at the very start, whatever the reason,
            // never wipes a place well into it. Real listening saves under its own reasons.
            if (prior != null && positionMs <= GUARD_START_MS && prior.positionMs > GUARD_FLOOR_MS &&
                !isFinished(prior.positionMs, prior.durationMs)
            ) {
                RingLog.i("store", "kept ${prior.positionMs} over a load-time $positionMs for $uri ($reason)")
                effectivePosition = prior.positionMs
            }
        }
        val entity = ProgressEntity(
            key = key,
            uri = uri,
            title = title,
            positionMs = effectivePosition,
            durationMs = effectiveDuration,
            updatedAt = now,
        )
        try {
            dao.upsert(entity)
            // 0.6.1: [remember] false for a one-off "Open with" file, which cannot be reopened later.
            if (remember) dao.putKv(KvEntity(KEY_LAST_ITEM, entity.key))
            appendBackup(entity, reason)
            val stamp = synchronized(clock) { clock.format(Date(now)) }
            _lastSaved.value = "${formatTime(effectivePosition)} at $stamp ($reason)"
            RingLog.i("store", "saved ${entity.key} pos=$effectivePosition dur=$effectiveDuration reason=$reason")
        } catch (t: Throwable) {
            RingLog.e("store", "save failed", t)
        }
    }

    suspend fun lastItem(): ProgressEntity? {
        val key = dao.kv(KEY_LAST_ITEM) ?: return null
        return dao.get(key)
    }

    suspend fun latestForUri(uri: String): ProgressEntity? = dao.latestForUri(uri)

    /** Position by uri, falling back to any other copy of the same file (same fingerprint). */
    suspend fun latestFor(uri: String, fingerprint: String?): ProgressEntity? {
        dao.latestForUri(uri)?.let { return it }
        if (fingerprint.isNullOrBlank()) return null
        return try {
            dao.latestForFingerprint(fingerprint)
        } catch (t: Throwable) {
            null
        }
    }

    val allFlow: Flow<List<ProgressEntity>> get() = dao.allFlow()

    /** The last [n] distinct items, most recent first. */
    suspend fun recent(n: Int): List<ProgressEntity> = dao.all().distinctBy { it.uri }.take(n)

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
