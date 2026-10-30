package app.lumen.player.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// Phase 0 tables. Unchanged so positions survive the Phase 1a and 1b upgrades.

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val key: String,
    val uri: String,
    val title: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val k: String,
    val v: String,
)

// Phase 1a tables.

/** One chosen folder tree (ACTION_OPEN_DOCUMENT_TREE) with its display name. */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val uri: String,
    val name: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** One scanned file. The uri is the document uri inside its folder tree. */
@Entity(
    tableName = "media",
    indices = [Index(value = ["folder_uri"]), Index(value = ["fingerprint"])],
)
data class MediaEntity(
    @PrimaryKey val uri: String,
    @ColumnInfo(name = "folder_uri") val folderUri: String,
    val title: String,
    val artist: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "date_added") val dateAdded: Long,
    val mime: String,
    @ColumnInfo(name = "is_video") val isVideo: Boolean,
    val fingerprint: String,
    /** Relative path inside the folder tree, used for the "Suno" rule and the folder name. */
    @ColumnInfo(name = "rel_path") val relPath: String,
    @ColumnInfo(name = "art_path") val artPath: String?,
    /** Desaturated dominant colour of the art as ARGB, null when there is no art. */
    val tint: Int?,
    /** Last-modified stamp from the provider, used to skip unchanged files on rescan. */
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    /**
     * Phase 1b. For a Drive row (`drive://<id>`), the local copy under filesDir/offline once
     * "Keep offline" has finished; null otherwise. Local rows never set it.
     */
    @ColumnInfo(name = "offline_path") val offlinePath: String? = null,
)

@Entity(tableName = "labels")
data class LabelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Index into the six hue dots (0 Sermons blue-slate, 1 violet, 2 coral, 3 amber, 4 teal, 5 neutral). */
    val hue: Int,
    @ColumnInfo(name = "lock_kids") val lockKids: Boolean,
    @ColumnInfo(name = "no_interruptions") val noInterruptions: Boolean,
    @ColumnInfo(name = "shuffle_on_open") val shuffleOnOpen: Boolean,
)

@Entity(
    tableName = "media_labels",
    primaryKeys = ["media_uri", "label_id"],
    indices = [Index(value = ["label_id"])],
)
data class MediaLabelEntity(
    @ColumnInfo(name = "media_uri") val mediaUri: String,
    @ColumnInfo(name = "label_id") val labelId: Long,
    /** 0.8.0: the order within the playlist; new members go to the end. */
    @ColumnInfo(name = "position", defaultValue = "0") val position: Int = 0,
)

@Entity(tableName = "bookmarks", indices = [Index(value = ["media_uri"])])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_uri") val mediaUri: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * 0.4.0: a typed chapter on a speech item (long press on the bookmark chip). Listed with the
 * bookmarks, sorted by time, and exported as notes. Keyed by the player's uri like bookmarks.
 */
