package app.lumen.player.data.backup

import android.content.Context
import android.net.Uri
import app.lumen.player.BuildConfig
import app.lumen.player.data.BookmarkEntity
import app.lumen.player.data.KvEntity
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.LabelRepository
import app.lumen.player.data.LumenDb
import app.lumen.player.data.MediaLabelEntity
import app.lumen.player.data.PositionStore
import app.lumen.player.data.ProgressEntity
import app.lumen.player.data.SettingsRepository
import app.lumen.player.data.drive.DriveApi
import app.lumen.player.data.drive.DriveAuthException
import app.lumen.player.data.drive.DriveRepository
import app.lumen.player.diag.RingLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The backup file: positions, labels, media_labels (by uri and by fingerprint), bookmarks and
 * settings as one JSON document. "Back up now" writes it where the owner chose through
 * ACTION_CREATE_DOCUMENT and, when Drive is connected, also into a "Lumen" folder the app
 * creates (drive.file sees it because the app made it). Once a week the Drive copy is refreshed
 * on app start. Restore merges and never deletes: a position wins by `updated_at`, labels match
 * by name, media by uri first and fingerprint second, bookmarks by uri and position, settings
 * only where this device has no value yet.
 */
class BackupManager(
    private val context: Context,
    private val db: LumenDb,
    private val store: PositionStore,
    private val settings: SettingsRepository,
    private val labels: LabelRepository,
    private val drive: DriveRepository,
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val DRIVE_FOLDER = "Lumen"
        const val AUTO_BACKUP_AFTER_MS = 7L * 24 * 60 * 60 * 1000

        fun defaultFileName(now: Long = System.currentTimeMillis()): String =
            "lumen-backup-" + SimpleDateFormat("yyyyMMdd", Locale.UK).format(Date(now)) + ".json"
    }

    /** One line for the Backup card: "Backed up 2 days ago", "Restored 14 items", "Backup failed". */
    private val _lastResult = MutableStateFlow("")
    val lastResult: StateFlow<String> = _lastResult

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    // Building the document.

    suspend fun buildJson(): String = withContext(Dispatchers.IO) {
        val progressDao = db.progressDao()
        val mediaDao = db.mediaDao()
        val labelDao = db.labelDao()
        val media = mediaDao.media().associateBy { it.uri }
        val root = JSONObject()
            .put("format", FORMAT_VERSION)
            .put("app", "Lumen ${BuildConfig.VERSION_NAME}")
            .put("created_at", System.currentTimeMillis())

        val positions = JSONArray()
        for (p in progressDao.all()) {
            positions.put(
                JSONObject()
                    .put("key", p.key)
                    .put("uri", p.uri)
                    .put("title", p.title)
                    .put("position_ms", p.positionMs)
                    .put("duration_ms", p.durationMs)
                    .put("updated_at", p.updatedAt)
                    .put("fingerprint", media[p.uri]?.fingerprint ?: JSONObject.NULL),
            )
        }
        root.put("positions", positions)

        val labelRows = JSONArray()
        for (l in labelDao.labels()) {
            labelRows.put(
                JSONObject()
                    .put("name", l.name)
                    .put("hue", l.hue)
                    .put("lock_kids", l.lockKids)
                    .put("no_interruptions", l.noInterruptions)
                    .put("shuffle_on_open", l.shuffleOnOpen),
            )
        }
        root.put("labels", labelRows)

        val labelNames = labelDao.labels().associate { it.id to it.name }
        val links = JSONArray()
        for (link in labelDao.allLinks()) {
            val name = labelNames[link.labelId] ?: continue
            links.put(
                JSONObject()
                    .put("uri", link.mediaUri)
                    .put("fingerprint", media[link.mediaUri]?.fingerprint ?: JSONObject.NULL)
                    .put("label", name),
            )
        }
        root.put("media_labels", links)

        val bookmarks = JSONArray()
        for (b in mediaDao.allBookmarks()) {
            bookmarks.put(
                JSONObject()
                    .put("uri", b.mediaUri)
                    .put("fingerprint", media[b.mediaUri]?.fingerprint ?: JSONObject.NULL)
                    .put("position_ms", b.positionMs)
                    .put("created_at", b.createdAt),
            )
        }
        root.put("bookmarks", bookmarks)

        val kv = JSONObject()
        for (entry in settings.allEntries()) {
            if (entry.k in SettingsRepository.deviceOnlyKeys) continue
            kv.put(entry.k, entry.v)
        }
        root.put("settings", kv)
        root.toString(2)
    }

    // Writing.

    /** "Back up now": the chosen document, then Drive when connected. */
    suspend fun backupTo(target: Uri): Boolean {
        _busy.value = true
        try {
            val json = buildJson()
            val bytes = json.toByteArray(Charsets.UTF_8)
            val wrote = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: return@withContext false
                    true
                } catch (t: Throwable) {
                    RingLog.e("backup", "write to $target failed", t)
                    false
                }
            }
            if (!wrote) {
                _lastResult.value = "Backup failed"
                return false
            }
            val now = System.currentTimeMillis()
            settings.setBackupAt(now)
            RingLog.i("backup", "written to $target, ${bytes.size} bytes")
            val driveOk = uploadToDrive(bytes, now)
            _lastResult.value = when (driveOk) {
                true -> "Backed up to the file and to Drive"
                false -> "Backed up to the file; Drive upload failed"
                null -> "Backed up to the file"
            }
            return true
        } finally {
            _busy.value = false
        }
    }

    /** Weekly automatic Drive backup, checked on app start. */
    suspend fun autoBackupIfDue() {
        val folderId = settings.get(SettingsRepository.KEY_DRIVE_FOLDER_ID).orEmpty()
        if (folderId.isBlank()) return
        val last = settings.get(SettingsRepository.KEY_BACKUP_DRIVE_AT)?.toLongOrNull() ?: 0L
        val now = System.currentTimeMillis()
        if (now - last < AUTO_BACKUP_AFTER_MS) return
        if (!drive.connectivity.isOnline()) return
        RingLog.i("backup", "weekly Drive backup is due")
        val bytes = buildJson().toByteArray(Charsets.UTF_8)
        if (uploadToDrive(bytes, now) == true) settings.setBackupAt(now)
    }

    /** Null when Drive is not connected; otherwise whether the upload succeeded. */
    private suspend fun uploadToDrive(bytes: ByteArray, now: Long): Boolean? = withContext(Dispatchers.IO) {
        val folderId = settings.get(SettingsRepository.KEY_DRIVE_FOLDER_ID).orEmpty()
        if (folderId.isBlank()) return@withContext null
        try {
            val token = drive.auth.tokenBlocking() ?: run {
                settings.setDriveNeedsSignIn(true)
                return@withContext false
            }
            val lumenFolder = DriveApi.findOrCreateFolder(token, DRIVE_FOLDER)
            val name = defaultFileName(now)
            val existing = DriveApi.findInFolder(token, lumenFolder, name)
            val id = DriveApi.uploadMultipart(token, lumenFolder, name, "application/json", bytes, existing)
            settings.setBackupDriveAt(now)
            RingLog.i("backup", "uploaded $name to Drive ($id)")
            true
        } catch (t: DriveAuthException) {
            settings.setDriveNeedsSignIn(true)
            RingLog.w("backup", "Drive refused the upload with ${t.code}")
            false
        } catch (t: Throwable) {
            RingLog.e("backup", "Drive upload failed", t)
            false
        }
    }

    // Restoring.

    /** "Restore": merges the chosen document. Returns a one-line summary. */
    suspend fun restoreFrom(source: Uri): String {
        _busy.value = true
        try {
            val text = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(source)?.use { it.bufferedReader().readText() }
                } catch (t: Throwable) {
                    RingLog.e("backup", "read of $source failed", t)
                    null
                }
            }
            if (text.isNullOrBlank()) {
                _lastResult.value = "Restore failed: could not read the file"
                return _lastResult.value
            }
            val root = try {
                JSONObject(text)
            } catch (t: Throwable) {
                _lastResult.value = "Restore failed: not a Lumen backup"
                return _lastResult.value
            }
            if (!root.has("positions") && !root.has("labels")) {
                _lastResult.value = "Restore failed: not a Lumen backup"
                return _lastResult.value
            }
            val summary = withContext(Dispatchers.IO) { merge(root) }
            _lastResult.value = summary
            RingLog.i("backup", "restore: $summary")
            return summary
        } finally {
            _busy.value = false
        }
    }

    private suspend fun merge(root: JSONObject): String {
        val progressDao = db.progressDao()
        val mediaDao = db.mediaDao()
        val labelDao = db.labelDao()
        val mediaByUri = mediaDao.media().associateBy { it.uri }
        val mediaByFingerprint = mediaDao.media().groupBy { it.fingerprint }

        /** The local uris a backup row refers to: the same uri when known here, else every copy of the fingerprint. */
        fun resolveUris(uri: String?, fingerprint: String?): List<String> {
            if (!uri.isNullOrBlank() && mediaByUri.containsKey(uri)) return listOf(uri)
            if (!fingerprint.isNullOrBlank()) mediaByFingerprint[fingerprint]?.let { rows -> if (rows.isNotEmpty()) return rows.map { it.uri } }
            // A position for a file this phone has not scanned is still worth keeping under its uri.
            return if (!uri.isNullOrBlank()) listOf(uri) else emptyList()
        }

        var positions = 0
        root.optJSONArray("positions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val uri = p.optString("uri", "")
                val fingerprint = p.optString("fingerprint", "").takeIf { it.isNotBlank() && it != "null" }
                val positionMs = p.optLong("position_ms", 0L)
                val durationMs = p.optLong("duration_ms", 0L)
                val updatedAt = p.optLong("updated_at", 0L)
                val title = p.optString("title", "")
                for (target in resolveUris(uri, fingerprint)) {
                    val key = PositionStore.keyFor(target, durationMs)
                    val local = progressDao.get(key) ?: progressDao.latestForUri(target)
                    if (local != null && local.updatedAt >= updatedAt) continue
                    progressDao.upsert(ProgressEntity(key = key, uri = target, title = title, positionMs = positionMs, durationMs = durationMs, updatedAt = updatedAt))
                    positions++
                }
            }
        }

        var labelsAdded = 0
        root.optJSONArray("labels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val l = arr.optJSONObject(i) ?: continue
                val name = l.optString("name", "").trim()
                if (name.isBlank() || labelDao.byName(name) != null) continue
                labelDao.insert(
                    LabelEntity(
                        name = name,
                        hue = l.optInt("hue", LabelRepository.HUE_NEUTRAL).coerceIn(0, 5),
                        lockKids = l.optBoolean("lock_kids", false),
                        noInterruptions = l.optBoolean("no_interruptions", false),
                        shuffleOnOpen = l.optBoolean("shuffle_on_open", false),
                    ),
                )
                labelsAdded++
            }
        }

        var links = 0
        root.optJSONArray("media_labels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val link = arr.optJSONObject(i) ?: continue
                val label = labelDao.byName(link.optString("label", "")) ?: continue
                val fingerprint = link.optString("fingerprint", "").takeIf { it.isNotBlank() && it != "null" }
                for (target in resolveUris(link.optString("uri", ""), fingerprint)) {
                    if (label.id in labelDao.labelIdsFor(target)) continue
                    labelDao.attach(MediaLabelEntity(target, label.id))
                    links++
                }
            }
        }

        var bookmarks = 0
        root.optJSONArray("bookmarks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                val fingerprint = b.optString("fingerprint", "").takeIf { it.isNotBlank() && it != "null" }
                val positionMs = b.optLong("position_ms", -1L)
                if (positionMs < 0L) continue
                for (target in resolveUris(b.optString("uri", ""), fingerprint)) {
                    val existing = mediaDao.bookmarksFor(target)
                    if (existing.any { kotlin.math.abs(it.positionMs - positionMs) < 1_000L }) continue
                    mediaDao.insertBookmark(BookmarkEntity(mediaUri = target, positionMs = positionMs, createdAt = b.optLong("created_at", System.currentTimeMillis())))
                    bookmarks++
                }
            }
        }

        var kvAdded = 0
        root.optJSONObject("settings")?.let { kv ->
            val local = settings.allEntries().associate { it.k to it.v }
            val keys = kv.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k in SettingsRepository.deviceOnlyKeys || local.containsKey(k)) continue
                val v = kv.optString(k, "")
                progressDao.putKv(KvEntity(k, v))
                kvAdded++
            }
        }

        val parts = ArrayList<String>()
        parts += "$positions positions"
        if (labelsAdded > 0) parts += "$labelsAdded labels"
        if (links > 0) parts += "$links label links"
        if (bookmarks > 0) parts += "$bookmarks bookmarks"
        if (kvAdded > 0) parts += "$kvAdded settings"
        return "Restored " + parts.joinToString(", ")
    }
}
