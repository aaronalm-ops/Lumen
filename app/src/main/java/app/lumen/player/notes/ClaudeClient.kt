package app.lumen.player.notes

import app.lumen.player.diag.RingLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.9.0: writes sermon notes with Claude Sonnet 5.5 (Anthropic Messages API, the owner's own key).
 * Only the finished transcript text is sent; audio always stays with Google's transcriber.
 * The notes JSON comes back as the input of a forced tool call, so it always matches the schema.
 */
class ClaudeClient(
    private val apiKey: String,
    private val baseUrl: String = BASE,
    private val waitBeforeRetry: (Long) -> Unit = { Thread.sleep(it) },
    private val maxAttempts: Int = 5,
) {
    class ClaudeException(val code: Int, message: String, val retryAfterMs: Long? = null) : IOException(message)

    fun writeNotes(
        system: String,
        request: String,
        geminiSchema: JSONObject,
        maxTokens: Int = MAX_TOKENS,
        onProgress: (Int) -> Unit = {},
        onRetry: (GeminiClient.Retry) -> Unit = {},
    ): GeminiClient.Reply {
        var attempt = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Notes cancelled")
            attempt++
            try {
                return once(system, request, toJsonSchema(geminiSchema), maxTokens, onProgress)
            } catch (e: ClaudeException) {
                RingLog.w("notes", "$MODEL notes failed: HTTP ${e.code}")
                if (e.code !in RETRYABLE || attempt >= maxAttempts) throw e
                val backoff = minOf(5_000L shl (attempt - 1).coerceAtMost(4), 60_000L) + kotlin.random.Random.nextLong(250)
                val delay = maxOf(backoff, e.retryAfterMs ?: 0L)
                if (delay > 120_000L) throw e
                onRetry(GeminiClient.Retry(e.code, delay, false))
                waitBeforeRetry(delay)
            }
        }
    }

    private fun once(system: String, request: String, schema: JSONObject, maxTokens: Int, onProgress: (Int) -> Unit): GeminiClient.Reply {
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", request)))
            .put("tools", JSONArray().put(JSONObject()
                .put("name", TOOL)
                .put("description", "Save the finished sermon notes.")
                .put("input_schema", schema)))
            .put("tool_choice", JSONObject().put("type", "tool").put("name", TOOL))
            .put("stream", true)
        val c = URL("$baseUrl/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 30_000
        c.readTimeout = 180_000
        c.doOutput = true
        c.setRequestProperty("x-api-key", apiKey)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("accept", "text/event-stream")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw ClaudeException(code, readError(c), retryAfter(c))
            val result = c.inputStream.bufferedReader().use { readStream(it, onProgress) }
            RingLog.i("notes", "$MODEL: ${result.stopReason}, ${result.json.length} characters")
            if (result.stopReason == "max_tokens") throw IOException("Claude's notes were cut off at the length limit. Try again")
            if (result.json.isBlank()) throw IOException("Claude returned no notes. Try again")
            return GeminiClient.Reply(result.json, result.stopReason, MODEL)
        } finally {
            c.disconnect()
        }
    }

    internal data class Streamed(val json: String, val stopReason: String)

    companion object {
        private const val BASE = "https://api.anthropic.com"
        const val MODEL = "claude-sonnet-5-5"
        private const val TOOL = "save_notes"
        private const val MAX_TOKENS = 32_000
        private val RETRYABLE = setOf(429, 500, 502, 503, 504, 529)

        /** Gemini's OpenAPI-style schema ("OBJECT", "STRING") as JSON Schema ("object", "string"). */
        internal fun toJsonSchema(gemini: JSONObject): JSONObject {
            val out = JSONObject()
            for (key in gemini.keys()) {
                when (val value = gemini.get(key)) {
                    is JSONObject -> out.put(key, if (key == "properties") {
                        JSONObject().also { props -> for (name in value.keys()) props.put(name, toJsonSchema(value.getJSONObject(name))) }
                    } else toJsonSchema(value))
                    is String -> out.put(key, if (key == "type") value.lowercase() else value)
                    else -> out.put(key, value)
                }
            }
            return out
        }

        /** Reads the SSE stream: the forced tool call's JSON arrives as input_json_delta pieces. */
        internal fun readStream(reader: BufferedReader, onProgress: (Int) -> Unit = {}): Streamed {
            val json = StringBuilder()
            var stopReason = ""
            var event = ""
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("event:") -> event = line.substringAfter("event:").trim()
                    line.startsWith("data:") -> {
                        val data = runCatching { JSONObject(line.substringAfter("data:").trim()) }.getOrNull() ?: continue
                        val type = data.optString("type", event)
                        when (type) {
                            "content_block_delta" -> data.optJSONObject("delta")?.let { delta ->
                                if (delta.optString("type") == "input_json_delta") {
                                    json.append(delta.optString("partial_json"))
                                    onProgress(json.length)
                                }
                            }
                            "message_delta" -> data.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() }?.let { stopReason = it }
                            "error" -> {
                                val error = data.optJSONObject("error")
                                val code = when (error?.optString("type")) {
                                    "overloaded_error" -> 529
                                    "rate_limit_error" -> 429
                                    "api_error" -> 500
                                    else -> 400
                                }
                                throw ClaudeException(code, error?.optString("message").orEmpty().ifBlank { "Claude stream error" })
                            }
                        }
                    }
                }
            }
            return Streamed(json.toString(), stopReason)
        }

        private fun readError(c: HttpURLConnection): String {
            val body = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
            val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
            return "${c.responseCode} ${message.ifBlank { body.take(200) }}"
        }

        private fun retryAfter(c: HttpURLConnection): Long? =
            c.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { (it * 1000).toLong() }
    }
}
