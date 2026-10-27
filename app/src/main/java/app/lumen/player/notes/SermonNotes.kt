package app.lumen.player.notes

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 0.6.0: the notes template Aaron chose (2026-10-27): Study notes and Journal mixed, with nothing
 * added on top of the recording. Sections in the speaker's order; each with its time, the
 * scriptures cited in it, what was said as paragraphs, the separate points with their times and
 * the lines said word for word. Announcements last, when there were any. No questions, prayers,
 * applications or summaries that the speaker did not say.
 */
data class Timed(val text: String, val ms: Long)

data class NotesSection(
    val heading: String,
    val ms: Long,
    val scriptures: List<Timed>,
    val prose: List<String>,
    val points: List<Timed>,
    val quotes: List<Timed>,
)

data class SermonNotes(
    val title: String,
    val speaker: String,
    val readings: List<Timed>,
    val sections: List<NotesSection>,
    val announcements: List<Timed>,
) {
    companion object {
        fun parse(json: String): SermonNotes? = try {
            val o = JSONObject(json)
            SermonNotes(
                title = o.optString("title").trim(),
                speaker = o.optString("speaker").trim(),
                readings = timedList(o.optJSONArray("readings"), "ref"),
                sections = (0 until (o.optJSONArray("sections")?.length() ?: 0)).mapNotNull { i ->
                    val s = o.getJSONArray("sections").optJSONObject(i) ?: return@mapNotNull null
                    NotesSection(
                        heading = s.optString("heading").trim(),
                        ms = parseTime(s.optString("t")),
                        scriptures = timedList(s.optJSONArray("scriptures"), "ref"),
                        prose = strings(s.optJSONArray("prose")),
                        points = timedList(s.optJSONArray("points"), "text"),
                        quotes = timedList(s.optJSONArray("quotes"), "text"),
                    )
                },
                announcements = timedList(o.optJSONArray("announcements"), "text"),
            )
        } catch (_: Throwable) {
            null
        }

        private fun strings(a: JSONArray?): List<String> =
            (0 until (a?.length() ?: 0)).mapNotNull { a!!.optString(it).trim().ifBlank { null } }

        private fun timedList(a: JSONArray?, key: String): List<Timed> =
            (0 until (a?.length() ?: 0)).mapNotNull { i ->
                val o = a!!.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString(key).trim()
                if (text.isBlank()) null else Timed(text, parseTime(o.optString("t")))
            }
    }
}

object NotesPrompts {

    const val TRANSCRIPT_SYSTEM =
        "You transcribe recordings of church sermons and services exactly. You never summarise, " +
            "correct, explain or add anything. Times are measured from the very start of the recording."

    fun transcriptRequest(afterTime: String?): String = buildString {
        append("Transcribe this recording word for word, in the language spoken. ")
        append("Return a JSON array of segments in the order they were spoken. Each segment has ")
        append("\"t\", the time the segment starts as MM:SS or H:MM:SS from the start of the recording, ")
        append("and \"s\", what was said: one or two sentences, about 5 to 20 seconds of speech. ")
        append("Include every spoken word: talk, readings, prayers and announcements. ")
        append("Where people sing, write one segment \"[singing]\" instead of the words. ")
        append("Where a word cannot be made out, write [unclear]. ")
        append("Do not leave gaps and do not repeat segments.")
        if (afterTime != null) {
            append(" The transcript up to $afterTime is already done: return only the segments that start after $afterTime, to the end of the recording.")
        }
    }

