package app.lumen.player.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class YoutubeCatalogTest {
    @Test fun `reads past the old forty page cutoff and keeps playlist order`() {
        var pages = 0
        val catalog = YoutubeCatalog { path ->
            when {
                path.startsWith("playlists?") -> JSONObject("""{"items":[{"snippet":{"title":"Long playlist"}}]}""")
                path.startsWith("playlistItems?") -> {
                    val page = ++pages
                    JSONObject().put("items", JSONArray().put(track("%011d".format(page), "Song $page")))
                        .also { if (page < 45) it.put("nextPageToken", "page $page") }
                }
                else -> JSONObject("""{"items":[]} """)
            }
        }
        val playlist = catalog.playlist("long")
        assertEquals(45, playlist.tracks.size)
        assertEquals("Song 45", playlist.tracks.last().title)
        assertEquals("00000000001", playlist.tracks.first().videoId)
    }

    @Test fun `account list includes private playlists on later pages`() {
        val catalog = YoutubeCatalog { path ->
            if (path.contains("pageToken=")) JSONObject("""{"items":[{"id":"private","snippet":{"title":"My private list"},"contentDetails":{"itemCount":4},"status":{"privacyStatus":"private"}}]}""")
            else JSONObject("""{"items":[{"id":"public","snippet":{"title":"Public list"}}],"nextPageToken":"second"}""")
        }
        val lists = catalog.playlists()
        assertEquals(listOf("public", "private"), lists.map { it.id })
        assertEquals(4, lists.last().trackCount)
        assertEquals("private", lists.last().privacy)
    }

    @Test fun `skips deleted items and batches unique videos without losing order`() {
        val catalog = YoutubeCatalog { path ->
            when {
                path.startsWith("playlistItems?") -> JSONObject().put("items", JSONArray()
                    .put(track("aaaaaaaaaaa", "A")).put(track("bbbbbbbbbbb", "Deleted video"))
                    .put(track("aaaaaaaaaaa", "A again")).put(track("ccccccccccc", "C")))
                path.startsWith("videos?") -> JSONObject("""{"items":[{"id":"aaaaaaaaaaa","contentDetails":{"duration":"PT3M41S"}}]}""")
                else -> JSONObject("""{"items":[]}""")
            }
        }
        val tracks = catalog.playlist("test").tracks
        assertEquals(listOf("A", "C"), tracks.map { it.title })
        assertEquals(221_000L, tracks.first().durationMs)
    }

    @Test fun `a failed later page never returns a silently incomplete import`() {
        val catalog = YoutubeCatalog { path ->
            if (path.contains("pageToken=")) throw IOException("Connection lost")
            JSONObject("""{"items":[],"nextPageToken":"next"}""")
        }
        assertThrows(IOException::class.java) { catalog.playlists() }
    }

    @Test fun `repeated page tokens fail instead of looping`() {
        val catalog = YoutubeCatalog { JSONObject("""{"items":[],"nextPageToken":"same"}""") }
        assertThrows(IOException::class.java) { catalog.playlists() }
    }

    private fun track(id: String, title: String) = JSONObject()
        .put("contentDetails", JSONObject().put("videoId", id))
        .put("snippet", JSONObject().put("title", title).put("videoOwnerChannelTitle", "Artist - Topic"))
}
