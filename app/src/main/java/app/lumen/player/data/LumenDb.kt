package app.lumen.player.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

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

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE uri = :uri ORDER BY updated_at DESC LIMIT 1")
    suspend fun latestForUri(uri: String): ProgressEntity?

    @Query("SELECT * FROM progress ORDER BY updated_at DESC")
    suspend fun all(): List<ProgressEntity>

    @Upsert
    suspend fun putKv(entry: KvEntity)

    @Query("SELECT v FROM kv WHERE k = :key LIMIT 1")
    suspend fun kv(key: String): String?
}

@Database(entities = [ProgressEntity::class, KvEntity::class], version = 1, exportSchema = false)
abstract class LumenDb : RoomDatabase() {
    abstract fun progressDao(): ProgressDao

    companion object {
        fun build(context: Context): LumenDb =
            Room.databaseBuilder(context.applicationContext, LumenDb::class.java, "lumen.db").build()
    }
}
