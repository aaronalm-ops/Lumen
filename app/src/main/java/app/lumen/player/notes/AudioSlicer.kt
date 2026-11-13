package app.lumen.player.notes

import android.media.MediaExtractor
import android.media.MediaFormat
import app.lumen.player.diag.RingLog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 0.9.0: cuts a recording into pieces Gemini 3.5 Transcribe accepts (up to 30 minutes each with
 * word timestamps) without re-encoding. MP3 frames are written as they are; AAC frames get ADTS
 * headers. Other formats return null and the caller uses the older whole-file path.
 */
internal class AudioSlicer(private val setSource: (MediaExtractor) -> Unit) {

    data class Slice(val file: File, val startMs: Long, val endMs: Long, val mimeType: String)

    /** Total length in ms from the container, or -1 when unknown or the format is unsupported. */
    fun durationMs(): Long = withTrack { _, format, _ ->
        if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else -1L
    } ?: -1L

    /**
     * Writes the audio from [fromMs] up to [toMs] into [out]. Returns null when the format cannot
     * be cut, or when there is no audio left after [fromMs]. The slice's start is the first
     * frame's real time, so transcript times stay exact.
     */
    fun cut(fromMs: Long, toMs: Long, out: File): Slice? = withTrack { extractor, format, mime ->
        val adts = if (mime == MIME_AAC) adtsConfig(format) ?: return@withTrack null else null
        extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(8192) else 1 shl 20
        val buffer = ByteBuffer.allocate(capacity)
        val chunk = ByteArray(capacity)
        var firstUs = -1L
        var lastUs = -1L
        FileOutputStream(out).use { file ->
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Notes cancelled")
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime
                if (timeUs >= toMs * 1000) break
                if (firstUs < 0) firstUs = timeUs
                lastUs = timeUs
                buffer.get(chunk, 0, size)
                if (adts != null) file.write(adtsHeader(adts, size))
                file.write(chunk, 0, size)
                extractor.advance()
            }
        }
        if (firstUs < 0) {
            out.delete()
            null
        } else {
            Slice(out, firstUs / 1000, lastUs / 1000, if (adts != null) "audio/aac" else "audio/mpeg")
        }
    }

    /** Opens the audio track; setup problems mean "cannot slice" (null), while errors while cutting propagate. */
    private fun <T> withTrack(block: (MediaExtractor, MediaFormat, String) -> T?): T? {
        var extractor: MediaExtractor? = null
        try {
            val opened = try {
                val e = MediaExtractor().also { extractor = it }
                setSource(e)
                val index = (0 until e.trackCount).firstOrNull {
                    e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null
                val format = e.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime != MIME_MP3 && mime != MIME_AAC) {
                    RingLog.i("notes", "slicing not supported for $mime")
                    return null
                }
                e.selectTrack(index)
                Triple(e, format, mime)
            } catch (t: Throwable) {
                if (t is InterruptedException) throw t
                RingLog.w("notes", "slicing unavailable: $t")
                return null
            }
            return block(opened.first, opened.second, opened.third)
        } finally {
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    private fun adtsConfig(format: MediaFormat): Adts? {
        val csd = format.getByteBuffer("csd-0") ?: return null
        val bytes = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
        return parseAudioSpecificConfig(bytes)
    }

    internal data class Adts(val objectType: Int, val frequencyIndex: Int, val channels: Int)

    companion object {
        const val MIME_MP3 = "audio/mpeg"
        const val MIME_AAC = "audio/mp4a-latm"
        const val SLICE_MS = 20 * 60_000L
        private const val MIN_TAIL_MS = 5 * 60_000L

        /** Whether the library's type or the name suggests MP3 or AAC, before any download. */
        fun canTry(mime: String, title: String): Boolean {
            val m = mime.lowercase()
            if (m.contains("mpeg") || m.contains("mp3") || m.contains("mp4") || m.contains("m4a") || m.contains("aac")) return true
            if (m.isNotBlank() && m != "application/octet-stream") return false
            val t = title.lowercase()
            return t.endsWith(".mp3") || t.endsWith(".m4a") || t.endsWith(".aac") || t.endsWith(".mp4")
        }

        /**
         * Part boundaries from [fromMs] to the end of a [totalMs] recording: 20-minute parts, with a
         * short tail (under 5 minutes) joined to the part before it, so no part passes 25 minutes.
         * The last part runs a little past the end so the final frames are kept.
         */
        fun plan(totalMs: Long, fromMs: Long, sliceMs: Long = SLICE_MS): List<Pair<Long, Long>> {
            if (totalMs <= 0 || fromMs >= totalMs - 1_000) return emptyList()
            val out = ArrayList<Pair<Long, Long>>()
            var start = fromMs.coerceAtLeast(0L)
            while (start < totalMs - 1_000) {
                var end = start + sliceMs
                if (end >= totalMs - MIN_TAIL_MS) end = totalMs + 2_000
                out += start to end
                start = end
            }
            return out
        }

        /** AAC-LC and the other ADTS-expressible profiles only (object types 1 to 4). */
        internal fun parseAudioSpecificConfig(bytes: ByteArray): Adts? {
            if (bytes.size < 2) return null
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val objectType = b0 shr 3
            val frequencyIndex = ((b0 and 0x07) shl 1) or (b1 shr 7)
            val channels = (b1 shr 3) and 0x0F
            if (objectType !in 1..4 || frequencyIndex > 12 || channels !in 1..7) return null
            return Adts(objectType, frequencyIndex, channels)
        }

        /** The 7-byte ADTS header for one AAC frame of [payload] bytes (no CRC). */
        internal fun adtsHeader(config: Adts, payload: Int): ByteArray {
            val length = payload + 7
            val profile = config.objectType - 1
            return byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(),
                ((profile shl 6) or (config.frequencyIndex shl 2) or (config.channels shr 2)).toByte(),
                (((config.channels and 3) shl 6) or (length shr 11)).toByte(),
                ((length shr 3) and 0xFF).toByte(),
                (((length and 7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            )
        }
    }
}
