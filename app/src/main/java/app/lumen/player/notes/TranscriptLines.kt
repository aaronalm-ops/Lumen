package app.lumen.player.notes

/**
 * 0.9.0: turns Gemini 3.5 Transcribe's timed words into Lumen's timed transcript lines.
 * Pure functions, so the line rules are unit tested without a network or Android.
 */
internal object TranscriptLines {
    data class Word(val text: String, val startMs: Long, val endMs: Long, val speaker: String?)

    private const val MIN_WORDS_BEFORE_SENTENCE_BREAK = 6
    private const val MAX_LINE_MS = 20_000L
    private const val MAX_WORDS = 60
    private const val PAUSE_BREAK_MS = 1_500L

    /** "0.100s", "12s" or "1.5" to milliseconds; -1 when it cannot be read. */
    fun offsetMs(text: String?): Long {
        val clean = text?.trim()?.removeSuffix("s")?.trim().orEmpty()
        if (clean.isEmpty()) return -1L
        return clean.toDoubleOrNull()?.takeIf { it >= 0 && !it.isNaN() }?.let { (it * 1000).toLong() } ?: -1L
    }

    /**
     * Lines from [words], whose times are relative to a slice that starts at [offsetMs] in the
     * recording. A line ends at a sentence end (once it has a few words), a pause, a change of
     * speaker, or when it grows past 20 seconds. Start times are strictly increasing.
     */
    fun group(words: List<Word>, offsetMs: Long = 0L): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        val current = ArrayList<Word>()
        fun flush() {
            if (current.isEmpty()) return
            val text = current.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+([,.;:!?])"), "$1").trim()
            if (text.isNotEmpty()) {
                var start = offsetMs + current.first().startMs
                out.lastOrNull()?.let { if (start <= it.first) start = it.first + 1 }
                out += start to text
            }
            current.clear()
        }
        for (word in words.sortedBy { it.startMs }) {
            if (word.text.isBlank()) continue
            val last = current.lastOrNull()
            if (last != null) {
                val speakerChanged = word.speaker != null && last.speaker != null && word.speaker != last.speaker
                val paused = word.startMs - last.endMs >= PAUSE_BREAK_MS
                val sentenceEnded = current.size >= MIN_WORDS_BEFORE_SENTENCE_BREAK && last.text.trimEnd().lastOrNull() in SENTENCE_ENDS
                val tooLong = word.startMs - current.first().startMs >= MAX_LINE_MS || current.size >= MAX_WORDS
                if (speakerChanged || paused || sentenceEnded || tooLong) flush()
            }
            current += word
        }
        flush()
        return out
    }

    /**
     * Fallback when a reply has text but no word times: sentences spread evenly across the slice,
     * so the transcript is still searchable and roughly placed.
     */
    fun spread(text: String, offsetMs: Long, lengthMs: Long): List<Pair<Long, String>> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        if (sentences.isEmpty()) return emptyList()
        val step = (lengthMs.coerceAtLeast(1L) / sentences.size).coerceAtLeast(1L)
        return sentences.mapIndexed { i, s -> (offsetMs + i * step) to s }
    }

    private val SENTENCE_ENDS = setOf('.', '!', '?')
}
