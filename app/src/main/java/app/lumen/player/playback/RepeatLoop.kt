package app.lumen.player.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * 0.9.1: Repeat and Loop a section. Pure rules, so the mode cycle and the loop bounds are unit
 * tested without a player. The service owns the live loop (see PlaybackService) and shares it
 * with the app through the session extras written by [toExtras].
 */
object RepeatLoop {
    /** The shortest section that can loop. */
    const val MIN_LOOP_MS = 1_000L

    /** The loop end stays this far before the end of the recording, so the item never ends inside it. */
    const val END_MARGIN_MS = 300L

    /** Off, then All, then One, then Off again (the car and notification button). */
    fun next(mode: Int): Int = when (mode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    /** A stored value back to a repeat mode; anything unknown is Off. */
    fun parseMode(value: String?): Int = when (value?.toIntOrNull()) {
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    /**
     * A section of one item. [endMs] is null while only the start has been set. The loop belongs to
     * [mediaId] and is cleared when another item becomes current.
     */
    data class Section(val mediaId: String, val startMs: Long, val endMs: Long? = null) {
        val active: Boolean get() = endMs != null
    }

    /** The start of a new section at [positionMs]; null without an item. */
    fun start(mediaId: String, positionMs: Long, durationMs: Long): Section? {
        if (mediaId.isEmpty()) return null
        val latest = if (durationMs > 0) durationMs - END_MARGIN_MS - MIN_LOOP_MS else Long.MAX_VALUE
        if (latest < 0) return null
        return Section(mediaId, positionMs.coerceAtLeast(0L).coerceAtMost(latest))
    }

    /**
     * The finished section, or null when [endMs] cannot close [pending]: another item, an end not
     * at least [MIN_LOOP_MS] after the start, or one too close to the end of the recording. An end
     * past the recording's end is pulled back to [END_MARGIN_MS] before it.
     */
    fun end(pending: Section?, mediaId: String, endMs: Long, durationMs: Long): Section? {
        if (pending == null || pending.active || pending.mediaId != mediaId) return null
        val end = if (durationMs > 0) endMs.coerceAtMost(durationMs - END_MARGIN_MS) else endMs
        if (end < pending.startMs + MIN_LOOP_MS) return null
        return pending.copy(endMs = end)
    }

    /** What is left of [section] when [mediaId] becomes current: kept for the same item, else cleared. */
    fun onItemChanged(section: Section?, mediaId: String?): Section? =
        section?.takeIf { mediaId != null && it.mediaId == mediaId }

    /** Whether a position save for [mediaId] must wait: an active loop never overwrites the saved place. */
    fun holdsSaves(section: Section?, mediaId: String?): Boolean =
        section?.active == true && mediaId != null && section.mediaId == mediaId

    /** The item a loop belongs to: its id, or its uri when it was queued without one. */
    fun keyOf(item: MediaItem?): String = item?.let { it.mediaId.ifEmpty { MediaItems.uriOf(it) } }.orEmpty()

    /** Whether a seek to [positionMs] leaves [section] behind: past its end ends the loop. */
    fun leftBySeek(section: Section?, positionMs: Long): Boolean {
        val end = section?.endMs ?: return false
        return positionMs > end + SEEK_SLACK_MS
    }

    /** A seek this close after the end still counts as inside (seek precision). */
    private const val SEEK_SLACK_MS = 500L

    const val EXTRA_LOOP_ID = "lumen.loop.id"
    const val EXTRA_LOOP_START = "lumen.loop.start"
    const val EXTRA_LOOP_END = "lumen.loop.end"

    fun toExtras(section: Section?): Bundle = Bundle().apply {
        if (section != null) {
            putString(EXTRA_LOOP_ID, section.mediaId)
            putLong(EXTRA_LOOP_START, section.startMs)
            putLong(EXTRA_LOOP_END, section.endMs ?: -1L)
        }
    }

    fun fromExtras(extras: Bundle?): Section? {
        val id = extras?.getString(EXTRA_LOOP_ID) ?: return null
        val end = extras.getLong(EXTRA_LOOP_END, -1L)
        return Section(id, extras.getLong(EXTRA_LOOP_START, 0L), end.takeIf { it >= 0 })
    }
}
