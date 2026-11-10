package app.lumen.player.notes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TranscriptRunnerTest {
    @Test fun `partial streamed text survives a failed request and retry resumes after saved time`() {
        var saved = emptyList<Pair<Long, String>>()
        val broken = TranscriptRunner(
            generate = { _, onText -> onText(lines(0L to "First", 20_000L to "Second")); throw IOException("Connection dropped") },
            persist = { _, all, _ -> saved = all },
        )
        assertThrows(IOException::class.java) { broken.run(0) }
        assertEquals(2, saved.size)
        var requestedAfter: String? = null
        var complete = false
        val resumed = TranscriptRunner(
            generate = { after, onText ->
                requestedAfter = after
                val text = lines(20_000L to "Repeated", 30_000L to "Third")
                onText(text)
                GeminiClient.Reply(text, "STOP", "test-model")
            },
            persist = { _, all, done -> saved = all; complete = done },
        ).run(0, saved)
        assertEquals("00:20", requestedAfter)
        assertEquals(listOf("First", "Second", "Third"), resumed.map { it.second })
        assertTrue(complete)
    }

    @Test fun `output limit continues without duplicating streamed lines`() {
        var round = 0
        var savedCount = 0
        val result = TranscriptRunner(
            generate = { after, onText ->
                round++
                if (round == 2) assertEquals("00:10", after)
                val text = if (round == 1) lines(0L to "A", 10_000L to "B") else lines(20_000L to "C")
                onText(text)
                onText(text)
                GeminiClient.Reply(text, if (round == 1) "MAX_TOKENS" else "STOP", "test-model")
            },
            persist = { delta, _, _ -> savedCount += delta.size },
        ).run(0)
        assertEquals(3, result.size)
        assertEquals(3, savedCount)
    }

    @Test fun `uncovered audio cannot silently become a completed transcript`() {
        var done = false
        val runner = TranscriptRunner(
            generate = { _, onText -> val text = lines(0L to "Only the start"); onText(text); GeminiClient.Reply(text, "STOP", "test-model") },
            persist = { _, _, complete -> done = complete }, maxRounds = 2,
        )
        assertThrows(IOException::class.java) { runner.run(3_600_000) }
        assertFalse(done)
    }

    private fun lines(vararg values: Pair<Long, String>): String = JSONArray().also { array ->
        values.forEach { (ms, text) -> array.put(JSONObject().put("t", stamp(ms)).put("s", text)) }
    }.toString()
}
