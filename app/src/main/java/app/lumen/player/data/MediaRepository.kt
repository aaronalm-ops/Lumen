package app.lumen.player.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.graphics.ColorUtils
import androidx.documentfile.provider.DocumentFile
import androidx.palette.graphics.Palette
import app.lumen.player.data.drive.isDrive
import app.lumen.player.diag.RingLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** True for the folder rows the whole-phone scan writes (one per MediaStore bucket). */
val FolderEntity.isMediaStore: Boolean get() = uri.startsWith(MediaRepository.MEDIASTORE_PREFIX)

/**
 * The library's two sources and the files inside them.
 *
 * Whole phone: [scanMediaStore] queries the audio and video collections of MediaStore, which is
 * the index Android already keeps, so a scan of thousands of files is one cursor pass per
 * collection with no per-file reads. Each MediaStore folder becomes a `folders` row keyed
 * `mediastore:<relative path>` with its bucket display name.
 *
 * Picked folders: [scanFolder] walks a chosen tree with DocumentsContract (one query per
 * directory, not per file), then runs MediaMetadataRetriever only for files that are new or
 * whose size or modified stamp changed. This is the way in for folders Android keeps out of
 * MediaStore, such as app-private audio.
 *
 * Embedded art is read with the retriever, which is the slow part. The whole-phone scan reads it
 * for the newest [ART_AT_SCAN] music files only; every other row gets it on demand through
 * [ensureArt] the first time it is played. `art_path` null means "not looked yet", "" means
 * "looked, none", otherwise the cached JPEG.
 */
