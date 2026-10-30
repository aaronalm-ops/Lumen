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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** True for the one folder row the Drive scan writes (`drive:<folderId>`). */
val FolderEntity.isDrive: Boolean get() = uri.startsWith(DriveRepository.FOLDER_PREFIX)

/** True for a Drive row (`drive://<fileId>`). */
val MediaEntity.isDrive: Boolean get() = uri.startsWith(DriveRepository.URI_PREFIX)

/**
 * The Drive source: one chosen folder, listed with `files.list` into the same `media` table the
 * local sources use, so labels, positions and the Library treat a Drive row like any other.
 * Rows are keyed `drive://<fileId>` and all sit under one folder row `drive:<folderId>` named
 * after the chosen folder (cloud icon in the Library), however deep the file was found.
 * Subfolders are walked down to [MAX_DEPTH] levels; their files carry the subfolder path in
 * `rel_path` so search and the Suno rule still see it. Durations are unknown until first play,
 * when the service writes the player's duration back into the row.
 *
 * The folder is chosen in Lumen's own browser ([browseTop], [browse], [countMedia]), which needs
 * drive.readonly; drive.file alone (0.3.0 to 0.3.2) only saw the folder, never its contents.
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
        const val FOLDER_NAME = "Google Drive"

        /** Rescan on app start when the last Drive scan is older than this. */
        const val RESCAN_AFTER_MS = 6L * 60 * 60 * 1000

        /** Subfolder levels walked below the chosen folder. */
        const val MAX_DEPTH = 4

        /** A ceiling on folders listed per scan, so a huge tree cannot run for minutes. */
        const val MAX_FOLDERS = 500

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

    // Grant state.

    /**
     * A token, with the Settings notices brought into line: no token sets "Sign in to Drive
     * again"; a token without drive.readonly sets "Reconnect Drive to see the files in your
     * folder"; a full token clears the reconnect line. Each flag is written only when it changes.
     */
    private suspend fun tokenWithFlags(forceRefresh: Boolean = false): String? {
        val token = auth.tokenBlocking(forceRefresh)
        if (token == null) {
            setFlag(SettingsRepository.KEY_DRIVE_NEEDS_SIGN_IN, true)
            return null
        }
        setFlag(SettingsRepository.KEY_DRIVE_NEEDS_RECONNECT, !auth.hasReadonly)
        return token
    }

    private suspend fun setFlag(key: String, on: Boolean) {
        if ((settings.get(key) == "1") == on) return
        when (key) {
            SettingsRepository.KEY_DRIVE_NEEDS_SIGN_IN -> settings.setDriveNeedsSignIn(on)
            SettingsRepository.KEY_DRIVE_NEEDS_RECONNECT -> settings.setDriveNeedsReconnect(on)
            else -> settings.put(key, if (on) "1" else "0")
        }
    }

    /**
     * Called on app start while a folder is connected: fetches a token silently so a grant from
     * 0.3.0 to 0.3.2 (drive.file only) shows the reconnect line at once. No UI.
     */
    suspend fun checkGrant() {
        withContext(Dispatchers.IO) {
            if (!connectivity.isOnline()) return@withContext
            val token = tokenWithFlags()
            RingLog.i("drive", "grant check: ${if (token == null) "no token" else if (auth.hasReadonly) "drive.readonly held" else "drive.readonly missing"}")
        }
    }

    /** The interactive sign-in came back: clear the lines that it answers. */
    suspend fun onSignedIn(hasReadonly: Boolean) {
        setFlag(SettingsRepository.KEY_DRIVE_NEEDS_SIGN_IN, false)
        setFlag(SettingsRepository.KEY_DRIVE_NEEDS_RECONNECT, !hasReadonly)
    }

    // Connect, disconnect, scan.

    /** Called when the browser's "Use this folder" was tapped. Stores the folder and scans at once. */
    suspend fun connectFolder(folderId: String, folderName: String) {
        val previous = settings.get(SettingsRepository.KEY_DRIVE_FOLDER_ID).orEmpty()
        if (previous.isNotBlank() && previous != folderId) {
            // A different folder replaces the old rows; a re-pick of the same one keeps them.
            dao.deleteMediaInFolder(folderUriFor(previous))
            dao.deleteFolder(folderUriFor(previous))
        }
        val name = folderName.ifBlank { FOLDER_NAME }
        settings.setDriveFolder(folderId, name)
        settings.setDriveScan(0L, 0)
        settings.setDriveNeedsSignIn(false)
        dao.upsertFolder(FolderEntity(uri = folderUriFor(folderId), name = name, addedAt = System.currentTimeMillis()))
        RingLog.i("drive", "folder connected: $name ($folderId)")
        scan()
    }

    /** Removes every Drive row and folder; offline copies are deleted too. Progress rows stay. */
    suspend fun disconnect() {
        val stale = dao.mediaWithFolderPrefix(FOLDER_PREFIX).map { it.uri }
        stale.chunked(500).forEach { dao.deleteMedia(it) }
        val folders = dao.foldersWithPrefix(FOLDER_PREFIX).map { it.uri }
        if (folders.isNotEmpty()) dao.deleteFolders(folders)
        settings.setDriveFolder("", "")
        settings.setDriveScan(0L, 0)
        settings.setDriveNeedsSignIn(false)
        settings.setDriveNeedsReconnect(false)
        settings.setDriveAccount("")
        auth.forget()
        withContext(Dispatchers.IO) { offlineDir.listFiles()?.forEach { it.delete() } }
        RingLog.i("drive", "disconnected")
    }

    /**
     * Drive folders other than the chosen one (left by the 0.3.0 picker or a change of folder)
     * and their media rows go, with any kept copies. Progress rows are never touched.
     */
    private suspend fun removeOtherDriveFolders(keepUri: String) {
        val staleRows = dao.mediaWithFolderPrefix(FOLDER_PREFIX).filter { it.folderUri != keepUri }
        val staleFolders = dao.foldersWithPrefix(FOLDER_PREFIX).map { it.uri }.filter { it != keepUri }
        if (staleRows.isEmpty() && staleFolders.isEmpty()) return
        staleRows.map { it.uri }.chunked(500).forEach { dao.deleteMedia(it) }
        staleRows.forEach { row -> row.offlinePath?.let { File(it).delete() } }
        if (staleFolders.isNotEmpty()) dao.deleteFolders(staleFolders)
        RingLog.i("drive", "removed ${staleFolders.size} old Drive folders and ${staleRows.size} of their rows")
    }

    /**
     * Lists the chosen folder and its subfolders down to [MAX_DEPTH] levels and brings the
     * `drive://` rows into line, all under the chosen folder's one folder row. Returns the row
     * count, or -1 when the token or the listing failed (rows kept). When a subfolder could not
     * be listed, or the [MAX_FOLDERS] ceiling was reached, nothing is removed on that pass.
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
                val token = tokenWithFlags() ?: return@withLock -1
                usedToken = token
                if (!auth.hasReadonly) {
                    RingLog.w("drive", "scan skipped: the grant lacks drive.readonly; Reconnect in Settings")
                    return@withLock -1
                }
                val folderUri = folderUriFor(folderId)
                val meta = DriveApi.getFile(token, folderId)
                val driveId = meta?.driveId.orEmpty()
                val storedName = settings.get(SettingsRepository.KEY_DRIVE_FOLDER_NAME).orEmpty()
                val folderName = meta?.name?.takeIf { it.isNotBlank() } ?: storedName.ifBlank { FOLDER_NAME }
                if (folderName != storedName) settings.setDriveFolder(folderId, folderName)
                removeOtherDriveFolders(folderUri)
                val folderRow = dao.folders().firstOrNull { it.uri == folderUri }
                if (folderRow == null || folderRow.name != folderName) {
                    dao.upsertFolder(FolderEntity(uri = folderUri, name = folderName, addedAt = folderRow?.addedAt ?: System.currentTimeMillis()))
                }

                // Breadth first, one files.list per folder (all pages). A file reachable twice
                // (several parents) is kept once, with the first path it was seen under.
                val found = LinkedHashMap<String, Pair<DriveFile, String>>()
                val visited = hashSetOf(folderId)
                var level: List<Pair<String, String>> = listOf(folderId to "")
                var depth = 0
                var walked = 0
                var partial = false
                var topEntries = 0
                var notWalked = 0
                while (level.isNotEmpty()) {
                    val next = ArrayList<Pair<String, String>>()
                    for ((id, prefix) in level) {
                        val children = try {
                            DriveApi.listChildren(token, id, driveId)
                        } catch (t: CancellationException) {
                            throw t
                        } catch (t: DriveAuthException) {
                            throw t
                        } catch (t: Throwable) {
                            if (id == folderId) throw t
                            RingLog.w("drive", "subfolder $prefix failed: $t")
                            partial = true
                            continue
                        }
                        if (id == folderId) topEntries = children.size else walked++
                        for (file in children) {
                            if (file.isMedia && !found.containsKey(file.id)) found[file.id] = file to prefix + file.name
                        }
                        val subfolders = children.filter { it.isFolder }
                        if (depth >= MAX_DEPTH) {
                            notWalked += subfolders.size
                            continue
                        }
                        for (sub in subfolders) {
                            if (!visited.add(sub.id)) continue
                            if (visited.size > MAX_FOLDERS) {
                                partial = true
                                break
                            }
                            next += sub.id to "$prefix${sub.name}/"
                        }
                    }
                    level = next
                    depth++
                }
                if (visited.size > MAX_FOLDERS) RingLog.w("drive", "folder ceiling of $MAX_FOLDERS reached; the rest is not listed")
                if (notWalked > 0) RingLog.i("drive", "$notWalked subfolders deeper than $MAX_DEPTH levels were not walked")

                val existing = dao.mediaInFolder(folderUri).associateBy { it.uri }
                val seen = HashSet<String>()
                val batch = ArrayList<MediaEntity>()
                var added = 0
                var updated = 0
                val addedUris = ArrayList<String>()
                for ((file, relPath) in found.values) {
                    val uri = uriFor(file.id)
                    seen += uri
                    val old = existing[uri]
                    if (old != null && old.sizeBytes == file.size && old.modifiedAt == file.modifiedMs && old.relPath == relPath) continue
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
                    if (old == null) {
                        added++
                        addedUris += uri
                    } else {
                        updated++
                    }
                }
                if (batch.isNotEmpty()) {
                    dao.upsertMediaAll(batch)
                    for (entity in batch) applySunoRule(entity)
                }
                val gone = if (partial) emptyList() else existing.keys.filter { it !in seen }
                if (gone.isNotEmpty()) {
                    gone.chunked(500).forEach { dao.deleteMedia(it) }
                    for (uri in gone) existing[uri]?.offlinePath?.let { File(it).delete() }
                }
                val total = found.size
                settings.setDriveScan(System.currentTimeMillis(), total)
                setFlag(SettingsRepository.KEY_DRIVE_NEEDS_SIGN_IN, false)
                setFlag(SettingsRepository.KEY_DRIVE_NEEDS_RECONNECT, false)
                if (settings.get(SettingsRepository.KEY_DRIVE_ACCOUNT).isNullOrBlank()) {
                    val email = try {
                        DriveApi.accountEmail(token)
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        RingLog.w("drive", "account email not read: $t")
                        ""
                    }
                    if (email.isNotBlank()) settings.setDriveAccount(email)
                }
                // 0.8.2: new sermons are kept on the phone when the owner asked for that. A first
                // listing (nothing there before) is left alone, so connecting a folder never
                // starts gigabytes of downloads.
                if (addedUris.isNotEmpty() && existing.isNotEmpty() && settings.get(SettingsRepository.KEY_DRIVE_AUTO_KEEP) == "1") {
                    val uris = addedUris.toList()
                    autoScope.launch { keepNewSermons(onlyRecent = true, uris = uris) }
                }
                RingLog.i(
                    "drive",
                    "scan: $total files ($topEntries top-level entries, $walked subfolders walked, depth limit $MAX_DEPTH" +
                        "${if (partial) ", partial" else ""}), $added added, $updated updated, ${gone.size} removed, " +
                        "${System.currentTimeMillis() - startedAt} ms",
                )
                total
            } catch (t: CancellationException) {
                throw t
            } catch (t: DriveAuthException) {
                if (t.insufficientScope) {
                    RingLog.w("drive", "scan refused with ${t.code} ${t.reason}; reconnect for drive.readonly")
                    setFlag(SettingsRepository.KEY_DRIVE_NEEDS_RECONNECT, true)
                } else {
                    RingLog.w("drive", "scan refused with ${t.code} ${t.reason}; sign in again")
                    settings.setDriveNeedsSignIn(true)
                }
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

    // The folder browser.

    /**
     * Runs one browser call with a token that carries drive.readonly. A 401 drops the token and
     * tries once more with a fresh one; a missing scope throws [DriveReconnectException] and sets
     * the reconnect line.
     */
    private suspend fun <T> withReadToken(block: (String) -> T): T = withContext(Dispatchers.IO) {
        val token = tokenWithFlags() ?: throw DriveAuthException(401, "noToken")
        if (!auth.hasReadonly) throw DriveReconnectException()
        try {
            block(token)
        } catch (e: DriveAuthException) {
            if (e.insufficientScope) {
                setFlag(SettingsRepository.KEY_DRIVE_NEEDS_RECONNECT, true)
                throw DriveReconnectException()
            }
            if (e.code != 401) throw e
            auth.invalidate(token)
            val fresh = tokenWithFlags(forceRefresh = true) ?: throw e
            block(fresh)
        }
    }

    /** The browser's top level: My Drive, Shared with me, then each shared drive by name. */
    suspend fun browseTop(): List<DriveNode> = withReadToken { token ->
        val drives = DriveApi.listSharedDrives(token)
        listOf(DriveNode.MyDrive, DriveNode.SharedWithMe) +
            drives.map { DriveNode(id = it.id, name = it.name.ifBlank { "Shared drive" }, kind = DriveNodeKind.SharedDrive, driveId = it.id) }
    }

    /** The folders inside [node], by name. */
    suspend fun browse(node: DriveNode): List<DriveNode> = withReadToken { token ->
        val folders = when (node.kind) {
            DriveNodeKind.SharedWithMe -> DriveApi.listSharedWithMeFolders(token)
            else -> DriveApi.listFolders(token, node.id, node.driveId)
        }
        folders.map { DriveNode(id = it.id, name = it.name, kind = DriveNodeKind.Folder, driveId = it.driveId.ifBlank { node.driveId }) }
    }

    /** Audio and video directly inside [node] (one page of up to 1000), for the row's count. */
    suspend fun countMedia(node: DriveNode): MediaCount = withReadToken { token ->
        DriveApi.countMedia(token, node.id, node.driveId)
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
        if (code == 403 && !auth.hasReadonly) setFlag(SettingsRepository.KEY_DRIVE_NEEDS_RECONNECT, true)
        else settings.setDriveNeedsSignIn(true)
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
            if (t.insufficientScope) setFlag(SettingsRepository.KEY_DRIVE_NEEDS_RECONNECT, true) else settings.setDriveNeedsSignIn(true)
            if (t.code == 401) auth.invalidate(usedToken)
            RingLog.w("drive", "download refused with ${t.code} ${t.reason}")
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

    private val autoScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /**
     * 0.8.2: "Download new sermons automatically". After a listing, the sermons it found new
     * ([uris]); when the setting is first turned on, the three newest sermons not yet kept. One at
     * a time, each waiting for Wi-Fi unless mobile downloads are allowed.
     */
    suspend fun keepNewSermons(onlyRecent: Boolean, uris: List<String> = emptyList()) {
        val rows = if (onlyRecent) uris.mapNotNull { dao.mediaByUri(it) }
        else dao.mediaWithFolderPrefix(FOLDER_PREFIX).sortedByDescending { it.dateAdded }
        val sermons = rows.filter { it.offlinePath == null && !it.isVideo && (it.durationMs > MediaRepository.SPEECH_THRESHOLD_MS || MediaRepository.looksLikeSpeechBySize(it)) }
            .let { if (onlyRecent) it else it.take(3) }
        if (sermons.isEmpty()) return
        RingLog.i("drive", "keeping ${sermons.size} new sermons offline")
        for (row in sermons) {
            if (downloadBlocker() != null) {
                RingLog.i("drive", "automatic downloads paused: ${downloadBlocker()}")
                return
            }
            keepOffline(row.uri)
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
        val needsReconnect = settings.get(SettingsRepository.KEY_DRIVE_NEEDS_RECONNECT) == "1"
        val account = settings.get(SettingsRepository.KEY_DRIVE_ACCOUNT).orEmpty().ifBlank { "unknown" }
        val offline = offlineDir.listFiles()?.count { it.isFile && !it.name.endsWith(".part") } ?: 0
        return "drive: folder \"$name\" ($id), account $account, last scan ${if (at > 0) "$at, $count files" else "never"}, " +
            "needs sign in $needsSignIn, needs reconnect $needsReconnect, readonly in last token ${auth.hasReadonly}, " +
            "$offline offline copies\n"
    }
}
