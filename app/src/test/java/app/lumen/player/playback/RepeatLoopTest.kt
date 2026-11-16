package app.lumen.player.playback

import android.app.Application
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.9.1: the Repeat list's rules and Loop a section's bounds, without a player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RepeatLoopTest {
    @Test fun `the repeat button cycles off, all, one and back to off`() {
        assertEquals(Player.REPEAT_MODE_ALL, RepeatLoop.next(Player.REPEAT_MODE_OFF))
        assertEquals(Player.REPEAT_MODE_ONE, RepeatLoop.next(Player.REPEAT_MODE_ALL))
        assertEquals(Player.REPEAT_MODE_OFF, RepeatLoop.next(Player.REPEAT_MODE_ONE))
    }

    @Test fun `a stored repeat mode reads back, and anything unknown is off`() {
        assertEquals(Player.REPEAT_MODE_ALL, RepeatLoop.parseMode("2"))
        assertEquals(Player.REPEAT_MODE_ONE, RepeatLoop.parseMode("1"))
        assertEquals(Player.REPEAT_MODE_OFF, RepeatLoop.parseMode("0"))
        assertEquals(Player.REPEAT_MODE_OFF, RepeatLoop.parseMode("7"))
        assertEquals(Player.REPEAT_MODE_OFF, RepeatLoop.parseMode(null))
    }

    @Test fun `the end must come after the start`() {
        val start = RepeatLoop.start("song", 60_000, 200_000)!!
        assertFalse(start.active)
        assertNull("End before the start", RepeatLoop.end(start, "song", 30_000, 200_000))
        assertNull("End at the start", RepeatLoop.end(start, "song", 60_000, 200_000))
        assertNull("Shorter than the shortest loop", RepeatLoop.end(start, "song", 60_000 + RepeatLoop.MIN_LOOP_MS - 1, 200_000))
        val done = RepeatLoop.end(start, "song", 90_000, 200_000)!!
        assertEquals(60_000L, done.startMs)
        assertEquals(90_000L, done.endMs)
        assertTrue(done.active)
        assertNull("A finished loop cannot be ended again", RepeatLoop.end(done, "song", 120_000, 200_000))
    }

    @Test fun `the end stays before the end of the recording`() {
        val start = RepeatLoop.start("song", 100_000, 200_000)!!
        val done = RepeatLoop.end(start, "song", 200_000, 200_000)!!
        assertEquals(200_000L - RepeatLoop.END_MARGIN_MS, done.endMs)
        assertTrue(done.endMs!! < 200_000L)
        // A start in the last moment is moved back so a loop can still close before the end.
        val late = RepeatLoop.start("song", 199_900, 200_000)!!
        assertTrue(late.startMs + RepeatLoop.MIN_LOOP_MS <= 200_000L - RepeatLoop.END_MARGIN_MS)
        assertTrue(RepeatLoop.end(late, "song", 200_000, 200_000) != null)
        assertNull("No item, no loop", RepeatLoop.start("", 0, 200_000))
    }

    @Test fun `the loop clears when the item changes`() {
        val loop = RepeatLoop.end(RepeatLoop.start("sermon", 10_000, 3_600_000), "sermon", 70_000, 3_600_000)
        assertEquals(loop, RepeatLoop.onItemChanged(loop, "sermon"))
        assertNull(RepeatLoop.onItemChanged(loop, "next song"))
        assertNull(RepeatLoop.onItemChanged(loop, null))
        assertNull("The end belongs to the item it started on", RepeatLoop.end(RepeatLoop.start("sermon", 10_000, 3_600_000), "other", 70_000, 3_600_000))
    }

    @Test fun `a running loop holds position saves for its item only`() {
        val pending = RepeatLoop.start("sermon", 10_000, 3_600_000)
        assertFalse("Saving continues until the end is set", RepeatLoop.holdsSaves(pending, "sermon"))
        val loop = RepeatLoop.end(pending, "sermon", 70_000, 3_600_000)
        assertTrue(RepeatLoop.holdsSaves(loop, "sermon"))
        assertFalse(RepeatLoop.holdsSaves(loop, "other"))
        assertFalse(RepeatLoop.holdsSaves(null, "sermon"))
    }

    @Test fun `seeking past the end leaves the loop, seeking inside keeps it`() {
        val loop = RepeatLoop.end(RepeatLoop.start("song", 10_000, 200_000), "song", 40_000, 200_000)
        assertFalse(RepeatLoop.leftBySeek(loop, 10_000))
        assertFalse(RepeatLoop.leftBySeek(loop, 25_000))
        assertTrue(RepeatLoop.leftBySeek(loop, 60_000))
        assertFalse("Only the start set", RepeatLoop.leftBySeek(RepeatLoop.start("song", 10_000, 200_000), 60_000))
    }

    @Test fun `the loop travels to the app in the session extras`() {
        val loop = RepeatLoop.end(RepeatLoop.start("song", 10_000, 200_000), "song", 40_000, 200_000)
        assertEquals(loop, RepeatLoop.fromExtras(RepeatLoop.toExtras(loop)))
        val pending = RepeatLoop.start("song", 10_000, 200_000)
        assertEquals(pending, RepeatLoop.fromExtras(RepeatLoop.toExtras(pending)))
        assertNull(RepeatLoop.fromExtras(RepeatLoop.toExtras(null)))
    }
}
