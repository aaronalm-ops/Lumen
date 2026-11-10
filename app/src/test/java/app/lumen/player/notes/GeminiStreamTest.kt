package app.lumen.player.notes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GeminiStreamTest {
    @Test fun `SSE fragments form one transcript and thoughts never enter saved text`() {
        val first = event("[{\"t\":\"00:00\",\"s\":\"Hello")
        val thought = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("thought", true).put("text", "hidden"))))))
        val last = event(" world\"}]", "STOP")
        val updates = mutableListOf<String>()
        val result = GeminiStream.read((": keepalive\n\ndata: $first\n\ndata: $thought\n\ndata: $last\n\ndata: [DONE]\n\n").reader().buffered()) { updates += it }
        assertEquals("Hello world", parseSegments(result.text).single().second)
        assertEquals("STOP", result.finishReason)
        assertEquals(2, updates.size)
        assertFalse(result.text.contains("hidden"))
    }

    @Test fun `unexpected end fails after exposing completed text to progress handler`() {
        var partial = ""
        val input = "data: ${event("[{\"t\":\"00:00\",\"s\":\"Saved\"}]")}\n\n"
        assertThrows(IOException::class.java) { GeminiStream.read(input.reader().buffered()) { partial = it } }
        assertEquals("Saved", parseSegments(partial).single().second)
    }

    @Test fun `embedded API error cannot be treated as a successful HTTP 200 transcript`() {
        assertThrows(GeminiClient.GeminiException::class.java) {
            GeminiStream.read("data: {\"error\":{\"code\":429,\"message\":\"Quota\"}}\n\n".reader().buffered(), null)
        }
    }

    private fun event(text: String, finish: String = "") = JSONObject().put("candidates", JSONArray().put(JSONObject()
        .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))
        .also { if (finish.isNotBlank()) it.put("finishReason", finish) })).toString()
}
