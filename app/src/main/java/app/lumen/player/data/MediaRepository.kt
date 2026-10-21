package app.lumen.player.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.graphics.ColorUtils
import androidx.documentfile.provider.DocumentFile
import androidx.palette.graphics.Palette
import app.lumen.player.diag.RingLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Chosen folders and the files inside them. Scanning walks each tree with DocumentsContract
 * (one query per directory, not per file), then runs MediaMetadataRetriever only for files that
 * are new or whose size or modified stamp changed.
 */
class MediaRepository(
    private val context: Context,
    private val dao: MediaDao,
    private val labels: LabelRepository,
) {
    companion object {
        const val SPEECH_THRESHOLD_MS = 20L * 60 * 1000
        private const val ART_MAX_PX = 512

        fun fingerprintOf(sizeBytes: Long, durationMs: Long, title: String): String = "$sizeBytes|$durationMs|$title"

        /** Speech layout: per-item override, then the Sermons label, then the 20 minute rule. */
        fun isSpeech(media: MediaEntity, labelNames: List<String>, override: String?): Boolean = when (override) {
            "speech" -> true
            "music" -> false
            else -> labelNames.any { it.equals(LabelRepository.SERMONS, ignoreCase = true) } ||
                media.durationMs > SPEECH_THRESHOLD_MS
        }

        fun titleFromName(name: String): String = name.substringBeforeLast('.').ifBlank { name }
    }

    val folders: Flow<List<FolderEntity>> = dao.foldersFlow()
    val media: Flow<List<MediaEntity>> = dao.mediaFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val scanLock = Mutex()

    private val artDir: File get() = File(context.filesDir, "art").apply { mkdirs() }

    suspend fun folderList(): List<FolderEntity> = dao.folders()

    suspend fun byUri(uri: String): MediaEntity? = dao.mediaByUri(uri)

    suspend fun all(): List<MediaEntity> = dao.media()

    suspend fun count(): Int = dao.mediaCount()

    fun bookmarks(uri: String): Flow<List<BookmarkEntity>> = dao.bookmarksFlow(uri)

    suspend fun addBookmark(uri: String, positionMs: Long) {
        dao.insertBookmark(BookmarkEntity(mediaUri = uri, positionMs = positionMs, createdAt = System.currentTimeMillis()))
    }

    suspend fun deleteBookmark(bookmark: BookmarkEntity) = dao.deleteBookmark(bookmark)

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
        dao.deleteMediaInFolder(folderUri)
        dao.deleteFolder(folderUri)
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(folderUri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: Throwable) {
            RingLog.w("scan", "release permission failed: $t")
        }
        RingLog.i("scan", "folder removed: $folderUri")
    }

    suspend fun rescanAll() {
        dao.folders().forEach { scanFolder(it) }
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
        scanLock.withLock {
            _scanning.value = true
            try {
                val treeUri = Uri.parse(folder.uri)
                val found = ArrayList<Found>()
                walk(context.contentResolver, treeUri, DocumentsContract.getTreeDocumentId(treeUri), "", found, 0)
                val existing = dao.mediaInFolder(folder.uri).associateBy { it.uri }
                val seen = HashSet<String>()
                var added = 0
                var updated = 0
                for (f in found) {
                    seen += f.uri
                    val old = existing[f.uri]
                    if (old != null && old.sizeBytes == f.size && old.modifiedAt == f.modified) continue
                    val entity = readMetadata(folder, f, old)
                    dao.upsertMedia(entity)
                    applySunoRule(entity)
                    if (old == null) added++ else updated++
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

    private fun walk(
        resolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
        relPath: String,
        out: MutableList<Found>,
        depth: Int,
    ) {
        if (depth > 12) return
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
        } ?: return
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
        var artPath: String? = null
        var tint: Int? = null
        if (art != null) {
            val cached = cacheArt(fingerprint, art)
            artPath = cached?.first
            tint = cached?.second
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
        val suno = media.relPath.contains("suno", ignoreCase = true)
        if (!suno) return
        val label = labels.ensureSuno()
        labels.attach(media.uri, label.id)
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }
}
