package app.lumen.player.notes

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.9.0: the pure pieces of the Gemini 3.5 Transcribe path. */
class TranscribePartsTest {
    private fun w(text: String, start: Long, end: Long = start + 300, speaker: String? = "spk_1") =
        TranscriptLines.Word(text, start, end, speaker)

    @Test fun `offsets read as milliseconds`() {
        assertEquals(100L, TranscriptLines.offsetMs("0.100s"))
        assertEquals(12_000L, TranscriptLines.offsetMs("12s"))
        assertEquals(1_500L, TranscriptLines.offsetMs("1.5"))
        assertEquals(-1L, TranscriptLines.offsetMs(""))
        assertEquals(-1L, TranscriptLines.offsetMs("soon"))
    }

    @Test fun `words become lines at sentence ends pauses and speaker changes, offset by the slice start`() {
        val words = listOf(
            w("Grace", 0), w("and", 400), w("peace", 800), w("to", 1200), w("you", 1600), w("all.", 2000),
            w("Open", 2400), w("your", 2800), w("Bibles", 3200),
            w("Amen", 6000),
            w("Welcome", 6500, speaker = "spk_2"),
        )
        val lines = TranscriptLines.group(words, offsetMs = 1_200_000)
        assertEquals(
            listOf(1_200_000L to "Grace and peace to you all.", 1_202_400L to "Open your Bibles", 1_206_000L to "Amen", 1_206_500L to "Welcome"),
            lines,
        )
    }

    @Test fun `long speech without punctuation still breaks every 20 seconds`() {
        val words = (0 until 100).map { w("word", it * 500L) }
        val lines = TranscriptLines.group(words)
        assertTrue(lines.size >= 2)
        assertTrue(lines.zipWithNext().all { (a, b) -> b.first > a.first })
    }

    @Test fun `text without word times is spread across the slice`() {
        val lines = TranscriptLines.spread("One. Two! Three?", 60_000, 30_000)
        assertEquals(listOf(60_000L to "One.", 70_000L to "Two!", 80_000L to "Three?"), lines)
    }

    @Test fun `interactions reply is parsed for text and word times`() {
        val json = JSONObject("""{"id":"interactions/abc","status":"completed","steps":[{"id":"s1","type":"model_output","content":[
            {"type":"text","text":"Hello world","annotations":[
              {"type":"word_info","text":"Hello","speaker":"spk_1","start_offset":"0.100s","end_offset":"0.450s"},
              {"type":"word_info","text":"world","speaker":"spk_1","start_offset":"0.500s","end_offset":"0.850s"}]}]}]}""")
        val t = GeminiClient.parseTranscription(json)
        assertEquals("Hello world", t.text)
        assertEquals(listOf(100L, 500L), t.words.map { it.startMs })
        assertEquals(listOf(450L, 850L), t.words.map { it.endMs })
    }

    @Test fun `parts are 20 minutes with a short tail joined to the last part`() {
        val minute = 60_000L
        assertEquals(listOf(0L to 20 * minute, 20 * minute to 40 * minute, 40 * minute to 63 * minute + 2_000), AudioSlicer.plan(63 * minute, 0))
        assertEquals(listOf(0L to 24 * minute + 2_000), AudioSlicer.plan(24 * minute, 0))
        assertEquals(listOf(40 * minute to 63 * minute + 2_000), AudioSlicer.plan(63 * minute, 40 * minute))
        assertTrue(AudioSlicer.plan(63 * minute, 63 * minute).isEmpty())
        assertTrue(AudioSlicer.plan(107 * minute, 0).all { (a, b) -> b - a <= 25 * minute + 2_000 })
    }

    @Test fun `only mp3 and aac recordings are cut`() {
        assertTrue(AudioSlicer.canTry("audio/mpeg", "x"))
        assertTrue(AudioSlicer.canTry("audio/mp4", "x"))
        assertTrue(AudioSlicer.canTry("", "Sermon.MP3"))
        assertTrue(!AudioSlicer.canTry("audio/ogg", "x.mp3"))
        assertTrue(!AudioSlicer.canTry("audio/wav", "x"))
    }

    @Test fun `aac config becomes a valid adts header`() {
        // AAC-LC, 44.1 kHz (index 4), stereo: 0x12 0x10.
        val config = AudioSlicer.parseAudioSpecificConfig(byteArrayOf(0x12, 0x10))!!
        assertEquals(AudioSlicer.Adts(2, 4, 2), config)
        val header = AudioSlicer.adtsHeader(config, 100)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x0D, 0x7F, 0xFC.toByte()), header)
        // HE-AAC (object type 5) cannot be written as ADTS here.
        assertNull(AudioSlicer.parseAudioSpecificConfig(byteArrayOf(0x2B, 0x92.toByte())))
    }
}
