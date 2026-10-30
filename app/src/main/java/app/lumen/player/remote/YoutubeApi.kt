package app.lumen.player.remote

import android.content.Context
import android.content.Intent
import app.lumen.player.diag.RingLog
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 0.8.1: reads a YouTube or YouTube Music playlist so its songs can be picked into Lumen one by
 * one. YouTube Data API v3 with the owner's own Google account (scope youtube.readonly, asked once
 * through the same Play Services sheet as Drive), so his private playlists and albums work too.
 * Lumen only reads: titles, artists, lengths and video ids. Nothing is played or downloaded here.
 *
 * Calls: playlists.list (the title, 1 unit), playlistItems.list (50 per page, 1 unit each) and
 * videos.list (lengths, 50 per call, 1 unit each). The daily allowance is 10,000 units.
 */
class YoutubeApi(private val context: Context) {

    data class Track(val videoId: String, val title: String, val artist: String, val durationMs: Long)

    data class Playlist(val id: String, val title: String, val tracks: List<Track>)

    class NotConfigured(message: String) : IOException(message)

    class NeedsSignIn : IOException("Sign in to YouTube")

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedAt = 0L

    private fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** The Task MainActivity shows the sheet for when `hasResolution()`. */
    fun authorizeTask(): Task<AuthorizationResult> = Identity.getAuthorizationClient(context).authorize(request())

    fun resultFromIntent(data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    fun accept(result: AuthorizationResult): String? {
        val token = result.accessToken?.takeIf { it.isNotBlank() } ?: return null
        cachedToken = token
        cachedAt = System.currentTimeMillis()
        return token
    }

    /** A token without any UI, or [NeedsSignIn]. Never on the main thread. */
    private fun tokenBlocking(): String {
        cachedToken?.let { if (System.currentTimeMillis() - cachedAt < TOKEN_REUSE_MS) return it }
        val result = Tasks.await(authorizeTask(), 20, TimeUnit.SECONDS)
        if (result.hasResolution()) throw NeedsSignIn()
        return accept(result) ?: throw NeedsSignIn()
    }

    /** The whole playlist, in its own order, without deleted or private videos. */
    fun fetchPlaylist(listId: String): Playlist {
        val token = tokenBlocking()
        val title = try {
            getJson(token, "$API/playlists?part=snippet&id=${enc(listId)}")
                .optJSONArray("items")?.optJSONObject(0)?.optJSONObject("snippet")?.optString("title").orEmpty()
        } catch (e: NotConfigured) {
            throw e
        } catch (t: Throwable) {
            RingLog.w("youtube", "playlist title not read: $t")
            ""
        }
        val raw = ArrayList<Track>()
        var page: String? = null
        var pages = 0
        do {
            val url = "$API/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=${enc(listId)}" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = getJson(token, url)
            val items = json.optJSONArray("items")
            for (i in 0 until (items?.length() ?: 0)) {
                val item = items!!.optJSONObject(i) ?: continue
                val snippet = item.optJSONObject("snippet") ?: continue
                val videoId = item.optJSONObject("contentDetails")?.optString("videoId").orEmpty()
                    .ifBlank { snippet.optJSONObject("resourceId")?.optString("videoId").orEmpty() }
                val name = snippet.optString("title")
                if (videoId.length != 11 || name == "Deleted video" || name == "Private video") continue
                val artist = snippet.optString("videoOwnerChannelTitle").removeSuffix(" - Topic").trim()
                raw += Track(videoId, name.trim(), artist, 0L)
            }
            page = json.optString("nextPageToken").ifBlank { null }
            pages++
        } while (page != null && pages < MAX_PAGES)
        val lengths = HashMap<String, Long>()
        raw.map { it.videoId }.distinct().chunked(50).forEach { ids ->
            try {
                val json = getJson(token, "$API/videos?part=contentDetails&id=${enc(ids.joinToString(","))}")
                val items = json.optJSONArray("items")
                for (i in 0 until (items?.length() ?: 0)) {
                    val v = items!!.optJSONObject(i) ?: continue
                    lengths[v.optString("id")] = isoDurationMs(v.optJSONObject("contentDetails")?.optString("duration").orEmpty())
                }
            } catch (t: Throwable) {
                RingLog.w("youtube", "lengths not read: $t")
            }
        }
        val tracks = raw.distinctBy { it.videoId }.map { it.copy(durationMs = lengths[it.videoId] ?: 0L) }
        RingLog.i("youtube", "playlist $listId: ${tracks.size} songs in $pages pages")
        return Playlist(listId, title, tracks)
    }

    private fun getJson(token: String, url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $token")
        try {
            val code = c.responseCode
            if (code == 401) {
                cachedToken = null
                throw NeedsSignIn()
            }
            if (code !in 200..299) {
                val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val reason = try {
                    JSONObject(body).optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                val message = try {
                    JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
                } catch (_: Throwable) {
                    ""
                }
                RingLog.w("youtube", "$code $reason $message")
                if (code == 403 && (reason == "accessNotConfigured" || message.contains("has not been used", true) || message.contains("disabled", true))) {
                    throw NotConfigured("YouTube Data API is not switched on in Google Cloud yet")
                }
                if (code == 403 && (reason.contains("insufficient", true) || message.contains("scope", true))) throw NeedsSignIn()
                if (code == 404) throw IOException("This playlist was not found. It may be private to another account, or a mix YouTube makes on the fly")
                if (code == 403 && reason.contains("quota", true)) throw IOException("YouTube's daily allowance is used up. Try again tomorrow")
                throw IOException("YouTube said: ${message.ifBlank { "error $code" }}")
            }
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/youtube.readonly"
        private const val API = "https://www.googleapis.com/youtube/v3"
        private const val TOKEN_REUSE_MS = 45L * 60 * 1000

        /** 50 songs a page: 2,000 songs is plenty for one import. */
        private const val MAX_PAGES = 40

        /** "PT3M41S" to milliseconds. */
        fun isoDurationMs(iso: String): Long {
            val m = Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(iso) ?: return 0L
            val (d, h, min, s) = m.destructured
            return (((d.toLongOrNull() ?: 0L) * 24 + (h.toLongOrNull() ?: 0L)) * 3600 +
                (min.toLongOrNull() ?: 0L) * 60 + (s.toLongOrNull() ?: 0L)) * 1000
        }
    }
}
