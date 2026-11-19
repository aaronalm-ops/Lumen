package app.lumen.player.remote

import app.lumen.player.diag.RingLog
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 0.10.0: copies the YouTube Music songs of a Lumen playlist into a private playlist on the owner's
 * own YouTube account, in Lumen's order, so the same playlist can be played in YouTube Music's own
 * Android Auto app. Official YouTube Data API v3 only (scope youtube); nothing is played or
 * downloaded by Lumen. Songs on the phone, in Drive or from Suno stay in Lumen.
 *
 * Lumen owns the mirrored playlist: each update makes it match the Lumen playlist again, so songs
 * added to it inside YouTube Music are removed on the next update (the description says so).
 *
 * Costs against the daily allowance of 10,000 units: reading the playlist 1 unit per 50 songs;
 * creating it, renaming it, and each song added, moved or removed 50 units.
 */
class YoutubeMirror(
    private val token: () -> String,
    private val baseUrl: String = API,
) {
    /** One song already in the YouTube playlist: its playlist item id and video id. */
    data class Existing(val itemId: String, val videoId: String)

    sealed class Op {
        data class Delete(val itemId: String, val videoId: String) : Op()
        data class Insert(val videoId: String, val position: Int) : Op()
        data class Move(val itemId: String, val videoId: String, val position: Int) : Op()
    }

    data class Result(val playlistId: String, val songs: Int, val added: Int, val moved: Int, val removed: Int, val skipped: Int, val created: Boolean)

    class QuotaUsed(val done: Int, val total: Int, val playlistId: String) :
        IOException("YouTube's daily allowance ran out after $done of $total changes. Update again tomorrow to finish")

    fun mirror(
        existingPlaylistId: String?,
        title: String,
        videoIds: List<String>,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val wanted = videoIds.distinct()
        var created = false
        var playlistId = existingPlaylistId?.takeIf { it.isNotBlank() }
        var current: List<Existing>? = null
        if (playlistId != null) {
            current = try {
                readItems(playlistId)
            } catch (gone: PlaylistGone) {
                RingLog.i("youtube", "mirror playlist was deleted on YouTube; making a new one")
                null
            }
            if (current == null) playlistId = null else renameIfNeeded(playlistId, title)
        }
        if (playlistId == null) {
            playlistId = createPlaylist(title)
            created = true
            current = emptyList()
        }
        val ops = plan(wanted, current.orEmpty())
        var added = 0
        var moved = 0
        var removed = 0
        var skipped = 0
        ops.forEachIndexed { i, op ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Mirror cancelled")
            try {
                when (op) {
                    is Op.Delete -> { call("DELETE", "playlistItems?id=${enc(op.itemId)}", null); removed++ }
                    is Op.Insert -> { call("POST", "playlistItems?part=snippet", itemBody(playlistId, op.videoId, op.position)); added++ }
                    is Op.Move -> { call("PUT", "playlistItems?part=snippet", itemBody(playlistId, op.videoId, op.position).put("id", op.itemId)); moved++ }
                }
            } catch (e: SongUnavailable) {
                // A song removed from YouTube, or not allowed in playlists: the rest still goes.
                RingLog.w("youtube", "mirror skipped ${op.javaClass.simpleName}: ${e.message}")
                skipped++
            } catch (e: Quota) {
                throw QuotaUsed(i, ops.size, playlistId)
            }
            progress(i + 1, ops.size)
        }
        RingLog.i("youtube", "mirror done: ${wanted.size} songs, +$added ~$moved -$removed, skipped $skipped")
        return Result(playlistId, wanted.size - skipped, added, moved, removed, skipped, created)
    }

    private fun readItems(playlistId: String): List<Existing> {
        val out = ArrayList<Existing>()
        var page: String? = null
        val seen = HashSet<String>()
        do {
            val json = call("GET", "playlistItems?part=snippet&maxResults=50&playlistId=${enc(playlistId)}" + (page?.let { "&pageToken=${enc(it)}" } ?: ""), null)
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items!!.getJSONObject(i)
                val videoId = item.optJSONObject("snippet")?.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                out += Existing(item.optString("id"), videoId)
            }
            page = json.optString("nextPageToken").takeIf { it.isNotBlank() && seen.add(it) }
        } while (page != null)
        return out
    }

    private fun createPlaylist(title: String): String {
        val body = JSONObject()
            .put("snippet", JSONObject().put("title", title.take(150)).put("description", DESCRIPTION))
            .put("status", JSONObject().put("privacyStatus", "private"))
        return call("POST", "playlists?part=snippet,status", body).optString("id").ifBlank { throw IOException("YouTube did not return the new playlist") }
    }

    private fun renameIfNeeded(playlistId: String, title: String) {
        val json = call("GET", "playlists?part=snippet&id=${enc(playlistId)}", null)
        val snippet = json.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("snippet") ?: throw PlaylistGone()
        if (snippet.optString("title") == title.take(150)) return
        call("PUT", "playlists?part=snippet", JSONObject().put("id", playlistId)
            .put("snippet", JSONObject().put("title", title.take(150)).put("description", DESCRIPTION)))
    }

    private fun itemBody(playlistId: String, videoId: String, position: Int) = JSONObject().put(
        "snippet",
        JSONObject().put("playlistId", playlistId).put("position", position)
            .put("resourceId", JSONObject().put("kind", "youtube#video").put("videoId", videoId)),
    )

    private class PlaylistGone : IOException("The YouTube playlist is gone")
    private class SongUnavailable(message: String) : IOException(message)
    private class Quota : IOException("quota")

    private fun call(method: String, path: String, body: JSONObject?): JSONObject {
        val c = URL("$baseUrl/$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer ${token()}")
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code in 200..299) {
                val text = c.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
            val text = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val error = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
            val reason = error?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
            val message = error?.optString("message").orEmpty()
            RingLog.w("youtube", "mirror $method failed: $code $reason")
            throw when {
                code == 401 -> YoutubeApi.NeedsSignIn()
                code == 403 && (reason.contains("quota", true) || reason == "rateLimitExceeded") -> Quota()
                code == 403 && (reason.contains("insufficient", true) || message.contains("scope", true)) -> YoutubeApi.NeedsSignIn()
                code == 403 && (reason == "accessNotConfigured" || message.contains("has not been used", true)) ->
                    YoutubeApi.NotConfigured("YouTube Data API is not switched on in Google Cloud yet")
                code == 404 && reason == "playlistNotFound" -> PlaylistGone()
                reason == "youtubeSignupRequired" -> IOException("This Google account has no YouTube channel. Connect the account used in YouTube Music")
                reason in SONG_REASONS -> SongUnavailable(reason)
                else -> IOException("YouTube could not update the playlist ($code ${reason.ifBlank { "request failed" }}). Try again")
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val API = "https://www.googleapis.com/youtube/v3"
        const val DESCRIPTION = "Kept in step by Lumen. Changes made here are replaced the next time Lumen updates this playlist."
        const val UNITS_PER_CHANGE = 50
        const val DAILY_UNITS = 10_000
        private val SONG_REASONS = setOf("videoNotFound", "forbidden", "playlistItemNotFound", "manualSortRequired", "invalidResourceId", "playlistContainsMaximumNumberOfVideos")

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

        /**
         * The changes that make [existing] equal to [wanted], in order: first removals (songs no
         * longer in Lumen, and repeats), then each position from the top is either right, moved
         * into place, or a new song inserted there.
         */
        fun plan(wanted: List<String>, existing: List<Existing>): List<Op> {
            val target = wanted.distinct()
            val keep = target.toHashSet()
            val ops = ArrayList<Op>()
            val current = ArrayList<Existing>()
            val seen = HashSet<String>()
            for (e in existing) {
                if (e.videoId !in keep || !seen.add(e.videoId)) ops += Op.Delete(e.itemId, e.videoId) else current += e
            }
            for ((i, videoId) in target.withIndex()) {
                if (i < current.size && current[i].videoId == videoId) continue
                val at = (i until current.size).firstOrNull { current[it].videoId == videoId }
                if (at != null) {
                    val e = current.removeAt(at)
                    current.add(i, e)
                    ops += Op.Move(e.itemId, e.videoId, i)
                } else {
                    current.add(i, Existing("", videoId))
                    ops += Op.Insert(videoId, i)
                }
            }
            return ops
        }

        /** The allowance an update would use, for the warning before a very large first copy. */
        fun units(ops: List<Op>): Int = ops.size * UNITS_PER_CHANGE
    }
}