    val transcriptSchema: JSONObject
        get() = JSONObject()
            .put("type", "ARRAY")
            .put(
                "items",
                JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("t", str()).put("s", str()))
                    .put("required", JSONArray().put("t").put("s")),
            )

    const val NOTES_SYSTEM =
        "You write notes of a sermon from its timed transcript, for someone who wants to keep every " +
            "important detail of what was said. Rules, with no exceptions:\n" +
            "1. Only what was said in the transcript. Never add questions, applications, prayers, " +
            "summaries, conclusions, praise, commentary or background that the speaker did not say.\n" +
            "2. Keep the speaker's own order and structure: their points, sub-points and lists.\n" +
            "3. Keep every point, story, illustration, example, scripture reference, explanation of a " +
            "word, instruction and announcement. Leave out only filler, repetition and the sung parts.\n" +
            "4. Use the speaker's own words where you can; paraphrase only to shorten.\n" +
            "5. Every time you give must be copied from the transcript line where that thing is said.\n" +
            "6. Quotes must be copied word for word from the transcript.\n" +
            "7. Where the transcript is unclear, leave it out rather than guess.\n" +
            "8. Write in English with British spelling. Scripture references as Book chapter:verse."

    fun notesRequest(transcript: String): String =
        "Here is the timed transcript, one line per segment as [time] text.\n\n" + transcript + "\n\n" +
            "Write the notes as JSON with these fields.\n" +
            "title: the sermon's title if the speaker or host states it, otherwise empty.\n" +
            "speaker: the speaker's name if it is said, otherwise empty.\n" +
            "readings: passages read aloud, each with ref and t.\n" +
            "sections: the parts of the service and sermon in order (for example the opening, a reading, " +
            "each main point, a story, the response, the close). For each: heading (the speaker's own " +
            "heading where they give one, otherwise a plain label of what that part is), t (when it starts), " +
            "scriptures (references cited in that part, each with ref and t), prose (paragraphs giving what " +
            "was said in that part in full detail, in order), points (separate statements, sub-points or list " +
            "items, each with text and t), quotes (striking lines copied word for word, each with text and t).\n" +
            "announcements: each notice given, with text and t; empty if none."

    val notesSchema: JSONObject
        get() {
            val timedRef = obj("ref" to str(), "t" to str())
            val timedText = obj("text" to str(), "t" to str())
            val section = obj(
                "heading" to str(),
                "t" to str(),
                "scriptures" to arr(timedRef),
                "prose" to arr(str()),
                "points" to arr(timedText),
                "quotes" to arr(timedText),
            )
            return obj(
                "title" to str(),
                "speaker" to str(),
                "readings" to arr(timedRef),
                "sections" to arr(section),
                "announcements" to arr(timedText),
            )
        }

    private fun str() = JSONObject().put("type", "STRING")
    private fun arr(items: JSONObject) = JSONObject().put("type", "ARRAY").put("items", items)
    private fun obj(vararg fields: Pair<String, JSONObject>): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        fields.forEach { (k, v) ->
            props.put(k, v)
            required.put(k)
        }
        return JSONObject().put("type", "OBJECT").put("properties", props).put("required", required)
    }
}

/** "MM:SS", "H:MM:SS" or "M:SS.s" to milliseconds; -1 when it cannot be read. */
fun parseTime(text: String): Long {
    val clean = text.trim().removePrefix("[").removeSuffix("]")
    if (clean.isBlank()) return -1L
    val parts = clean.split(":")
    if (parts.size !in 2..3) return -1L
    return try {
        val seconds = parts.last().toDouble()
        val minutes = parts[parts.size - 2].toLong()
        val hours = if (parts.size == 3) parts[0].toLong() else 0L
        ((hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()).coerceAtLeast(0L)
    } catch (_: NumberFormatException) {
        -1L
    }
}

/** Milliseconds as M:SS or H:MM:SS, as the transcript is sent to the notes call. */
fun stamp(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.UK, "%d:%02d:%02d", h, m, s) else String.format(Locale.UK, "%02d:%02d", m, s)
}

/**
 * Reads the segments from a transcript reply. A reply cut off at the output limit is read up to its
 * last complete segment, so the next call can carry on from there.
 */
fun parseSegments(text: String): List<Pair<Long, String>> {
    val trimmed = text.trim()
    val array = try {
        JSONArray(trimmed)
    } catch (_: Throwable) {
        val cut = trimmed.lastIndexOf("},")
        val repaired = if (cut > 0) trimmed.substring(0, cut + 1) + "]" else return emptyList()
        try {
            JSONArray(repaired)
        } catch (_: Throwable) {
            return emptyList()
        }
    }
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val ms = parseTime(o.optString("t"))
        val s = o.optString("s").trim()
        if (ms < 0 || s.isBlank()) null else ms to s
    }
}
