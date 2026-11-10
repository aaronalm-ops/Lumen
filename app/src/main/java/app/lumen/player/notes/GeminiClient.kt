package app.lumen.player.notes

import app.lumen.player.diag.RingLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 0.6.0: the Gemini API over plain HTTPS, with the owner's own key (Google AI Studio). Three calls:
 * a resumable upload to the Files API, generateContent against the uploaded file or text, and a
 * delete of the file once the transcript is in. The key is sent as the x-goog-api-key header,
 * never in a url.
 */
class GeminiClient(private val apiKey: String, private val baseUrl: String = BASE, private val requestTimeoutMs: Long = 180_000L) {

    class GeminiException(val code: Int, message: String) : IOException(message)

    data class UploadedFile(val name: String, val uri: String, val mimeType: String)

    data class Reply(val text: String, val finishReason: String, val model: String)

    /**
     * Streams [size] bytes from [input] to the Files API. [onProgress] gets 0 to 1. The file is
     * kept by Google for at most 48 hours; Lumen deletes it as soon as the transcript is in.
     */
    fun upload(input: InputStream, size: Long, mimeType: String, displayName: String, onProgress: (Float) -> Unit): UploadedFile {
        val start = open("$baseUrl/upload/v1beta/files", "POST", connectTimeoutMs = 30_000, readTimeoutMs = 60_000)
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
        start.setRequestProperty("X-Goog-Upload-Command", "start")
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mimeType)
        start.setRequestProperty("Content-Type", "application/json")
        start.doOutput = true
        val body = JSONObject().put("file", JSONObject().put("display_name", displayName.take(120))).toString()
        start.outputStream.use { it.write(body.toByteArray()) }
        val code = start.responseCode
        if (code !in 200..299) throw GeminiException(code, "upload start: ${readError(start)}")
        val uploadUrl = start.getHeaderField("x-goog-upload-url") ?: start.getHeaderField("X-Goog-Upload-URL")
            ?: throw IOException("upload start: no upload url")
        start.disconnect()

