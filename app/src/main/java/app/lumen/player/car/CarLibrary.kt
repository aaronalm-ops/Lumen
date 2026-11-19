package app.lumen.player.car

import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import app.lumen.player.data.LabelRepository
import app.lumen.player.data.MediaEntity
import app.lumen.player.data.MediaRepository
import app.lumen.player.data.NaturalOrder
import app.lumen.player.data.PositionStore
import app.lumen.player.data.drive.isDrive
import app.lumen.player.data.ProgressEntity
import app.lumen.player.notes.NotesRepository
import app.lumen.player.playback.MediaItems
import app.lumen.player.remote.YtmLink

/**
 * 0.10.0: the fourth tab is Library: Recent, the phone's Folders and Drive (Android Auto shows at
 * most four tabs). Folders list in natural order (Part 2 before Part 10) with Play all and Shuffle.
 *
 * 0.9.0: what Lumen shows in Android Auto. Four tabs (Carry on, Sermons, Playlists, Recent),
 * Lumen tiles where there is no cover, played and part-played markers, sermon groups, Play all
 * and Shuffle rows, and search over titles and what was said. YouTube Music rows stay out: they
 * play in YouTube Music's own car app.
 */
@UnstableApi
internal class CarLibrary(
    private val context: Context,
    private val media: MediaRepository,
    private val labels: LabelRepository,
    private val store: PositionStore,
    private val notes: NotesRepository,
    private val itemFor: suspend (MediaEntity) -> MediaItem,
) {
    data class Expanded(val items: List<MediaItem>, val startIndex: Int, val startPositionMs: Long)

    /** Root hints: tabs show their children as lists unless an item asks otherwise. */
    fun rootExtras(): Bundle = Bundle().apply {
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }

    fun rootItem(): MediaItem = folder(ROOT_ID, "Lumen", null, null, null)

    suspend fun children(parentId: String): List<MediaItem> = when {
        // Tabs stay text-only: Android Auto expects monochrome tab icons, which tiles are not.
        parentId == ROOT_ID -> listOf(
            folder(CONTINUE_ID, "Carry on", null, null, playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
            folder(SERMONS_ID, "Sermons", null, null, playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
            folder(LABELS_ID, "Playlists", null, null, browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM),
            folder(LIBRARY_ID, "Library", null, null, browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM),
        )
        parentId == LIBRARY_ID -> buildList {
            add(folder(RECENT_ID, "Recent", "The last things you played", CarArt.tile(context, "Recent", CarArt.Accent.CYAN), playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM))
            val phone = phoneFolders()
            if (phone.isNotEmpty()) add(folder(FOLDERS_ID, "Folders", "${phone.size} on this phone", CarArt.tile(context, "Folders", CarArt.Accent.CYAN), browsableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM))
            val drive = driveRows()
            if (drive.isNotEmpty()) add(folder(DRIVE_ID, "Drive", "${drive.size} ${if (drive.size == 1) "recording" else "recordings"}", CarArt.tile(context, "Drive", CarArt.Accent.AMBER), playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM))
        }
        parentId == FOLDERS_ID -> phoneFolders().map { (f, rows) ->
            folder(FOLDER_PREFIX + f.uri, f.name, "${rows.size} ${if (rows.size == 1) "item" else "items"}", CarArt.tile(context, f.name, CarArt.Accent.CYAN),
                playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        parentId.startsWith(FOLDER_PREFIX) -> {
            val uri = parentId.removePrefix(FOLDER_PREFIX)
            val rows = folderRows(uri)
            if (rows.isEmpty()) emptyList() else {
                val progress = store.latestByUri()
                listOf(
                    action(FOLDER_ALL_PREFIX + uri, "Play all", "${rows.size} ${if (rows.size == 1) "item" else "items"}", CarArt.Accent.CYAN),
                    action(FOLDER_SHUFFLE_PREFIX + uri, "Shuffle", null, CarArt.Accent.AMBER),
                ) + rows.map { decorate(it, progress[it.uri], null) }
            }
        }
        parentId == DRIVE_ID -> {
            val progress = store.latestByUri()
            driveRows().map { decorate(it, progress[it.uri], null) }
        }
        parentId == CONTINUE_ID -> carryOn(store.latestByUri(), CARRY_ON_LIMIT)
        parentId == SERMONS_ID -> sermons()
        parentId == RECENT_ID -> {
            val progress = store.latestByUri()
            store.recent(12).filterNot { YtmLink.isYtm(it.uri) }.mapNotNull { media.byUri(it.uri) }
                .distinctBy { it.fingerprint }.take(8).map { decorate(it, progress[it.uri], null) }
        }
        parentId == LABELS_ID -> labels.all().map { label ->
            folder(LABEL_PREFIX + label.id, label.name, null, CarArt.tile(context, label.name, CarArt.Accent.CYAN),
                playableStyle = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        parentId.startsWith(LABEL_PREFIX) -> {
            val id = parentId.removePrefix(LABEL_PREFIX).toLongOrNull()
            val rows = id?.let { playlistRows(it) }.orEmpty()
            if (id == null || rows.isEmpty()) emptyList() else {
                val progress = store.latestByUri()
                listOf(
                    action(PLAY_ALL_PREFIX + id, "Play all", "${rows.size} ${if (rows.size == 1) "item" else "items"}", CarArt.Accent.CYAN),
                    action(SHUFFLE_PREFIX + id, "Shuffle", null, CarArt.Accent.AMBER),
                ) + rows.map { decorate(it, progress[it.uri], null) }
            }
        }
        else -> emptyList()
    }

    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT_ID -> rootItem()
        mediaId == CONTINUE_ID -> folder(CONTINUE_ID, "Carry on", null, null, null)
        mediaId == SERMONS_ID -> folder(SERMONS_ID, "Sermons", null, null, null)
        mediaId == LABELS_ID -> folder(LABELS_ID, "Playlists", null, null, null)
        mediaId == RECENT_ID -> folder(RECENT_ID, "Recent", null, null, null)
        mediaId == LIBRARY_ID -> folder(LIBRARY_ID, "Library", null, null, null)
        mediaId == FOLDERS_ID -> folder(FOLDERS_ID, "Folders", null, null, null)
        mediaId == DRIVE_ID -> folder(DRIVE_ID, "Drive", null, null, null)
        mediaId.startsWith(FOLDER_PREFIX) -> media.folderList().firstOrNull { it.uri == mediaId.removePrefix(FOLDER_PREFIX) }?.let { folder(mediaId, it.name, null, null, null) }
        mediaId.startsWith(FOLDER_ALL_PREFIX) -> action(mediaId, "Play all", null, CarArt.Accent.CYAN)
        mediaId.startsWith(FOLDER_SHUFFLE_PREFIX) -> action(mediaId, "Shuffle", null, CarArt.Accent.AMBER)
        mediaId.startsWith(LABEL_PREFIX) -> mediaId.removePrefix(LABEL_PREFIX).toLongOrNull()
            ?.let { labels.byId(it) }?.let { folder(mediaId, it.name, null, null, null) }
        mediaId.startsWith(PLAY_ALL_PREFIX) -> action(mediaId, "Play all", null, CarArt.Accent.CYAN)
        mediaId.startsWith(SHUFFLE_PREFIX) -> action(mediaId, "Shuffle", null, CarArt.Accent.AMBER)
        mediaId.startsWith(AT_PREFIX) -> parseAt(mediaId)?.let { (_, uri) -> media.byUri(uri)?.let { itemFor(it) } }
        else -> media.byUri(mediaId)?.let { decorate(it, store.latestByUri()[it.uri], null) }
    }

    /**
     * Car-only ids become a real queue: Play all and Shuffle expand to the playlist, and a search
     * hit inside a transcript starts at the moment it was said. Null for an ordinary item.
     */
    suspend fun expand(mediaId: String): Expanded? = when {
        mediaId.startsWith(PLAY_ALL_PREFIX) || mediaId.startsWith(SHUFFLE_PREFIX) -> {
            val shuffle = mediaId.startsWith(SHUFFLE_PREFIX)
            val id = mediaId.substringAfterLast(':').toLongOrNull()
            val rows = id?.let { playlistRows(it) }.orEmpty().let { if (shuffle) it.shuffled() else it }
            if (rows.isEmpty()) null else Expanded(rows.map { itemFor(it) }, 0, C.TIME_UNSET)
        }
        mediaId.startsWith(AT_PREFIX) -> parseAt(mediaId)?.let { (ms, uri) ->
            media.byUri(uri)?.let { Expanded(listOf(itemFor(it)), 0, ms) }
        }
        mediaId.startsWith(FOLDER_ALL_PREFIX) || mediaId.startsWith(FOLDER_SHUFFLE_PREFIX) -> {
            val shuffle = mediaId.startsWith(FOLDER_SHUFFLE_PREFIX)
            val uri = mediaId.removePrefix(if (shuffle) FOLDER_SHUFFLE_PREFIX else FOLDER_ALL_PREFIX)
            val rows = folderRows(uri).let { if (shuffle) it.shuffled() else it }
            if (rows.isEmpty()) null else Expanded(rows.map { itemFor(it) }, 0, C.TIME_UNSET)
        }
        else -> null
    }

    /**
     * Titles first, then lines said in transcribed recordings. Used by the car's search and by
     * "play ... on Lumen": the first result is what voice plays.
     */
    suspend fun search(query: String, limit: Int = 30): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val progress = store.latestByUri()
        val words = q.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        val titleHits = media.all().asSequence()
            .filter { !YtmLink.isYtm(it.uri) && !it.isVideo }
            .filter { m -> val t = m.title.lowercase(); t.contains(q.lowercase()) || (words.isNotEmpty() && words.all { t.contains(it) }) }
            .distinctBy { it.fingerprint }
            .sortedWith(compareByDescending<MediaEntity> { it.title.lowercase().startsWith(q.lowercase()) }.thenByDescending { it.dateAdded })
            .take(limit)
            .toList()
        val out = titleHits.map { decorate(it, progress[it.uri], "Titles") }.toMutableList()
        if (out.size < limit && q.length >= 3) {
            val seen = titleHits.map { it.uri }.toHashSet()
            for (segment in notes.search(q)) {
                if (out.size >= limit) break
                if (!seen.add(segment.mediaUri)) continue
                val row = media.byUri(segment.mediaUri) ?: continue
                val base = itemFor(row)
                val said = "Said at ${stamp(segment.startMs)}: ${segment.text.take(80)}"
                out += base.buildUpon()
                    .setMediaId(AT_PREFIX + segment.startMs + ":" + row.uri)
                    .setMediaMetadata(base.mediaMetadata.buildUpon()
                        .setSubtitle(said)
                        .setArtworkUri(artFor(row))
                        .setExtras(Bundle(base.mediaMetadata.extras ?: Bundle()).apply {
                            putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, "What was said")
                        })
                        .build())
                    .build()
            }
        }
        return out
    }

    /** The voice request "play [query] on Lumen": best match, or null to resume the last item. */
    suspend fun bestFor(query: String): Expanded? {
        val first = search(query, 1).firstOrNull() ?: return null
        return expand(first.mediaId) ?: media.byUri(first.mediaId)?.let { Expanded(listOf(itemFor(it)), 0, C.TIME_UNSET) }
    }

    // Lists.

    private suspend fun sermonRows(): List<MediaEntity> {
        val sermonUris = labels.byName(LabelRepository.SERMONS)?.let { labels.mediaUrisFor(it.id) }?.toSet() ?: emptySet()
        return media.all().filter { (it.durationMs > MediaRepository.SPEECH_THRESHOLD_MS || it.uri in sermonUris) && !YtmLink.isYtm(it.uri) && !it.isVideo }
            .sortedByDescending { it.dateAdded }
            .distinctBy { it.fingerprint }
    }

    private suspend fun carryOn(progress: Map<String, ProgressEntity>, limit: Int): List<MediaItem> =
        sermonRows().mapNotNull { row -> progress[row.uri]?.takeIf { inProgress(it, row) }?.let { row to it } }
            .sortedByDescending { it.second.updatedAt }
            .take(limit)
            .map { (row, p) -> decorate(row, p, null) }

    /** Grouped: Carry on, New (last 14 days, not started), This month, Older. */
    private suspend fun sermons(): List<MediaItem> {
        val progress = store.latestByUri()
        val rows = sermonRows()
        val now = System.currentTimeMillis()
        val started = rows.filter { row -> progress[row.uri]?.let { inProgress(it, row) } == true }
            .sortedByDescending { progress[it.uri]?.updatedAt ?: 0L }
        val startedUris = started.map { it.uri }.toHashSet()
        val rest = rows.filterNot { it.uri in startedUris }
        val fresh = rest.filter { now - it.dateAdded <= 14 * DAY_MS && progress[it.uri] == null }
        val freshUris = fresh.map { it.uri }.toHashSet()
        val month = rest.filter { it.uri !in freshUris && now - it.dateAdded <= 31 * DAY_MS }
        val monthUris = month.map { it.uri }.toHashSet()
        val older = rest.filter { it.uri !in freshUris && it.uri !in monthUris }
        return started.map { decorate(it, progress[it.uri], "Carry on") } +
            fresh.map { decorate(it, progress[it.uri], "New") } +
            month.map { decorate(it, progress[it.uri], "This month") } +
            older.map { decorate(it, progress[it.uri], "Older") }
    }

    /** Phone folders (picked folders and the phone's own audio folders) that hold something the car can play. */
    private suspend fun phoneFolders(): List<Pair<app.lumen.player.data.FolderEntity, List<MediaEntity>>> {
        val byFolder = media.all().filter { carPlayable(it) && !it.isDrive }.groupBy { it.folderUri }
        return media.folderList()
            .filter { !it.isDrive && it.uri != YtmLink.FOLDER_URI }
            .mapNotNull { f -> byFolder[f.uri]?.takeIf { it.isNotEmpty() }?.let { f to sortedForFolder(it) } }
            .sortedWith(compareBy(NaturalOrder) { it.first.name })
    }

    private suspend fun folderRows(folderUri: String): List<MediaEntity> =
        sortedForFolder(media.all().filter { it.folderUri == folderUri && carPlayable(it) })

    /** Drive recordings, newest first (sermons arrive there week by week). */
    private suspend fun driveRows(): List<MediaEntity> =
        media.all().filter { it.isDrive && carPlayable(it) }.sortedByDescending { it.dateAdded }.distinctBy { it.fingerprint }

    private fun sortedForFolder(rows: List<MediaEntity>) = rows.distinctBy { it.fingerprint }.sortedWith(compareBy(NaturalOrder) { it.title })

    private fun carPlayable(row: MediaEntity) = !YtmLink.isYtm(row.uri) && !row.isVideo

    private suspend fun playlistRows(labelId: Long): List<MediaEntity> =
        labels.mediaUrisFor(labelId).filterNot { YtmLink.isYtm(it) }.mapNotNull { media.byUri(it) }.distinctBy { it.fingerprint }

    // Items.

    private fun inProgress(p: ProgressEntity, row: MediaEntity): Boolean {
        val duration = row.durationMs.takeIf { it > 0 } ?: p.durationMs
        return p.positionMs >= STARTED_MS && !PositionStore.isFinished(p.positionMs, duration)
    }

    private fun artFor(row: MediaEntity) =
        CarArt.cover(context, row.artPath) ?: CarArt.tile(
            context,
            if (row.durationMs > MediaRepository.SPEECH_THRESHOLD_MS) "Sermon" else "Lumen",
            if (row.durationMs > MediaRepository.SPEECH_THRESHOLD_MS) CarArt.Accent.AMBER else CarArt.Accent.CYAN,
        )

    /** Cover or Lumen tile, played marker, time left, and an optional group heading. */
    private suspend fun decorate(row: MediaEntity, progress: ProgressEntity?, group: String?): MediaItem {
        val base = itemFor(row)
        val extras = Bundle(base.mediaMetadata.extras ?: Bundle())
        val duration = row.durationMs.takeIf { it > 0 } ?: progress?.durationMs ?: 0L
        var subtitle: String? = row.artist.ifBlank { null }
        when {
            progress != null && PositionStore.isFinished(progress.positionMs, duration) ->
                extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
            progress != null && progress.positionMs >= STARTED_MS && duration > 0 -> {
                extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                extras.putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, (progress.positionMs.toDouble() / duration).coerceIn(0.0, 1.0))
                subtitle = "${minutesLeft(duration - progress.positionMs)} left" + (subtitle?.let { " · $it" } ?: "")
            }
            else -> extras.putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
        }
        group?.let { extras.putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        return base.buildUpon()
            .setMediaMetadata(base.mediaMetadata.buildUpon()
                .setSubtitle(subtitle)
                .setArtworkUri(artFor(row))
                .setExtras(extras)
                .build())
            .build()
    }

    private fun folder(id: String, title: String, subtitle: String?, art: android.net.Uri?, browsableStyle: Int? = null, playableStyle: Int? = null): MediaItem {
        val extras = Bundle().apply {
            browsableStyle?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
            playableStyle?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it) }
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(art)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(extras)
                .build())
            .build()
    }

    private fun action(id: String, title: String, subtitle: String?, accent: CarArt.Accent): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(CarArt.tile(context, title, accent))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build())
            .build()

    companion object {
        const val ROOT_ID = "root"
        const val CONTINUE_ID = "continue"
        const val LABELS_ID = "labels"
        const val SERMONS_ID = "sermons"
        const val RECENT_ID = "recent"
        const val LIBRARY_ID = "library"
        const val FOLDERS_ID = "folders"
        const val DRIVE_ID = "drive"
        const val FOLDER_PREFIX = "folder:"
        const val FOLDER_ALL_PREFIX = "car:fplay:"
        const val FOLDER_SHUFFLE_PREFIX = "car:fshuffle:"
        const val LABEL_PREFIX = "label:"
        const val PLAY_ALL_PREFIX = "car:playall:"
        const val SHUFFLE_PREFIX = "car:shuffle:"
        const val AT_PREFIX = "car:at:"
        private const val CARRY_ON_LIMIT = 5
        private const val STARTED_MS = 30_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun isCarOnly(mediaId: String) = mediaId.startsWith("car:")

        /** "car:at:<ms>:<uri>" to (ms, uri); the uri may itself contain colons. */
        fun parseAt(mediaId: String): Pair<Long, String>? {
            if (!mediaId.startsWith(AT_PREFIX)) return null
            val rest = mediaId.removePrefix(AT_PREFIX)
            val ms = rest.substringBefore(':').toLongOrNull() ?: return null
            val uri = rest.substringAfter(':', "")
            return if (uri.isEmpty()) null else ms to uri
        }

        fun minutesLeft(ms: Long): String {
            val minutes = ((ms.coerceAtLeast(0L) + 59_999) / 60_000)
            return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        }

        private fun stamp(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
