package app.lumen.player.notes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.lumen.player.diag.RingLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One line of the notes: a typed chapter or a bookmark, at a position. */
data class NoteMark(val positionMs: Long, val title: String)

/**
 * 0.4.0 "Export notes": a plain-text file with the sermon title, the speaker, the date, then
 * one "47:12  Title" line per chapter and bookmark in time order, written to cacheDir/notes and
 * handed to the share sheet through the app's FileProvider (ACTION_SEND).
 */
object NotesExport {

    private const val DIR = "notes"

    /** The whole document. [dateMs] 0 leaves the date line out; a blank [speaker] leaves that out. */
    fun text(title: String, speaker: String, dateMs: Long, marks: List<NoteMark>): String = buildString {
        append(title.ifBlank { "Untitled" }).append('\n')
        if (speaker.isNotBlank()) append(speaker).append('\n')
        if (dateMs > 0L) append(SimpleDateFormat("d MMMM yyyy", Locale.UK).format(Date(dateMs))).append('\n')
        append('\n')
        if (marks.isEmpty()) {
            append("No chapters or bookmarks yet\n")
        } else {
            marks.sortedBy { it.positionMs }.forEach { append(timecode(it.positionMs)).append("  ").append(it.title).append('\n') }
        }
    }

    /** Writes the file and opens the share sheet. Returns false when the file could not be written. */
    suspend fun share(context: Context, title: String, speaker: String, dateMs: Long, marks: List<NoteMark>): Boolean {
        val file = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, DIR).apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, fileName(title))
                out.writeText(text(title, speaker, dateMs, marks), Charsets.UTF_8)
                out
            } catch (t: Throwable) {
                RingLog.e("notes", "notes file failed", t)
                null
            }
        } ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Notes: ${title.ifBlank { "Untitled" }}")
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Export notes").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(chooser)
            RingLog.i("notes", "shared ${file.name}, ${marks.size} marks")
            true
        } catch (t: Throwable) {
            RingLog.w("notes", "share sheet failed: $t")
            false
        }
    }

    /** "Honoring the High Office notes.txt", with characters a file system dislikes removed. */
    private fun fileName(title: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
        return (clean.ifBlank { "Lumen" }) + " notes.txt"
    }

    /** "47:12" under an hour, "1:09:32" over, as everywhere else in Lumen. */
    private fun timecode(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.UK, "%02d:%02d", m, s)
    }
}
