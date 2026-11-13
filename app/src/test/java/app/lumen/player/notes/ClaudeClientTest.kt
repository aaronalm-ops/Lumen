package app.lumen.player.notes

import android.app.Application
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** 0.9.0: notes from Claude Sonnet through a forced tool call, against a local fixture server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ClaudeClientTest {
    @Test fun `gemini schema becomes json schema`() {
        val converted = ClaudeClient.toJsonSchema(NotesPrompts.notesSchema)
        assertEquals("object", converted.getString("type"))
        val sections = converted.getJSONObject("properties").getJSONObject("sections")
        assertEquals("array", sections.getString("type"))
        assertEquals("string", sections.getJSONObject("items").getJSONObject("properties").getJSONObject("heading").getString("type"))
        assertTrue(converted.getJSONArray("required").length() > 0)
    }

    @Test fun `busy answers are retried and the streamed tool input is the notes json`() {
        val calls = AtomicInteger()
        var request = JSONObject()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/messages") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            if (calls.incrementAndGet() == 1) {
                val bytes = """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""".toByteArray()
                exchange.sendResponseHeaders(529, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return@createContext
            }
            request = JSONObject(body)
            val events = listOf(
                """{"type":"message_start","message":{"id":"m1"}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","name":"save_notes","input":{}}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"title\":\"Fix"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"ture\",\"sections\":[]}"}}""",
                """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
                """{"type":"message_stop"}""",
            ).joinToString("") { "event: x\ndata: $it\n\n" }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, events.size.toLong())
            exchange.responseBody.use { it.write(events) }
        }
        server.start()
        try {
            val delays = mutableListOf<Long>()
            val reply = ClaudeClient("synthetic-key", "http://127.0.0.1:${server.address.port}", waitBeforeRetry = { delays += it })
                .writeNotes("system", "transcript", NotesPrompts.notesSchema)
            assertEquals("""{"title":"Fixture","sections":[]}""", reply.text)
            assertEquals(ClaudeClient.MODEL, reply.model)
            assertEquals(2, calls.get())
            assertEquals(1, delays.size)
            assertEquals("save_notes", request.getJSONObject("tool_choice").getString("name"))
            assertEquals("object", request.getJSONArray("tools").getJSONObject(0).getJSONObject("input_schema").getString("type"))
        } finally { server.stop(0) }
    }

    @Test fun `a refused key is not retried`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            calls.incrementAndGet()
            val bytes = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""".toByteArray()
            exchange.sendResponseHeaders(401, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val e = assertThrows(ClaudeClient.ClaudeException::class.java) {
                ClaudeClient("bad", "http://127.0.0.1:${server.address.port}", waitBeforeRetry = {}).writeNotes("s", "t", NotesPrompts.notesSchema)
            }
            assertEquals(401, e.code)
            assertEquals(1, calls.get())
        } finally { server.stop(0) }
    }
}
