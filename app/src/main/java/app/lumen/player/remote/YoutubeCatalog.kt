package app.lumen.player.remote

import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** The Data API's metadata only. Playback stays in the official YouTube Music app. */
internal class YoutubeCatalog(private val read: (String) -> JSONObject) {
    fun playlists(): List<YoutubeApi.PlaylistSummary> {
        val result = mutableListOf<YoutubeApi.PlaylistSummary>()
        pages("playlists?part=snippet,contentDetails,status&mine=true&maxResults=50") { json ->
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items!!.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                result += YoutubeApi.PlaylistSummary(
                    id,
                    item.optJSONObject("snippet")?.optString("title").orEmpty().ifBlank { "Untitled playlist" },
                    item.optJSONObject("contentDetails")?.optInt("itemCount") ?: 0,
                    item.optJSONObject("status")?.optString("privacyStatus").orEmpty(),
                )
            }
        }
        return result.distinctBy { it.id }
    }

    fun playlist(id: String): YoutubeApi.Playlist {
        val metadata = read("playlists?part=snippet&id=${enc(id)}")
        val title = metadata.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("snippet")?.optString("title").orEmpty()
        val raw = mutableListOf<YoutubeApi.Track>()
        pages("playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=${enc(id)}") { json ->
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items!!.optJSONObject(i) ?: continue
                val snippet = item.optJSONObject("snippet") ?: continue
                val videoId = item.optJSONObject("contentDetails")?.optString("videoId").orEmpty()
                    .ifBlank { snippet.optJSONObject("resourceId")?.optString("videoId").orEmpty() }
                val name = snippet.optString("title")
                if (!VIDEO_ID.matches(videoId) || name == "Deleted video" || name == "Private video") continue
                raw += YoutubeApi.Track(videoId, name.trim(), snippet.optString("videoOwnerChannelTitle").removeSuffix(" - Topic").trim(), 0L)
            }
        }
        val lengths = mutableMapOf<String, Long>()
        raw.map { it.videoId }.distinct().chunked(50).forEach { ids ->
            val json = read("videos?part=contentDetails&id=${enc(ids.joinToString(","))}")
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items!!.optJSONObject(i) ?: continue
                lengths[item.optString("id")] = YoutubeApi.isoDurationMs(item.optJSONObject("contentDetails")?.optString("duration").orEmpty())
            }
        }
        return YoutubeApi.Playlist(id, title, raw.distinctBy { it.videoId }.map { it.copy(durationMs = lengths[it.videoId] ?: 0L) })
    }

    private fun pages(path: String, consume: (JSONObject) -> Unit) {
        var page: String? = null
        val seen = mutableSetOf<String>()
        do {
            if (Thread.currentThread().isInterrupted) throw IOException("Playlist reading was interrupted. Try again")
            val json = read(path + (page?.let { "&pageToken=${enc(it)}" } ?: ""))
            consume(json)
            page = json.optString("nextPageToken").ifBlank { null }
            if (page != null && !seen.add(page)) throw IOException("YouTube repeated a page. Please try again")
        } while (page != null)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
