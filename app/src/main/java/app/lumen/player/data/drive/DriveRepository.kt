package app.lumen.player.data.drive

import android.content.Context
import app.lumen.player.data.FolderEntity
import app.lumen.player.data.LabelRepository
import app.lumen.player.data.MediaDao
import app.lumen.player.data.MediaEntity
import app.lumen.player.data.MediaRepository
import app.lumen.player.data.SettingsRepository
import app.lumen.player.diag.RingLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** True for the one folder row the Drive scan writes (`drive:<folderId>`). */
val FolderEntity.isDrive: Boolean get() = uri.startsWith(DriveRepository.FOLDER_PREFIX)

/** True for a Drive row (`drive://<fileId>`). */
val MediaEntity.isDrive: Boolean get() = uri.startsWith(DriveRepository.URI_PREFIX)

/**
 * The Drive source: one picked folder, listed with `files.list` into the same `media` table the
 * local sources use, so labels, positions and the Library treat a Drive row like any other.
 * Rows are keyed `drive://<fileId>` and sit under one folder row `drive:<folderId>` named
 * "Drive". Subfolders are listed one level down; their files carry the subfolder name in
 * `rel_path` so the Suno rule still applies. Durations are unknown until first play, when the
 * service writes the player's duration back into the row.
 *
 * "Keep offline" streams the file to filesDir/offline/<id>.<ext> and records the path in
 * `offline_path`; the player's data source then reads that file instead of Drive. Downloads wait
 * for an unmetered network unless "Allow downloads on mobile data" is on.
 */
