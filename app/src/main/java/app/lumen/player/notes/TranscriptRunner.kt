package app.lumen.player.notes

import java.io.IOException

/** Bounded output batches. Completed segments are saved even if a later SSE event fails. */
internal class TranscriptRunner(
    private val generate: (after: String?, onText: (String) -> Unit) -> GeminiClient.Reply,
    private val persist: (newLines: List<Pair<Long, String>>, all: List<Pair<Long, String>>, complete: Boolean) -> Unit,
    private val maxRounds: Int = 24,
) {
    fun run(durationMs: Long, initial: List<Pair<Long, String>> = emptyList()): List<Pair<Long, String>> {
        val out = initial.sortedBy { it.first }.distinctBy { it.first }.toMutableList()
        var savedCount = out.size
        var lastSave = System.nanoTime()
        var complete = false
        fun flush(done: Boolean) {
            if (out.size > savedCount || done) {
                persist(out.drop(savedCount), out.toList(), done)
                savedCount = out.size
                lastSave = System.nanoTime()
            }
        }
        try {
            repeat(maxRounds) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Notes cancelled")
                val before = out.size
                val floor = out.lastOrNull()?.first ?: -1L
                fun accept(text: String) {
                    val current = out.lastOrNull()?.first ?: -1L
                    val lines = parseSegments(text).sortedBy { it.first }.distinctBy { it.first }
                        .filter { it.first > current && (durationMs <= 0 || it.first <= durationMs + 30_000L) }
                    out.addAll(lines)
                    if (out.size - savedCount >= 8 || System.nanoTime() - lastSave >= 1_000_000_000L) flush(false)
                }
                val reply = generate(floor.takeIf { it >= 0 }?.let { stamp(it) }, ::accept)
                accept(reply.text)
                flush(false)
                if (out.isEmpty()) throw IOException("Google returned no readable speech. Try another recording or check the audio")
                val short = durationMs > 0 && out.last().first < durationMs - 120_000L
                if (reply.finishReason == "STOP" && !short) {
                    flush(true)
                    complete = true
                    return out
                }
                if (out.size == before) throw IOException("Transcription stopped at ${stamp(out.last().first)}. Saved text is available; try again to continue")
            }
            throw IOException("The recording needs more sections. Saved transcript lines are available; try again to continue")
        } finally {
            if (!complete) flush(false)
        }
    }
}
