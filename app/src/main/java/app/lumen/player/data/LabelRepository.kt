package app.lumen.player.data

import app.lumen.player.diag.RingLog
import kotlinx.coroutines.flow.Flow

/** Labels are many-to-many with media. Four are seeded on first run; "Suno" appears on demand. */
class LabelRepository(private val dao: LabelDao, private val settings: SettingsRepository) {

    companion object {
        const val SERMONS = "Sermons"
        const val WORSHIP = "Worship"
        const val KIDS = "Kids"
        const val UPBEAT = "Upbeat"
        const val SUNO = "Suno"

        const val HUE_SLATE = 0
        const val HUE_VIOLET = 1
        const val HUE_CORAL = 2
        const val HUE_AMBER = 3
        const val HUE_TEAL = 4
        const val HUE_NEUTRAL = 5
    }

    val labels: Flow<List<LabelEntity>> = dao.labelsFlow()
    val links: Flow<List<MediaLabelEntity>> = dao.mediaLabelsFlow()

    suspend fun seedDefaults() {
        if (settings.get(SettingsRepository.KEY_SEEDED) == "1") return
        val defaults = listOf(
            LabelEntity(name = SERMONS, hue = HUE_SLATE, lockKids = false, noInterruptions = false, shuffleOnOpen = false),
            LabelEntity(name = WORSHIP, hue = HUE_VIOLET, lockKids = false, noInterruptions = true, shuffleOnOpen = false),
            LabelEntity(name = KIDS, hue = HUE_CORAL, lockKids = true, noInterruptions = false, shuffleOnOpen = true),
            LabelEntity(name = UPBEAT, hue = HUE_AMBER, lockKids = false, noInterruptions = false, shuffleOnOpen = true),
        )
        defaults.forEach { label ->
            if (dao.byName(label.name) == null) dao.insert(label)
        }
        settings.put(SettingsRepository.KEY_SEEDED, "1")
        RingLog.i("labels", "seeded default labels")
    }

    suspend fun all(): List<LabelEntity> = dao.labels()

    suspend fun byId(id: Long): LabelEntity? = dao.byId(id)

    suspend fun byName(name: String): LabelEntity? = dao.byName(name)

    /** Returns null when the name is blank or already taken by another label. */
    suspend fun save(label: LabelEntity): LabelEntity? {
        val name = label.name.trim()
        if (name.isBlank()) return null
        val clash = dao.byName(name)
        if (clash != null && clash.id != label.id) return null
        val cleaned = label.copy(name = name)
        return if (cleaned.id == 0L) {
            val id = dao.insert(cleaned)
            cleaned.copy(id = id)
        } else {
            dao.update(cleaned)
            cleaned
        }
    }

    suspend fun delete(id: Long) {
        dao.detachAll(id)
        dao.delete(id)
    }

    suspend fun labelIdsFor(uri: String): List<Long> = dao.labelIdsFor(uri)

    suspend fun mediaUrisFor(labelId: Long): List<String> = dao.mediaUrisFor(labelId)

    /** 0.8.0: a new member goes to the end of the playlist. */
    suspend fun attach(uri: String, labelId: Long) = dao.attach(MediaLabelEntity(uri, labelId, dao.nextPosition(labelId)))

    suspend fun detach(uri: String, labelId: Long) = dao.detach(uri, labelId)

    suspend fun toggle(uri: String, labelId: Long) {
        if (labelId in dao.labelIdsFor(uri)) dao.detach(uri, labelId) else attach(uri, labelId)
    }

    /** 0.8.0: the playlist in this order (the uris as stored on its links). */
    suspend fun reorder(labelId: Long, uris: List<String>) {
        uris.forEachIndexed { index, uri -> dao.setPosition(uri, labelId, index) }
    }

    /** The Suno label is created the first time a Suno file is scanned. */
    suspend fun ensureSuno(): LabelEntity {
        dao.byName(SUNO)?.let { return it }
        val label = LabelEntity(name = SUNO, hue = HUE_TEAL, lockKids = false, noInterruptions = false, shuffleOnOpen = true)
        val id = dao.insert(label)
        return label.copy(id = id)
    }
}