class DriveRepository(
    private val context: Context,
    private val dao: MediaDao,
    private val labels: LabelRepository,
    private val settings: SettingsRepository,
    val auth: DriveAuth,
    val connectivity: Connectivity,
) {
    companion object {
        const val URI_PREFIX = "drive://"
        const val FOLDER_PREFIX = "drive:"
        const val FOLDER_NAME = "Drive"

        /** Rescan on app start when the last Drive scan is older than this. */
        const val RESCAN_AFTER_MS = 6L * 60 * 60 * 1000

        private const val DOWNLOAD_BUFFER = 64 * 1024

        fun uriFor(fileId: String): String = URI_PREFIX + fileId

        fun fileIdOf(uri: String): String = uri.removePrefix(URI_PREFIX)

        fun folderUriFor(folderId: String): String = FOLDER_PREFIX + folderId
    }

    /** The folder for offline copies; created on first use. */
    val offlineDir: File get() = File(context.filesDir, "offline").apply { mkdirs() }

    private val scanLock = Mutex()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Downloads in flight: uri to fraction 0..1 (0 while the size is still unknown). */
    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloads: StateFlow<Map<String, Float>> = _downloads
    private val downloadLock = Mutex()
    private val downloading = HashSet<String>()

    /** The local copy of a Drive file when it exists, by id (the row may lag behind by a moment). */
    fun offlineFile(fileId: String): File? {
        val dir = offlineDir
        val files = dir.listFiles { f -> f.isFile && f.name.substringBeforeLast('.') == fileId && !f.name.endsWith(".part") } ?: return null
        return files.firstOrNull()
    }

    // Connect, disconnect, scan.

    /** Called after the picker returned a folder. Stores it and scans at once. */
    suspend fun connectFolder(folderId: String, folderName: String) {
        val previous = settings.get(SettingsRepository.KEY_DRIVE_FOLDER_ID).orEmpty()
        if (previous.isNotBlank() && previous != folderId) {
            // A different folder replaces the old rows; a re-pick of the same one keeps them.
            dao.deleteMediaInFolder(folderUriFor(previous))
            dao.deleteFolder(folderUriFor(previous))
        }
        settings.setDriveFolder(folderId, folderName.ifBlank { FOLDER_NAME })
        settings.setDriveNeedsSignIn(false)
        dao.upsertFolder(FolderEntity(uri = folderUriFor(folderId), name = FOLDER_NAME, addedAt = System.currentTimeMillis()))
        RingLog.i("drive", "folder connected: $folderName ($folderId)")
        scan()
    }

    /** Removes the Drive rows and the folder; offline copies are deleted too. */
    suspend fun disconnect() {
        val folderId = settings.get(SettingsRepository.KEY_DRIVE_FOLDER_ID).orEmpty()
        if (folderId.isNotBlank()) {
            dao.deleteMediaInFolder(folderUriFor(folderId))
            dao.deleteFolder(folderUriFor(folderId))
        }
        settings.setDriveFolder("", "")
        settings.setDriveScan(0L, 0)
        settings.setDriveNeedsSignIn(false)
        auth.forget()
        withContext(Dispatchers.IO) { offlineDir.listFiles()?.forEach { it.delete() } }
        RingLog.i("drive", "disconnected")
    }

    /**
     * Lists the picked folder and one level of subfolders and brings the `drive://` rows into
     * line. Returns the row count, or -1 when the token or the listing failed (rows kept).
     */
    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        val folderId = settings.get(SettingsRepository.KEY_DRIVE_FOLDER_ID).orEmpty()
        if (folderId.isBlank()) return@withContext -1
        if (!connectivity.isOnline()) {
            RingLog.i("drive", "scan skipped: offline")
            return@withContext -1
        }
        scanLock.withLock {
            _scanning.value = true
            val startedAt = System.currentTimeMillis()
            var usedToken: String? = null
            try {
                val token = auth.tokenBlocking() ?: run {
                    settings.setDriveNeedsSignIn(true)
                    return@withLock -1
                }
                usedToken = token
                val folderUri = folderUriFor(folderId)
                val top = DriveApi.listChildren(token, folderId)
                val found = ArrayList<Pair<DriveFile, String>>()
                top.filter { it.isMedia }.forEach { found += it to it.name }
                val subfolders = top.filter { it.isFolder }
                for (sub in subfolders) {
                    try {
                        DriveApi.listChildren(token, sub.id).filter { it.isMedia }.forEach { found += it to "${sub.name}/${it.name}" }
                    } catch (t: DriveAuthException) {
                        throw t
                    } catch (t: Throwable) {
                        RingLog.w("drive", "subfolder ${sub.name} failed: $t")
                    }
                }
                val existing = dao.mediaInFolder(folderUri).associateBy { it.uri }
                val seen = HashSet<String>()
                val batch = ArrayList<MediaEntity>()
                var added = 0
                var updated = 0
                for ((file, relPath) in found) {
                    val uri = uriFor(file.id)
                    seen += uri
                    val old = existing[uri]
                    if (old != null && old.sizeBytes == file.size && old.modifiedAt == file.modifiedMs) continue
                    val title = MediaRepository.titleFromName(file.name)
                    val duration = old?.durationMs ?: 0L
                    batch += MediaEntity(
                        uri = uri,
                        folderUri = folderUri,
                        title = title,
                        artist = old?.artist.orEmpty(),
                        durationMs = duration,
                        sizeBytes = file.size,
                        dateAdded = old?.dateAdded ?: (if (file.createdMs > 0) file.createdMs else System.currentTimeMillis()),
                        mime = file.mimeType,
                        isVideo = file.mimeType.startsWith("video/"),
                        fingerprint = MediaRepository.fingerprintOf(file.size, duration, title),
                        relPath = relPath,
                        artPath = "",
                        tint = null,
                        modifiedAt = file.modifiedMs,
                        offlinePath = old?.offlinePath,
                    )
                    if (old == null) added++ else updated++
                }
                if (batch.isNotEmpty()) {
                    dao.upsertMediaAll(batch)
                    for (entity in batch) applySunoRule(entity)
                }
                val gone = existing.keys.filter { it !in seen }
                if (gone.isNotEmpty()) {
                    gone.chunked(500).forEach { dao.deleteMedia(it) }
                    for (uri in gone) existing[uri]?.offlinePath?.let { File(it).delete() }
                }
                val total = found.size
                settings.setDriveScan(System.currentTimeMillis(), total)
                settings.setDriveNeedsSignIn(false)
                // The open spike: drive.file sees what the user picked (or the app made). Whether
                // files added to the picked folder later show up is read off this count.
                RingLog.i(
                    "drive",
                    "scan: $total files (${top.size} top-level entries, ${subfolders.size} subfolders), " +
                        "$added added, $updated updated, ${gone.size} removed, ${System.currentTimeMillis() - startedAt} ms",
                )
                total
            } catch (t: CancellationException) {
                throw t
            } catch (t: DriveAuthException) {
                RingLog.w("drive", "scan refused with ${t.code}; sign in again")
                settings.setDriveNeedsSignIn(true)
                if (t.code == 401) auth.invalidate(usedToken)
                -1
            } catch (t: Throwable) {
                RingLog.e("drive", "scan failed", t)
                -1
            } finally {
                _scanning.value = false
            }
        }
    }

    private suspend fun applySunoRule(media: MediaEntity) {
        if (!MediaRepository.isSuno(media)) return
        val label = labels.ensureSuno()
        labels.attach(media.uri, label.id)
    }

    /** Called by the service once the player knows the duration of a Drive row. */
    suspend fun setDuration(uri: String, durationMs: Long) {
        if (durationMs <= 0L) return
        val row = dao.mediaByUri(uri) ?: return
        if (row.durationMs == durationMs) return
        dao.setDuration(uri, durationMs, MediaRepository.fingerprintOf(row.sizeBytes, durationMs, row.title))
        RingLog.i("drive", "duration learned for ${row.title}: $durationMs ms")
    }

    /** Marks the token bad after a 401 or 403 from the player; Settings shows the one line. */
    suspend fun onAuthFailure(code: Int) {
        RingLog.w("drive", "playback refused with $code")
        settings.setDriveNeedsSignIn(true)
    }

    // Offline copies.

    /** Why a download cannot start right now, or null when it can. */
    suspend fun downloadBlocker(): String? {
        if (!connectivity.isOnline()) return "Offline"
        val allowMobile = settings.get(SettingsRepository.KEY_DRIVE_MOBILE_DOWNLOADS) == "1"
        if (!allowMobile && !connectivity.isUnmetered()) return "Waiting for Wi-Fi"
        return null
    }

    /**
     * Downloads one Drive row to filesDir/offline. Progress is published on [downloads]; the
     * row's `offline_path` is set only when the whole file has arrived. Returns false when it
     * did not start or did not finish.
     */
    suspend fun keepOffline(uri: String): Boolean = withContext(Dispatchers.IO) {
        if (!uri.startsWith(URI_PREFIX)) return@withContext false
        val row = dao.mediaByUri(uri) ?: return@withContext false
        if (row.offlinePath != null && File(row.offlinePath).exists()) return@withContext true
        val blocker = downloadBlocker()
        if (blocker != null) {
            RingLog.i("drive", "download of ${row.title} not started: $blocker")
            return@withContext false
        }
        val claimed = downloadLock.withLock { downloading.add(uri) }
        if (!claimed) return@withContext false
        publishProgress(uri, 0f)
        val id = fileIdOf(uri)
        val ext = row.relPath.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 } ?: if (row.isVideo) "mp4" else "mp3"
        val target = File(offlineDir, "$id.$ext")
        val part = File(offlineDir, "$id.$ext.part")
        var usedToken: String? = null
        try {
            val token = auth.tokenBlocking() ?: run {
                settings.setDriveNeedsSignIn(true)
                return@withContext false
            }
            usedToken = token
            val (connection, input) = DriveApi.openMedia(token, id)
            try {
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: row.sizeBytes
                var received = 0L
                var lastPublished = 0f
                input.use { stream ->
                    FileOutputStream(part).use { out ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            received += n
                            if (total > 0) {
                                val fraction = (received.toFloat() / total).coerceIn(0f, 1f)
                                if (fraction - lastPublished >= 0.02f) {
                                    lastPublished = fraction
                                    publishProgress(uri, fraction)
                                }
                            }
                        }
                    }
                }
                if (total > 0 && received < total) throw IOException("short download: $received of $total bytes")
            } finally {
                connection.disconnect()
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("rename failed for ${target.name}")
            dao.setOfflinePath(uri, target.absolutePath)
            RingLog.i("drive", "kept offline: ${row.title}, ${target.length()} bytes")
            true
        } catch (t: CancellationException) {
            part.delete()
            throw t
        } catch (t: DriveAuthException) {
            part.delete()
            settings.setDriveNeedsSignIn(true)
            if (t.code == 401) auth.invalidate(usedToken)
            RingLog.w("drive", "download refused with ${t.code}")
            false
        } catch (t: Throwable) {
            part.delete()
            RingLog.e("drive", "download failed for ${row.title}", t)
            false
        } finally {
            // Runs on cancellation too: the lock must still be taken, or the uri stays "in flight".
            withContext(NonCancellable) { downloadLock.withLock { downloading.remove(uri) } }
            clearProgress(uri)
        }
    }

    /** Deletes the local copy and goes back to streaming. */
    suspend fun removeOffline(uri: String) = withContext(Dispatchers.IO) {
        val row = dao.mediaByUri(uri) ?: return@withContext
        row.offlinePath?.let { File(it).delete() }
        offlineFile(fileIdOf(uri))?.delete()
        dao.setOfflinePath(uri, null)
        RingLog.i("drive", "download removed: ${row.title}")
    }

    private fun publishProgress(uri: String, fraction: Float) {
        _downloads.value = _downloads.value + (uri to fraction)
    }

    private fun clearProgress(uri: String) {
        _downloads.value = _downloads.value - uri
    }

    /** One line for device.txt. */
    suspend fun describe(): String {
        val id = settings.get(SettingsRepository.KEY_DRIVE_FOLDER_ID).orEmpty()
        if (id.isBlank()) return "drive: not connected\n"
        val name = settings.get(SettingsRepository.KEY_DRIVE_FOLDER_NAME).orEmpty()
        val at = settings.get(SettingsRepository.KEY_DRIVE_SCAN_AT)?.toLongOrNull() ?: 0L
        val count = settings.get(SettingsRepository.KEY_DRIVE_SCAN_COUNT)?.toIntOrNull() ?: 0
        val needsSignIn = settings.get(SettingsRepository.KEY_DRIVE_NEEDS_SIGN_IN) == "1"
        val offline = offlineDir.listFiles()?.count { it.isFile && !it.name.endsWith(".part") } ?: 0
        return "drive: folder \"$name\" ($id), last scan ${if (at > 0) "$at, $count files" else "never"}, " +
            "needs sign in $needsSignIn, $offline offline copies\n"
    }
}