class MediaRepository(
    private val context: Context,
    private val dao: MediaDao,
    private val labels: LabelRepository,
    private val settings: SettingsRepository,
) {
    companion object {
        const val SPEECH_THRESHOLD_MS = 20L * 60 * 1000
        const val MEDIASTORE_PREFIX = "mediastore:"

        /** Mirrors DriveRepository.FOLDER_PREFIX; named here so this file needs no Drive import for it. */
        const val DRIVE_FOLDER_PREFIX = "drive:"

        /** Notification sounds and the like: anything shorter is not a listen. */
        const val MIN_DURATION_MS = 3_000L

        /** How many music files get their embedded art read during a whole-phone scan. */
        const val ART_AT_SCAN = 200

        /** Rescan MediaStore on app start when the last scan is older than this. */
        const val RESCAN_AFTER_MS = 6L * 60 * 60 * 1000

        private const val ART_MAX_PX = 512
        private const val UPSERT_BATCH = 100

        /** Relative paths the whole-phone scan leaves out, compared case-insensitively. */
        private val EXCLUDED_PREFIXES = listOf("Android/", "Ringtones/", "Notifications/", "Alarms/")

        /**
         * 0.4.0: the only places under Android/ the scan reads, compared case-insensitively:
         * WhatsApp's voice notes and audio (Android 11 and later keep them in Android/media),
         * for WhatsApp and WhatsApp Business. Everything else under Android/ stays excluded.
         */
        private val ALLOWED_ANDROID_PREFIXES = listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes/",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Audio/",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Voice Notes/",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Audio/",
        )

        /** Path segments that mark a WhatsApp voice note, wherever the WhatsApp folder sits. */
        private val VOICE_NOTE_SEGMENTS = listOf("WhatsApp Voice Notes/", "WhatsApp Business Voice Notes/")

        /**
         * The one Library folder every WhatsApp voice note is filed under, however many dated
         * subfolders WhatsApp spreads them over (the way Drive files sit under `drive:<id>`). It
         * keeps the `mediastore:` prefix, so the scan's bucket sync owns it like any other bucket.
         */
        const val VOICE_NOTES_FOLDER = MEDIASTORE_PREFIX + "whatsapp-voice-notes"
        const val VOICE_NOTES_NAME = "WhatsApp voice notes"

        /** A voice note longer than this resumes at its saved place, though it plays in the music layout. */
        const val VOICE_NOTE_RESUME_MS = 3L * 60 * 1000

        /** True when a MediaStore relative path lies where the scan may read it. */
        fun scanAllowed(relativeDir: String): Boolean {
            if (ALLOWED_ANDROID_PREFIXES.any { relativeDir.startsWith(it, ignoreCase = true) }) return true
            return EXCLUDED_PREFIXES.none { relativeDir.startsWith(it, ignoreCase = true) }
        }

        fun isVoiceNoteDir(relativeDir: String): Boolean =
            VOICE_NOTE_SEGMENTS.any { relativeDir.contains(it, ignoreCase = true) }

        /** A WhatsApp voice note, from the phone scan or from a picked folder. */
        fun isVoiceNote(media: MediaEntity): Boolean =
            media.folderUri == VOICE_NOTES_FOLDER || isVoiceNoteDir(media.relPath)

        /**
         * Whether an item resumes at its saved position (minus 3 s) or starts at 0. Speech always
         * resumes; so does a voice note over [VOICE_NOTE_RESUME_MS], even in the music layout.
         */
        fun resumes(media: MediaEntity, labelNames: List<String>, override: String?): Boolean =
            isSpeech(media, labelNames, override) || (isVoiceNote(media) && media.durationMs > VOICE_NOTE_RESUME_MS)

        fun fingerprintOf(sizeBytes: Long, durationMs: Long, title: String): String = "$sizeBytes|$durationMs|$title"

        /**
         * Speech layout: per-item override, then the Sermons label, then the 20 minute rule. The
         * rule covers WhatsApp voice notes too: under 20 minutes they take the music layout
         * (and resume by [resumes] when over 3 minutes).
         */
        fun isSpeech(media: MediaEntity, labelNames: List<String>, override: String?): Boolean = when (override) {
            "speech" -> true
            "music" -> false
            else -> labelNames.any { it.equals(LabelRepository.SERMONS, ignoreCase = true) } ||
                media.durationMs > SPEECH_THRESHOLD_MS
        }

        fun titleFromName(name: String): String = name.substringBeforeLast('.').ifBlank { name }

        /** The Suno rule: a path containing "Suno" or an artist of "Suno". */
        fun isSuno(media: MediaEntity): Boolean =
            media.relPath.contains("suno", ignoreCase = true) || media.artist.contains("suno", ignoreCase = true)

        /** "Sermons" for "Music/Sermons/", "Phone" for the storage root. */
        fun bucketNameOf(relativePath: String): String =
            relativePath.trim('/').substringAfterLast('/').ifBlank { "Phone" }
    }

    val folders: Flow<List<FolderEntity>> = dao.foldersFlow()
    val media: Flow<List<MediaEntity>> = dao.mediaFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Files seen so far by the running whole-phone scan; 0 when none is running. */
    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress

    private val scanLock = Mutex()

    private val artDir: File get() = File(context.filesDir, "art").apply { mkdirs() }
    private val artInFlight = HashSet<String>()
    private val artInFlightLock = Mutex()
    private val artReads = Semaphore(2)

    suspend fun folderList(): List<FolderEntity> = dao.folders()

    suspend fun byUri(uri: String): MediaEntity? = dao.mediaByUri(uri)

    suspend fun all(): List<MediaEntity> = dao.media()

    suspend fun count(): Int = dao.mediaCount()

    suspend fun mediaStoreCount(): Int = dao.mediaCountWithFolderPrefix(MEDIASTORE_PREFIX)

    fun bookmarks(uri: String): Flow<List<BookmarkEntity>> = dao.bookmarksFlow(uri)

    suspend fun addBookmark(uri: String, positionMs: Long) {
        dao.insertBookmark(BookmarkEntity(mediaUri = uri, positionMs = positionMs, createdAt = System.currentTimeMillis()))
    }

    suspend fun deleteBookmark(bookmark: BookmarkEntity) = dao.deleteBookmark(bookmark)

    fun chapters(uri: String): Flow<List<ChapterEntity>> = dao.chaptersFlow(uri)

    suspend fun addChapter(uri: String, positionMs: Long, title: String) {
        dao.insertChapter(
            ChapterEntity(mediaUri = uri, positionMs = positionMs.coerceAtLeast(0L), title = title, createdAt = System.currentTimeMillis()),
        )
    }

    suspend fun deleteChapter(chapter: ChapterEntity) = dao.deleteChapter(chapter)

    suspend fun bookmarksFor(uri: String): List<BookmarkEntity> = dao.bookmarksFor(uri)

    suspend fun chaptersFor(uri: String): List<ChapterEntity> = dao.chaptersFor(uri)

    // Picked folders (SAF).

    /** Takes the persistable permission, stores the folder and scans it. */
    suspend fun addFolder(treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: Throwable) {
            RingLog.w("scan", "persistable permission not granted for $treeUri: $t")
        }
        val name = withContext(Dispatchers.IO) {
            try {
                DocumentFile.fromTreeUri(context, treeUri)?.name
            } catch (t: Throwable) {
                null
            }
        } ?: treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Folder"
        val folder = FolderEntity(uri = treeUri.toString(), name = name, addedAt = System.currentTimeMillis())
        dao.upsertFolder(folder)
        RingLog.i("scan", "folder added: $name")
        scanFolder(folder)
    }

    suspend fun removeFolder(folderUri: String) {
        // Phone buckets come and go with the scan; the Drive folder is DriveRepository's.
        if (folderUri.startsWith(MEDIASTORE_PREFIX) || folderUri.startsWith(DRIVE_FOLDER_PREFIX)) return
        dao.deleteMediaInFolder(folderUri)
        dao.deleteFolder(folderUri)
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(folderUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: Throwable) {
            RingLog.w("scan", "release permission failed: $t")
        }
        RingLog.i("scan", "folder removed: $folderUri")
    }

    /** Rescans every picked folder. MediaStore buckets and the Drive folder are not trees to walk. */
    suspend fun rescanAll() {
        dao.folders().filter { !it.isMediaStore && !it.isDrive }.forEach { scanFolder(it) }
    }

    private data class Found(
        val uri: String,
        val name: String,
        val mime: String,
        val size: Long,
        val modified: Long,
        val relPath: String,
    )

    suspend fun scanFolder(folder: FolderEntity) = withContext(Dispatchers.IO) {
        if (folder.isMediaStore || folder.isDrive) return@withContext
        scanLock.withLock {
            _scanning.value = true
            try {
                val treeUri = Uri.parse(folder.uri)
                val found = ArrayList<Found>()
                val rootOk = walk(context.contentResolver, treeUri, DocumentsContract.getTreeDocumentId(treeUri), "", found, 0)
                if (!rootOk) {
                    // A revoked grant or an absent provider throws on the root query. An empty
                    // result here must not be taken as "every file is gone": keep the rows and
                    // their labels for when the folder is reachable again.
                    RingLog.w("scan", "${folder.name}: root query failed (permission=${hasReadPermission(treeUri)}), rows kept")
                    return@withLock
                }
                val existing = dao.mediaInFolder(folder.uri).associateBy { it.uri }
                val seen = HashSet<String>()
                var added = 0
                var updated = 0
                for (f in found) {
                    seen += f.uri
                    val old = existing[f.uri]
                    if (old != null && old.sizeBytes == f.size && old.modifiedAt == f.modified) continue
                    try {
                        val entity = readMetadata(folder, f, old)
                        dao.upsertMedia(entity)
                        applySunoRule(entity)
                        if (old == null) added++ else updated++
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        // One bad file must not stop the rest of the folder.
                        RingLog.w("scan", "skipped ${f.name}: $t")
                    }
                }
                val gone = existing.keys.filter { it !in seen }
                if (gone.isNotEmpty()) dao.deleteMedia(gone)
                RingLog.i("scan", "${folder.name}: ${found.size} files, $added added, $updated updated, ${gone.size} removed")
            } catch (t: Throwable) {
                RingLog.e("scan", "scan failed for ${folder.name}", t)
            } finally {
                _scanning.value = false
            }
        }
    }

    /** True when the tree's persisted read grant is still held. */
    private fun hasReadPermission(treeUri: Uri): Boolean = try {
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
    } catch (t: Throwable) {
        RingLog.w("scan", "permission check failed: $t")
        true
    }

    /** Lists one directory and recurses into its subdirectories. Returns false when the query itself failed. */
    private fun walk(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
        relPath: String,
        out: MutableList<Found>,
        depth: Int,
    ): Boolean {
        if (depth > 12) return true
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            resolver.query(childrenUri, projection, null, null, null)
        } catch (t: Throwable) {
            RingLog.w("scan", "query failed for $relPath: $t")
            null
        } ?: return false
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                val childRel = if (relPath.isEmpty()) name else "$relPath/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(resolver, treeUri, id, childRel, out, depth + 1)
                } else if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out += Found(docUri.toString(), name, mime, size, modified, childRel)
                }
            }
        }
        return true
    }

    private fun readMetadata(folder: FolderEntity, f: Found, old: MediaEntity?): MediaEntity {
        val retriever = MediaMetadataRetriever()
        var title = titleFromName(f.name)
        var artist = ""
        var duration = 0L
        var art: ByteArray? = null
        try {
            retriever.setDataSource(context, Uri.parse(f.uri))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.takeIf { it.isNotBlank() }?.let { title = it }
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (!f.mime.startsWith("video/") && duration <= SPEECH_THRESHOLD_MS) art = retriever.embeddedPicture
        } catch (t: Throwable) {
            RingLog.w("scan", "metadata failed for ${f.name}: $t")
        } finally {
            try {
                retriever.release()
            } catch (t: Throwable) {
                RingLog.w("scan", "retriever release: $t")
            }
        }
        val fingerprint = fingerprintOf(f.size, duration, title)
        // The retriever has been asked, so the row is marked "looked" either way.
        var artPath: String? = ""
        var tint: Int? = null
        if (art != null) {
            val cached = cacheArt(fingerprint, art)
            if (cached != null) {
                artPath = cached.first
                tint = cached.second
            }
        }
        return MediaEntity(
            uri = f.uri,
            folderUri = folder.uri,
            title = title,
            artist = artist,
            durationMs = duration,
            sizeBytes = f.size,
            dateAdded = old?.dateAdded ?: (if (f.modified > 0) f.modified else System.currentTimeMillis()),
            mime = f.mime,
            isVideo = f.mime.startsWith("video/"),
            fingerprint = fingerprint,
            relPath = f.relPath,
            artPath = artPath,
            tint = tint,
            modifiedAt = f.modified,
        )
    }

    // Whole phone (MediaStore).

    /** One MediaStore row before it becomes a MediaEntity. Stamps are already in milliseconds. */
    private class MsRow(
        val uri: String,
        val name: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val size: Long,
        val addedMs: Long,
        val modifiedMs: Long,
        val mime: String,
        val relativeDir: String,
        val bucket: String,
        val video: Boolean,
    ) {
        val voiceNote: Boolean get() = isVoiceNoteDir(relativeDir)

        /** Voice notes share one folder; every other row is filed under its own MediaStore bucket. */
        val folderUri: String get() = if (voiceNote) VOICE_NOTES_FOLDER else MEDIASTORE_PREFIX + relativeDir

        val folderName: String get() = if (voiceNote) VOICE_NOTES_NAME else bucket
    }

    /**
     * Queries MediaStore's audio and video collections and brings the `mediastore:` rows in the
     * `media` table into line with them: new and changed rows are written in batches, rows whose
     * ids are gone are removed, and picked-folder rows are never touched. Returns the number of
     * MediaStore rows in the library afterwards, or -1 when the permission is missing or a
     * collection could not be read at all (its rows are then kept as they were).
     */
    suspend fun scanMediaStore(): Int = withContext(Dispatchers.IO) {
        if (!MediaPermissions.granted(context)) {
            RingLog.w("scan", "mediastore: no audio permission, scan skipped")
            return@withContext -1
        }
        scanLock.withLock {
            _scanning.value = true
            _scanProgress.value = 0
            val startedAt = System.currentTimeMillis()
            try {
                val resolver = context.contentResolver
                val rows = ArrayList<MsRow>()
                val audioOk = queryCollection(resolver, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, video = false, out = rows)
                val videoWanted = MediaPermissions.videoGranted(context)
                val videoOk = videoWanted && queryCollection(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video = true, out = rows)
                if (!audioOk) {
                    RingLog.w("scan", "mediastore: audio query failed, rows kept")
                    return@withLock -1
                }
                if (videoWanted && !videoOk) RingLog.w("scan", "mediastore: video query failed, video rows kept")

                val existing = dao.mediaWithFolderPrefix(MEDIASTORE_PREFIX).associateBy { it.uri }
                val seen = HashSet<String>(rows.size * 2)
                val batch = ArrayList<MediaEntity>(UPSERT_BATCH)
                var artBudget = ART_AT_SCAN
                var added = 0
                var updated = 0
                var processed = 0
                for (row in rows) {
                    processed++
                    if (processed % 50 == 0) _scanProgress.value = processed
                    seen += row.uri
                    val old = existing[row.uri]
                    // A row whose folder changed (a voice note filed before 0.4.0) is rewritten.
                    if (old != null && old.sizeBytes == row.size && old.modifiedAt == row.modifiedMs && old.folderUri == row.folderUri) continue
                    try {
                        // Voice notes carry no art; they must not spend the art budget.
                        val wantArt = !row.video && !row.voiceNote && row.durationMs <= SPEECH_THRESHOLD_MS && artBudget > 0
                        if (wantArt) artBudget--
                        batch += entityFor(row, old, readArt = wantArt)
                        if (old == null) added++ else updated++
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        RingLog.w("scan", "mediastore: skipped ${row.name}: $t")
                    }
                    if (batch.size >= UPSERT_BATCH) flush(batch)
                }
                flush(batch)
                _scanProgress.value = processed

                // Rows whose ids are gone. A video table that was not read keeps its rows.
                val gone = existing.values
                    .filter { it.uri !in seen && (!it.isVideo || videoOk) }
                    .map { it.uri }
                if (gone.isNotEmpty()) gone.chunked(500).forEach { dao.deleteMedia(it) }

                // Buckets that only hold kept-but-unread video rows must keep their folder row.
                val keptFolderUris = existing.values
                    .filter { it.uri !in seen && it.isVideo && !videoOk }
                    .map { it.folderUri }
                    .toSet()
                syncBuckets(rows, keptFolderUris, dao.foldersWithPrefix(MEDIASTORE_PREFIX))

                val total = dao.mediaCountWithFolderPrefix(MEDIASTORE_PREFIX)
                settings.setMediaStoreScan(System.currentTimeMillis(), total)
                RingLog.i(
                    "scan",
                    "mediastore: ${rows.size} rows, $added added, $updated updated, ${gone.size} removed, " +
                        "$total in library, ${ART_AT_SCAN - artBudget} art reads, ${System.currentTimeMillis() - startedAt} ms",
                )
                total
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                RingLog.e("scan", "mediastore scan failed", t)
                -1
            } finally {
                _scanProgress.value = 0
                _scanning.value = false
            }
        }
    }

    private suspend fun flush(batch: MutableList<MediaEntity>) {
        if (batch.isEmpty()) return
        dao.upsertMediaAll(batch)
        for (entity in batch) applySunoRule(entity)
        batch.clear()
    }

    /**
     * One cursor pass over a collection, newest first so the art budget goes to recent files.
     * Rows under the excluded paths and rows shorter than [MIN_DURATION_MS] are dropped here.
     * Returns false when the query itself failed.
     */
    private fun queryCollection(resolver: ContentResolver, collection: Uri, video: Boolean, out: MutableList<MsRow>): Boolean {
        val projection = ArrayList<String>().apply {
            add(BaseColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.TITLE)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            if (!video) add(MediaStore.Audio.AudioColumns.ARTIST)
        }.toTypedArray()
        val cursor: Cursor = try {
            resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        } catch (t: Throwable) {
            RingLog.w("scan", "mediastore query failed for $collection: $t")
            null
        } ?: return false
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(BaseColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val artistCol = if (video) -1 else c.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.ARTIST)
            var excluded = 0
            var short = 0
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                val relativeDir = c.getString(relCol).orEmpty()
                if (!scanAllowed(relativeDir)) {
                    excluded++
                    continue
                }
                val duration = if (c.isNull(durationCol)) 0L else c.getLong(durationCol)
                // 0 means MediaStore has no duration yet; that is read with the retriever later,
                // so only a known short duration is dropped here.
                if (duration in 1 until MIN_DURATION_MS) {
                    short++
                    continue
                }
                val artist = if (artistCol < 0) "" else c.getString(artistCol)?.trim().orEmpty()
                out += MsRow(
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    name = name,
                    title = c.getString(titleCol)?.trim()?.takeIf { it.isNotBlank() } ?: titleFromName(name),
                    // MediaStore writes the literal "<unknown>" when a file has no artist tag.
                    artist = if (artist == MediaStore.UNKNOWN_STRING) "" else artist,
                    durationMs = duration,
                    size = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol),
                    addedMs = if (c.isNull(addedCol)) 0L else c.getLong(addedCol) * 1000L,
                    modifiedMs = if (c.isNull(modifiedCol)) 0L else c.getLong(modifiedCol) * 1000L,
                    mime = c.getString(mimeCol)?.takeIf { it.isNotBlank() } ?: if (video) "video/*" else "audio/*",
                    relativeDir = relativeDir,
                    bucket = c.getString(bucketCol)?.trim()?.takeIf { it.isNotBlank() } ?: bucketNameOf(relativeDir),
                    video = video,
                )
            }
            RingLog.i("scan", "mediastore ${if (video) "video" else "audio"}: ${c.count} rows, $excluded excluded, $short under 3 s")
        }
        return true
    }

    /** Builds the row. The retriever runs only for art within the budget, or for a missing duration. */
    private fun entityFor(row: MsRow, old: MediaEntity?, readArt: Boolean): MediaEntity {
        var duration = row.durationMs
        var art: ByteArray? = null
        var looked = false
        if (readArt || duration <= 0L) {
            looked = true
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(row.uri))
                if (duration <= 0L) {
                    duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
                if (readArt || (!row.video && !row.voiceNote && duration in 1..SPEECH_THRESHOLD_MS)) art = retriever.embeddedPicture
            } catch (t: Throwable) {
                RingLog.w("scan", "mediastore: metadata failed for ${row.name}: $t")
            } finally {
                try {
                    retriever.release()
                } catch (t: Throwable) {
                    RingLog.w("scan", "retriever release: $t")
                }
            }
        }
        val fingerprint = fingerprintOf(row.size, duration, row.title)
        // A voice note is marked "looked, none" so it is never read for art on play either.
        var artPath: String? = if (looked || row.voiceNote) "" else null
        var tint: Int? = null
        if (art != null) {
            val cached = cacheArt(fingerprint, art)
            if (cached != null) {
                artPath = cached.first
                tint = cached.second
            }
        }
        val now = System.currentTimeMillis()
        return MediaEntity(
            uri = row.uri,
            folderUri = row.folderUri,
            title = row.title,
            artist = row.artist,
            durationMs = duration,
            sizeBytes = row.size,
            dateAdded = when {
                row.addedMs > 0L -> row.addedMs
                row.modifiedMs > 0L -> row.modifiedMs
                else -> old?.dateAdded ?: now
            },
            mime = row.mime,
            isVideo = row.video,
            fingerprint = fingerprint,
            relPath = row.relativeDir + row.name,
            artPath = artPath,
            tint = tint,
            modifiedAt = row.modifiedMs,
        )
    }

    /** One `folders` row per bucket that still has files; buckets with none are removed. */
    private suspend fun syncBuckets(rows: List<MsRow>, keptFolderUris: Set<String>, existingFolders: List<FolderEntity>) {
        val byUri = existingFolders.associateBy { it.uri }
        val wanted = LinkedHashMap<String, String>()
        for (row in rows) {
            val uri = row.folderUri
            if (uri !in wanted) wanted[uri] = row.folderName
        }
        val now = System.currentTimeMillis()
        for ((uri, name) in wanted) {
            val old = byUri[uri]
            if (old == null) {
                dao.upsertFolder(FolderEntity(uri = uri, name = name, addedAt = now))
            } else if (old.name != name) {
                dao.upsertFolder(old.copy(name = name))
            }
        }
        // A bucket whose rows all failed the filters above still has no files in the library.
        val gone = existingFolders.filter { it.uri !in wanted && it.uri !in keptFolderUris }.map { it.uri }
        if (gone.isNotEmpty()) dao.deleteFolders(gone)
    }

    /**
     * Reads embedded art for one row that the scan skipped (`art_path` null). Called when the row
     * is about to be shown large or played; at most two reads run at once and a row is never read
     * twice. Rows that cannot carry art (video, speech length) are just marked as looked.
     */
    suspend fun ensureArt(uri: String) = withContext(Dispatchers.IO) {
        val row = dao.mediaByUri(uri) ?: return@withContext
        if (row.artPath != null) return@withContext
        if (row.isVideo || row.durationMs > SPEECH_THRESHOLD_MS) {
            dao.upsertMedia(row.copy(artPath = ""))
            return@withContext
        }
        val claimed = artInFlightLock.withLock { artInFlight.add(uri) }
        if (!claimed) return@withContext
        try {
            artReads.withPermit {
                val retriever = MediaMetadataRetriever()
                val bytes: ByteArray? = try {
                    retriever.setDataSource(context, Uri.parse(uri))
                    retriever.embeddedPicture
                } catch (t: Throwable) {
                    RingLog.w("scan", "art read failed for ${row.title}: $t")
                    null
                } finally {
                    try {
                        retriever.release()
                    } catch (t: Throwable) {
                        RingLog.w("scan", "retriever release: $t")
                    }
                }
                val cached = bytes?.let { cacheArt(row.fingerprint, it) }
                // Re-read so a scan that rewrote the row in the meantime is not undone.
                val fresh = dao.mediaByUri(uri) ?: return@withPermit
                if (fresh.artPath != null) return@withPermit
                dao.upsertMedia(fresh.copy(artPath = cached?.first ?: "", tint = cached?.second))
            }
        } finally {
            artInFlightLock.withLock { artInFlight.remove(uri) }
        }
    }

    /** Writes a 512 px JPEG under filesDir/art and returns its path with the desaturated tint. */
    private fun cacheArt(fingerprint: String, bytes: ByteArray): Pair<String, Int>? {
        return try {
            val file = File(artDir, sha1(fingerprint) + ".jpg")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > ART_MAX_PX * 2 || bounds.outHeight / sample > ART_MAX_PX * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val scaled = if (bitmap.width > ART_MAX_PX || bitmap.height > ART_MAX_PX) {
                val ratio = minOf(ART_MAX_PX.toFloat() / bitmap.width, ART_MAX_PX.toFloat() / bitmap.height)
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            } else bitmap
            if (!file.exists()) {
                FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
            val tint = tintOf(scaled)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            file.absolutePath to tint
        } catch (t: Throwable) {
            RingLog.w("scan", "art cache failed: $t")
            null
        }
    }

    /** DESIGN.md art tint: dominant colour, saturation clamped to 18 percent, lightness to 6 percent. */
    private fun tintOf(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val rgb = palette.dominantSwatch?.rgb ?: palette.vibrantSwatch?.rgb ?: palette.mutedSwatch?.rgb ?: 0xFF000000.toInt()
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(rgb, hsl)
        hsl[1] = hsl[1].coerceAtMost(0.18f)
        hsl[2] = 0.06f
        return ColorUtils.HSLToColor(hsl)
    }

    private suspend fun applySunoRule(media: MediaEntity) {
        if (!isSuno(media)) return
        val label = labels.ensureSuno()
        labels.attach(media.uri, label.id)
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }
}
