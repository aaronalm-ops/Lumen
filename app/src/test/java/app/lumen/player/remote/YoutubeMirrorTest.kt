package app.lumen.player.remote

import android.app.Application
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

/** 0.10.0: Send to YouTube Music, the change plan and the calls against a local fake of the API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YoutubeMirrorTest {
    private fun e(item: String, video: String) = YoutubeMirror.Existing(item, video)

    /** Applies a plan to the existing list, the way YouTube would. */
    private fun apply(existing: List<YoutubeMirror.Existing>, ops: List<YoutubeMirror.Op>): List<String> {
        val list = existing.toMutableList()
        for (op in ops) when (op) {
            is YoutubeMirror.Op.Delete -> list.removeAll { it.itemId == op.itemId }
            is YoutubeMirror.Op.Insert -> list.add(op.position, e("new-${op.videoId}", op.videoId))
            is YoutubeMirror.Op.Move -> {
                val at = list.indexOfFirst { it.itemId == op.itemId }
                list.add(op.position, list.removeAt(at))
            }
        }
        return list.map { it.videoId }
    }

    @Test fun `a first send inserts every song in order`() {
        val ops = YoutubeMirror.plan(listOf("a", "b", "c"), emptyList())
        assertEquals(listOf(YoutubeMirror.Op.Insert("a", 0), YoutubeMirror.Op.Insert("b", 1), YoutubeMirror.Op.Insert("c", 2)), ops)
    }

    @Test fun `an unchanged playlist needs no changes`() {
        assertTrue(YoutubeMirror.plan(listOf("a", "b"), listOf(e("1", "a"), e("2", "b"))).isEmpty())
    }

    @Test fun `songs removed in Lumen or added on YouTube are removed, repeats too`() {
        val existing = listOf(e("1", "a"), e("2", "x"), e("3", "b"), e("4", "a"))
        val ops = YoutubeMirror.plan(listOf("a", "b"), existing)
        assertEquals(listOf(YoutubeMirror.Op.Delete("2", "x"), YoutubeMirror.Op.Delete("4", "a")), ops)
        assertEquals(listOf("a", "b"), apply(existing, ops))
    }

    @Test fun `a new order needs few moves and ends exactly in Lumen's order`() {
        val existing = listOf(e("1", "a"), e("2", "b"), e("3", "c"), e("4", "d"))
        val lastToTop = YoutubeMirror.plan(listOf("d", "a", "b", "c"), existing)
        assertEquals(1, lastToTop.size)
        assertEquals(listOf("d", "a", "b", "c"), apply(existing, lastToTop))
        val mixed = YoutubeMirror.plan(listOf("c", "new", "a", "d"), existing)
        assertEquals(listOf("c", "new", "a", "d"), apply(existing, mixed))
        assertEquals(1, mixed.count { it is YoutubeMirror.Op.Delete })
        assertEquals(1, mixed.count { it is YoutubeMirror.Op.Insert })
    }

    @Test fun `a first send makes a private playlist with Lumen's description and adds the songs`() = fixture { base, state ->
        val result = YoutubeMirror({ "token" }, base).mirror(null, "Drive home", listOf("v1", "v2", "v1"))
        assertTrue(result.created)
        assertEquals("PL1", result.playlistId)
        assertEquals(2, result.added)
        assertEquals("private", state.created!!.getJSONObject("status").getString("privacyStatus"))
        assertEquals(YoutubeMirror.DESCRIPTION, state.created!!.getJSONObject("snippet").getString("description"))
        assertEquals(listOf("v1", "v2"), state.items.map { it.second })
        assertTrue(state.auth.all { it == "Bearer token" })
    }

    @Test fun `an update brings the playlist in step and a deleted playlist is made again`() = fixture { base, state ->
        state.playlists["PL9"] = "Drive home"
        state.items += "i1" to "v1"
        state.items += "i2" to "gone-in-lumen"
        state.items += "i3" to "v3"
        val updated = YoutubeMirror({ "token" }, base).mirror("PL9", "Drive home", listOf("v3", "v1"))
        assertEquals("PL9", updated.playlistId)
        assertEquals(listOf("v3", "v1"), state.items.map { it.second })
        assertEquals(1, updated.removed)
        val again = YoutubeMirror({ "token" }, base).mirror("deleted-on-youtube", "Drive home", listOf("v3"))
        assertTrue(again.created)
    }

    @Test fun `a song YouTube no longer has is skipped and the rest still goes`() = fixture { base, state ->
        state.missing += "removed"
        val result = YoutubeMirror({ "token" }, base).mirror(null, "Mix", listOf("v1", "removed", "v2"))
        assertEquals(1, result.skipped)
        assertEquals(2, result.songs)
        assertEquals(listOf("v1", "v2"), state.items.map { it.second })
    }

    @Test fun `the daily allowance running out says how far it got`() = fixture { base, state ->
        state.quotaAfter = 1
        val e = assertThrows(YoutubeMirror.QuotaUsed::class.java) {
            YoutubeMirror({ "token" }, base).mirror(null, "Mix", listOf("v1", "v2", "v3"))
        }
        assertEquals(1, e.done)
        assertEquals(3, e.total)
        assertEquals("PL1", e.playlistId)
    }

    @Test fun `a missing grant asks to sign in again`() = fixture { base, state ->
        state.refuse = true
        assertThrows(YoutubeApi.NeedsSignIn::class.java) { YoutubeMirror({ "token" }, base).mirror(null, "Mix", listOf("v1")) }
    }

    private class State {
        val playlists = LinkedHashMap<String, String>()
        val items = ArrayList<Pair<String, String>>()
        val missing = HashSet<String>()
        val auth = ArrayList<String>()
        var created: JSONObject? = null
        var quotaAfter = Int.MAX_VALUE
        var writes = 0
        var refuse = false
        var nextId = 1
    }

    private fun fixture(block: (String, State) -> Unit) {
        val state = State()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun send(x: HttpExchange, code: Int, body: JSONObject?) {
            val bytes = (body?.toString() ?: "").toByteArray()
            x.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) x.responseBody.use { it.write(bytes) }
            x.close()
        }
        fun error(x: HttpExchange, code: Int, reason: String) =
            send(x, code, JSONObject().put("error", JSONObject().put("code", code).put("message", reason).put("errors", JSONArray().put(JSONObject().put("reason", reason)))))
        server.createContext("/") { x ->
            state.auth += x.requestHeaders.getFirst("Authorization").orEmpty()
            val body = x.requestBody.bufferedReader().readText().takeIf { it.isNotBlank() }?.let { JSONObject(it) }
            val path = x.requestURI.path.removePrefix("/")
            val query = x.requestURI.query.orEmpty().split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            if (state.refuse) return@createContext error(x, 403, "insufficientPermissions")
            if (x.requestMethod != "GET" && state.writes++ > state.quotaAfter) return@createContext error(x, 403, "quotaExceeded")
            when {
                path == "playlists" && x.requestMethod == "POST" -> {
                    state.created = body
                    val id = "PL${state.nextId++}"
                    state.playlists[id] = body!!.getJSONObject("snippet").getString("title")
                    send(x, 200, JSONObject().put("id", id))
                }
                path == "playlists" && x.requestMethod == "GET" -> {
                    val title = state.playlists[query["id"]]
                    send(x, 200, JSONObject().put("items", if (title == null) JSONArray() else JSONArray().put(JSONObject().put("snippet", JSONObject().put("title", title)))))
                }
                path == "playlists" -> send(x, 200, JSONObject())
                path == "playlistItems" && x.requestMethod == "GET" -> {
                    if (query["playlistId"] !in state.playlists) return@createContext error(x, 404, "playlistNotFound")
                    val list = JSONArray()
                    state.items.forEach { (id, video) -> list.put(JSONObject().put("id", id).put("snippet", JSONObject().put("resourceId", JSONObject().put("videoId", video)))) }
                    send(x, 200, JSONObject().put("items", list))
                }
                path == "playlistItems" && x.requestMethod == "POST" -> {
                    val snippet = body!!.getJSONObject("snippet")
                    val video = snippet.getJSONObject("resourceId").getString("videoId")
                    if (video in state.missing) return@createContext error(x, 404, "videoNotFound")
                    state.items.add(snippet.getInt("position").coerceAtMost(state.items.size), "item-${state.nextId++}" to video)
                    send(x, 200, JSONObject())
                }
                path == "playlistItems" && x.requestMethod == "PUT" -> {
                    val id = body!!.getString("id")
                    val at = state.items.indexOfFirst { it.first == id }
                    val moved = state.items.removeAt(at)
                    state.items.add(body.getJSONObject("snippet").getInt("position"), moved)
                    send(x, 200, JSONObject())
                }
                path == "playlistItems" && x.requestMethod == "DELETE" -> {
                    state.items.removeAll { it.first == query["id"] }
                    send(x, 204, null)
                }
                else -> error(x, 400, "badRequest")
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}", state)
        } finally {
            server.stop(0)
        }
    }
}
