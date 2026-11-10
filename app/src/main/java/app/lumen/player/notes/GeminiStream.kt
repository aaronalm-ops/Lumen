package app.lumen.player.notes

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException

/** SSE boundaries and JSON boundaries are independent. Preserve text across both. */
internal object GeminiStream {
    data class Result(val text: String, val finishReason: String)

    fun read(reader: BufferedReader, onText: ((String) -> Unit)?): Result {
        val text = StringBuilder()
        val event = StringBuilder()
        var finish = ""
        fun consume() {
            val data = event.toString().trim()
            event.setLength(0)
            if (data.isEmpty() || data == "[DONE]") return
            val json = JSONObject(data)
            json.optJSONObject("error")?.let {
                throw GeminiClient.GeminiException(it.optInt("code", 500), it.optString("message", "Google interrupted the request"))
            }
            val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            if (blocked.isNotBlank()) throw IOException("Google declined this recording ($blocked)")
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return
            candidate.optString("finishReason").takeIf { it.isNotBlank() }?.let { finish = it }
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            var added = false
            for (i in 0 until (parts?.length() ?: 0)) {
                val part = parts!!.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                val delta = part.optString("text")
                if (delta.isNotEmpty()) { text.append(delta); added = true }
            }
            if (added) onText?.invoke(text.toString())
        }
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Notes cancelled")
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> consume()
                line.startsWith("data:") -> {
                    if (event.isNotEmpty()) event.append('\n')
                    event.append(line.removePrefix("data:").trimStart())
                }
            }
        }
        consume()
        if (finish.isBlank()) throw IOException("The connection ended before Google finished. Completed transcript lines have been kept")
        if (finish != "STOP" && finish != "MAX_TOKENS") throw IOException("Google stopped before finishing ($finish). Completed transcript lines have been kept")
        return Result(text.toString(), finish)
    }
}
