package app.lumen.player.notes

import android.content.Context
import app.lumen.player.data.MediaEntity
import app.lumen.player.data.SegmentEntity
import org.json.JSONObject
import java.security.MessageDigest

/** Local processing state only. A completion flag is valid only for the exact saved segments. */
internal class TranscriptCheckpoint(context: Context) {
    private val preferences = context.getSharedPreferences("transcript_checkpoints", Context.MODE_PRIVATE)
    /** [slicedThroughMs]: 0.9.0, how far the sliced transcriber has finished; -1 for other paths. */
    data class State(val complete: Boolean, val slicedThroughMs: Long = -1L)

    fun hasRecord(uri: String): Boolean = preferences.contains(key(uri))

    fun read(media: MediaEntity, lines: List<SegmentEntity>): State? {
        val json = runCatching { JSONObject(preferences.getString(key(media.uri), "").orEmpty()) }.getOrNull() ?: return null
        if (json.optString("source") != source(media)) return null
        val matches = json.optInt("count") == lines.size && json.optLong("last", -1) == (lines.lastOrNull()?.startMs ?: -1L)
        return State(json.optBoolean("complete") && matches, if (matches) json.optLong("sliced", -1L) else -1L)
    }

    fun save(media: MediaEntity, lines: List<Pair<Long, String>>, complete: Boolean, slicedThroughMs: Long = -1L) {
        val json = JSONObject().put("source", source(media)).put("count", lines.size)
            .put("last", lines.lastOrNull()?.first ?: -1L).put("complete", complete).put("sliced", slicedThroughMs)
        preferences.edit().putString(key(media.uri), json.toString()).commit()
    }

    // Playback can learn a duration and change the library fingerprint without changing the audio.
    private fun source(media: MediaEntity) = key("${media.uri}|${media.sizeBytes}|${media.modifiedAt}")
    private fun key(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
