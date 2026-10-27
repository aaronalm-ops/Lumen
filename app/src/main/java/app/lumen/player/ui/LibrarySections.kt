package app.lumen.player.ui

import androidx.compose.ui.graphics.vector.ImageVector
import app.lumen.player.data.FolderEntity
import app.lumen.player.data.LabelEntity
import app.lumen.player.data.LabelRepository
import app.lumen.player.data.drive.isDrive
import app.lumen.player.remote.isYtm

/**
 * 0.5.1: the Library's sections, one per place media comes from. Suno is a collection across
 * Phone and Drive (the Suno rule labels any file with "Suno" in its path or artist), so a Suno
 * song counts in both its home section and Suno.
 */
enum class LibrarySource(val title: String, val key: String) {
    ALL("All", "all"),
    PHONE("Phone", "phone"),
    DRIVE("Drive", "drive"),
    YTM("YouTube Music", "ytm"),
    SUNO("Suno", "suno");

    val icon: ImageVector?
        get() = when (this) {
            ALL -> null
            PHONE -> LumenIcons.Phone
            DRIVE -> LumenIcons.Cloud
            YTM -> LumenIcons.Music
            SUNO -> LumenIcons.Music
        }

    companion object {
        fun fromKey(key: String?): LibrarySource = entries.firstOrNull { it.key == key } ?: ALL
    }
}

/** Phone: MediaStore buckets and picked folders alike; everything that is not Drive or YouTube Music. */
fun FolderEntity.isPhone(): Boolean = !isDrive && !isYtm

fun sunoLabelId(labels: List<LabelEntity>): Long? = labels.firstOrNull { it.name == LabelRepository.SUNO }?.id

/** Whether [item] belongs in [source]. */
fun inSource(item: LibraryItem, source: LibrarySource, sunoId: Long?): Boolean = when (source) {
    LibrarySource.ALL -> true
    LibrarySource.PHONE -> !item.media.isDrive && !item.media.isYtm
    LibrarySource.DRIVE -> item.media.isDrive
    LibrarySource.YTM -> item.media.isYtm
    LibrarySource.SUNO -> sunoId != null && sunoId in item.labelIds
}

fun sourceCounts(items: List<LibraryItem>, labels: List<LabelEntity>): Map<LibrarySource, Int> {
    val sunoId = sunoLabelId(labels)
    return LibrarySource.entries.associateWith { s -> items.count { inSource(it, s, sunoId) } }
}

/**
 * The first level under the Drive folder ("2025/Sunday 12.mp3" gives "2025"), or null for a file
 * at the top. The Drive section lists these as its folders.
 */
fun driveSubfolder(item: LibraryItem): String? {
    val path = item.media.relPath
    val slash = path.indexOf('/')
    return if (slash > 0) path.substring(0, slash) else null
}

/** Marker for "files at the top of the Drive folder" in the subfolder filter. */
const val DRIVE_TOP_LEVEL = "\u0000top"