        val put = URL(uploadUrl).openConnection() as HttpURLConnection
        put.requestMethod = "POST"
        put.connectTimeout = 30_000
        put.readTimeout = 10 * 60_000
        put.doOutput = true
        put.setFixedLengthStreamingMode(size)
        put.setRequestProperty("X-Goog-Upload-Offset", "0")
        put.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        put.setRequestProperty("Content-Type", mimeType)
        var sent = 0L
        var lastShown = -1f
        put.outputStream.use { out ->
            val buffer = ByteArray(64 * 1024)
            while (sent < size) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - sent).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                sent += n
                val f = sent.toFloat() / size
                if (f - lastShown >= 0.01f) {
                    lastShown = f
                    onProgress(f)
                }
            }
        }
        if (sent < size) throw IOException("upload: the file ended after $sent of $size bytes")
        val upCode = put.responseCode
        if (upCode !in 200..299) throw GeminiException(upCode, "upload: ${readError(put)}")
        val file = JSONObject(put.inputStream.bufferedReader().use { it.readText() }).optJSONObject("file")
            ?: throw IOException("upload: no file in the reply")
        put.disconnect()
        return UploadedFile(file.optString("name"), file.optString("uri"), file.optString("mimeType", mimeType))
    }

    /** Waits until the uploaded file can be used (state ACTIVE), for up to [timeoutMs]. */
    fun waitUntilActive(file: UploadedFile, timeoutMs: Long = 10 * 60_000L, onWaiting: (Long) -> Unit = {}) {
        val started = System.nanoTime()
        val until = started + timeoutMs * 1_000_000
        while (true) {
            val c = open("$baseUrl/v1beta/${file.name}", "GET")
            val code = c.responseCode
            if (code !in 200..299) throw GeminiException(code, "file state: ${readError(c)}")
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            when (json.optString("state", "ACTIVE")) {
                "ACTIVE", "" -> return
                "FAILED" -> throw IOException("Google could not read this recording")
            }
            onWaiting((System.nanoTime() - started) / 1_000_000)
            if (System.nanoTime() > until) throw IOException("Google took too long to prepare the recording")
            Thread.sleep(4_000)
        }
    }

    fun delete(file: UploadedFile) {
        try {
            val c = open("$baseUrl/v1beta/${file.name}", "DELETE")
            val code = c.responseCode
            c.disconnect()
            RingLog.i("notes", "uploaded file deleted ($code)")
        } catch (t: Throwable) {
            RingLog.w("notes", "delete of the uploaded file failed: $t; Google removes it within 48 hours")
        }
    }

    /**
     * One generateContent call. [parts] are Gemini content parts; [schema] is the response schema.
     * Tries each model in [models] in turn when a model is not found, and drops the thinking
     * setting once if the model refuses it.
     */
    fun generate(
        models: List<String>,
        system: String,
        parts: JSONArray,
        schema: JSONObject,
        maxOutputTokens: Int,
        thinking: String?,
        onText: ((String) -> Unit)? = null,
    ): Reply {
        var lastError: Throwable? = null
        for (model in models) {
            var withThinking = thinking
            var attempts = 0
            while (attempts < 2) {
                attempts++
                try {
                    return generateOnce(model, system, parts, schema, maxOutputTokens, withThinking, onText)
                } catch (e: GeminiException) {
                    lastError = e
                    if (e.code == 400 && withThinking != null && e.message.orEmpty().contains("think", ignoreCase = true)) {
                        withThinking = null
                    } else if (e.code == 404) {
                        break
                    } else {
                        throw e
                    }
                }
            }
            RingLog.w("notes", "model $model not usable: ${lastError?.message}")
        }
        throw lastError ?: IOException("no model answered")
    }

    private fun generateOnce(model: String, system: String, parts: JSONArray, schema: JSONObject, maxOutputTokens: Int, thinking: String?, onText: ((String) -> Unit)?): Reply {
        val config = JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseSchema", schema)
            .put("maxOutputTokens", maxOutputTokens)
        if (thinking != null) config.put("thinkingConfig", JSONObject().put("thinkingLevel", thinking))
        val request = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", config)
        val c = open("$baseUrl/v1beta/models/$model:streamGenerateContent?alt=sse", "POST", readTimeoutMs = minOf(180_000L, requestTimeoutMs).toInt())
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "text/event-stream")
        c.doOutput = true
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val deadline = java.util.Timer("Lumen-notes-deadline", true)
        deadline.schedule(object : java.util.TimerTask() {
            override fun run() { timedOut.set(true); c.disconnect() }
        }, requestTimeoutMs)
        try {
            c.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw GeminiException(code, readError(c))
            val result = c.inputStream.bufferedReader().use { GeminiStream.read(it, onText) }
            RingLog.i("notes", "$model: ${result.finishReason}, ${result.text.length} streamed characters")
            return Reply(result.text, result.finishReason, model)
        } catch (error: IOException) {
            if (timedOut.get()) throw java.net.SocketTimeoutException("Google did not finish this section within three minutes. Completed transcript lines have been kept")
            throw error
        } finally {
            deadline.cancel()
            c.disconnect()
        }
    }

    private fun open(url: String, method: String, connectTimeoutMs: Int = 30_000, readTimeoutMs: Int = 60_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("x-goog-api-key", apiKey)
        return c
    }

    private fun readError(c: HttpURLConnection): String {
        val body = try {
            c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val message = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Throwable) {
            ""
        }
        return "${c.responseCode} ${message.ifBlank { body.take(200) }}"
    }

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com"

        /** Newest first; the next is tried when Google no longer offers one (verified 2026-10-27). */
        val MODELS = listOf("gemini-3.8-flash", "gemini-3.6-flash")
    }
}