@Entity(tableName = "chapters", indices = [Index(value = ["media_uri"])])
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_uri") val mediaUri: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    val title: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE uri = :uri ORDER BY updated_at DESC LIMIT 1")
    suspend fun latestForUri(uri: String): ProgressEntity?

    @Query("SELECT COUNT(*) FROM progress WHERE uri = :uri")
    suspend fun countForUri(uri: String): Int

    @Query(
        "SELECT p.* FROM progress p JOIN media m ON m.uri = p.uri " +
            "WHERE m.fingerprint = :fingerprint ORDER BY p.updated_at DESC LIMIT 1",
    )
    suspend fun latestForFingerprint(fingerprint: String): ProgressEntity?

    @Query("SELECT * FROM progress ORDER BY updated_at DESC")
    suspend fun all(): List<ProgressEntity>

    @Query("SELECT * FROM progress ORDER BY updated_at DESC")
    fun allFlow(): Flow<List<ProgressEntity>>

    @Upsert
    suspend fun putKv(entry: KvEntity)

    @Query("SELECT v FROM kv WHERE k = :key LIMIT 1")
    suspend fun kv(key: String): String?

    @Query("SELECT v FROM kv WHERE k = :key LIMIT 1")
    fun kvFlow(key: String): Flow<String?>

    @Query("SELECT * FROM kv")
    fun allKvFlow(): Flow<List<KvEntity>>

    @Query("SELECT * FROM kv")
    suspend fun allKv(): List<KvEntity>
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM folders ORDER BY added_at ASC")
    fun foldersFlow(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY added_at ASC")
    suspend fun folders(): List<FolderEntity>

    @Upsert
    suspend fun upsertFolder(folder: FolderEntity)

    @Query("DELETE FROM folders WHERE uri = :uri")
    suspend fun deleteFolder(uri: String)

    /** The folder rows whose uri starts with [prefix]; used for the MediaStore buckets. */
    @Query("SELECT * FROM folders WHERE uri LIKE :prefix || '%'")
    suspend fun foldersWithPrefix(prefix: String): List<FolderEntity>

    @Query("DELETE FROM folders WHERE uri IN (:uris)")
    suspend fun deleteFolders(uris: List<String>)

    @Query("SELECT * FROM media ORDER BY date_added DESC")
    fun mediaFlow(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media ORDER BY date_added DESC")
    suspend fun media(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE uri = :uri LIMIT 1")
    suspend fun mediaByUri(uri: String): MediaEntity?

    @Query("SELECT * FROM media WHERE fingerprint = :fingerprint")
    suspend fun mediaByFingerprint(fingerprint: String): List<MediaEntity>

    /** Phase 1b: the local copy of a Drive row, or null to go back to streaming. */
    @Query("UPDATE media SET offline_path = :path WHERE uri = :uri")
    suspend fun setOfflinePath(uri: String, path: String?)

    /** Phase 1b: a Drive row learns its duration (and so its fingerprint) from the player on first play. */
    @Query("UPDATE media SET duration_ms = :durationMs, fingerprint = :fingerprint WHERE uri = :uri")
    suspend fun setDuration(uri: String, durationMs: Long, fingerprint: String)

    @Query("SELECT * FROM media WHERE folder_uri = :folderUri")
    suspend fun mediaInFolder(folderUri: String): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media")
    suspend fun mediaCount(): Int

    /** The media rows whose folder uri starts with [prefix]; used for the MediaStore rows. */
    @Query("SELECT * FROM media WHERE folder_uri LIKE :prefix || '%'")
    suspend fun mediaWithFolderPrefix(prefix: String): List<MediaEntity>

    @Query("SELECT COUNT(*) FROM media WHERE folder_uri LIKE :prefix || '%'")
    suspend fun mediaCountWithFolderPrefix(prefix: String): Int

    @Upsert
    suspend fun upsertMedia(media: MediaEntity)

    /** One transaction per batch so a whole-phone scan does not emit the media flow per file. */
    @Upsert
    suspend fun upsertMediaAll(media: List<MediaEntity>)

    @Query("DELETE FROM media WHERE folder_uri = :folderUri")
    suspend fun deleteMediaInFolder(folderUri: String)

    @Query("DELETE FROM media WHERE uri IN (:uris)")
    suspend fun deleteMedia(uris: List<String>)

    @Query("SELECT * FROM bookmarks WHERE media_uri = :uri ORDER BY position_ms ASC")
    fun bookmarksFlow(uri: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks ORDER BY media_uri ASC, position_ms ASC")
    suspend fun allBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE media_uri = :uri ORDER BY position_ms ASC")
    suspend fun bookmarksFor(uri: String): List<BookmarkEntity>

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    // 0.4.0: chapters.

    @Query("SELECT * FROM chapters WHERE media_uri = :uri ORDER BY position_ms ASC")
    fun chaptersFlow(uri: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters ORDER BY media_uri ASC, position_ms ASC")
    suspend fun allChapters(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE media_uri = :uri ORDER BY position_ms ASC")
    suspend fun chaptersFor(uri: String): List<ChapterEntity>

    @Insert
    suspend fun insertChapter(chapter: ChapterEntity): Long

    @Delete
    suspend fun deleteChapter(chapter: ChapterEntity)
}

@Dao
interface LabelDao {
    @Query("SELECT * FROM labels ORDER BY id ASC")
    fun labelsFlow(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM labels ORDER BY id ASC")
    suspend fun labels(): List<LabelEntity>

    @Query("SELECT * FROM labels WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): LabelEntity?

    @Query("SELECT * FROM labels WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): LabelEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(label: LabelEntity): Long

    @Update
    suspend fun update(label: LabelEntity)

    @Query("DELETE FROM labels WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM media_labels ORDER BY label_id ASC, position ASC, rowid ASC")
    fun mediaLabelsFlow(): Flow<List<MediaLabelEntity>>

    @Query("SELECT * FROM media_labels")
    suspend fun allLinks(): List<MediaLabelEntity>

    @Query("SELECT label_id FROM media_labels WHERE media_uri = :uri")
    suspend fun labelIdsFor(uri: String): List<Long>

    @Query("SELECT media_uri FROM media_labels WHERE label_id = :labelId ORDER BY position ASC, rowid ASC")
    suspend fun mediaUrisFor(labelId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun attach(link: MediaLabelEntity)

    @Query("DELETE FROM media_labels WHERE media_uri = :uri AND label_id = :labelId")
    suspend fun detach(uri: String, labelId: Long)

    @Query("DELETE FROM media_labels WHERE label_id = :labelId")
    suspend fun detachAll(labelId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM media_labels WHERE label_id = :labelId")
    suspend fun nextPosition(labelId: Long): Int

    @Query("UPDATE media_labels SET position = :position WHERE media_uri = :uri AND label_id = :labelId")
    suspend fun setPosition(uri: String, labelId: Long, position: Int)
}

/**
 * 0.6.0: a sermon's notes and where the job stands. One row per media uri. [status] is "working",
 * "done" or "failed"; [step] is the line shown while working or the reason it failed; [json] holds
 * the notes as Gemini returned them (see notes/SermonNotes.kt), empty until done.
 */
@Entity(tableName = "notes")
data class NotesEntity(
    @PrimaryKey @ColumnInfo(name = "media_uri") val mediaUri: String,
    val title: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    val status: String,
    val step: String,
    val json: String,
    val model: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** 0.6.0: one timed line of a transcript, for search and the transcript view. */
@Entity(tableName = "transcript_segments", indices = [Index(value = ["media_uri"])])
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_uri") val mediaUri: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    val text: String,
)

@Dao
interface NotesDao {
    @Query("SELECT * FROM notes WHERE media_uri = :uri LIMIT 1")
    fun notesFlow(uri: String): Flow<NotesEntity?>

    @Query("SELECT * FROM notes WHERE media_uri = :uri LIMIT 1")
    suspend fun notes(uri: String): NotesEntity?

    @Query("SELECT * FROM notes")
    fun allFlow(): Flow<List<NotesEntity>>

    @Upsert
    suspend fun upsert(notes: NotesEntity)

    @Query("UPDATE notes SET status = :status, step = :step, updated_at = :at WHERE media_uri = :uri")
    suspend fun setStatus(uri: String, status: String, step: String, at: Long)

    @Query("UPDATE notes SET status = 'failed', step = 'Stopped before it finished. Tap Write notes to try again' WHERE status = 'working'")
    suspend fun markInterrupted(): Int

    @Query("DELETE FROM notes WHERE media_uri = :uri")
    suspend fun delete(uri: String)

    @Query("SELECT * FROM transcript_segments WHERE media_uri = :uri ORDER BY start_ms ASC")
    fun segmentsFlow(uri: String): Flow<List<SegmentEntity>>

    @Insert
    suspend fun insertSegments(segments: List<SegmentEntity>)

    @Query("DELETE FROM transcript_segments WHERE media_uri = :uri")
    suspend fun deleteSegments(uri: String)

    /** Plain substring search; a few dozen sermons are tens of thousands of rows at most. */
    @Query("SELECT * FROM transcript_segments WHERE text LIKE '%' || :q || '%' ORDER BY media_uri ASC, start_ms ASC LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<SegmentEntity>
}

@Database(
    entities = [
        ProgressEntity::class,
        KvEntity::class,
        FolderEntity::class,
        MediaEntity::class,
        LabelEntity::class,
        MediaLabelEntity::class,
        BookmarkEntity::class,
        ChapterEntity::class,
        NotesEntity::class,
        SegmentEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class LumenDb : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
    abstract fun mediaDao(): MediaDao
    abstract fun labelDao(): LabelDao
    abstract fun notesDao(): NotesDao

    companion object {
        /**
         * Phase 0 to Phase 1a. Only creates the new tables; `progress` and `kv` are untouched so
         * every saved position survives the upgrade. Column types follow Room's expectations for
         * the entities above (TEXT or INTEGER, NOT NULL for non-null Kotlin types).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` (" +
                        "`uri` TEXT NOT NULL, `name` TEXT NOT NULL, `added_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`uri`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media` (" +
                        "`uri` TEXT NOT NULL, `folder_uri` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, `duration_ms` INTEGER NOT NULL, `size_bytes` INTEGER NOT NULL, " +
                        "`date_added` INTEGER NOT NULL, `mime` TEXT NOT NULL, `is_video` INTEGER NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, `rel_path` TEXT NOT NULL, `art_path` TEXT, `tint` INTEGER, " +
                        "`modified_at` INTEGER NOT NULL, PRIMARY KEY(`uri`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_folder_uri` ON `media` (`folder_uri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_fingerprint` ON `media` (`fingerprint`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `labels` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `hue` INTEGER NOT NULL, " +
                        "`lock_kids` INTEGER NOT NULL, `no_interruptions` INTEGER NOT NULL, " +
                        "`shuffle_on_open` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_labels` (" +
                        "`media_uri` TEXT NOT NULL, `label_id` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`media_uri`, `label_id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_labels_label_id` ON `media_labels` (`label_id`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_uri` TEXT NOT NULL, " +
                        "`position_ms` INTEGER NOT NULL, `created_at` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_media_uri` ON `bookmarks` (`media_uri`)")
            }
        }

        /**
         * Phase 1a to Phase 1b. One nullable column on `media` for the offline copy of a Drive
         * row. `progress` and `kv` are untouched; every saved position survives.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media` ADD COLUMN `offline_path` TEXT")
            }
        }

        /**
         * 0.3.x to 0.4.0. Creates `chapters` and its index and touches nothing else, so
         * `progress`, `kv` and every other table survive as they are. The column types match
         * what Room expects for [ChapterEntity] (Long and String non-null, autoGenerate id).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chapters` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_uri` TEXT NOT NULL, " +
                        "`position_ms` INTEGER NOT NULL, `title` TEXT NOT NULL, `created_at` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_media_uri` ON `chapters` (`media_uri`)")
            }
        }

        /** 0.5.x to 0.6.0. Creates `notes` and `transcript_segments`; nothing else is touched. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (" +
                        "`media_uri` TEXT NOT NULL, `title` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, `step` TEXT NOT NULL, `json` TEXT NOT NULL, `model` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`media_uri`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transcript_segments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_uri` TEXT NOT NULL, " +
                        "`start_ms` INTEGER NOT NULL, `text` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcript_segments_media_uri` ON `transcript_segments` (`media_uri`)")
            }
        }

        /** 0.7.x to 0.8.0. Playlists keep an order: one column on `media_labels`, numbered as added. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_labels` ADD COLUMN `position` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE `media_labels` SET `position` = (SELECT COUNT(*) FROM `media_labels` AS m2 " +
                        "WHERE m2.`label_id` = `media_labels`.`label_id` AND m2.rowid < `media_labels`.rowid)",
                )
            }
        }

        fun build(context: Context): LumenDb =
            Room.databaseBuilder(context.applicationContext, LumenDb::class.java, "lumen.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                // 0.8.0 (red team round 2): no destructive fallback. Every version step has its
                // migration; a missing one must never wipe saved places, labels or notes.
                .build()
    }
}
