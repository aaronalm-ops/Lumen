package app.lumen.player.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import app.lumen.player.LumenApp
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * 0.9.1: the car and notification Repeat button, and the app's Loop a section commands, against
 * the real service session (the same path Android Auto and the phone notification use).
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumenApp::class)
class RepeatCommandTest {
    private val repeat = SessionCommand(PlaybackService.CMD_REPEAT, Bundle.EMPTY)

    @Test fun `the repeat command cycles off, all, one, off, shows on the button and is remembered`() = withService { app, session, controller ->
        session.player.setMediaItem(MediaItem.fromUri("file:///repeat-test-song.mp3"))
        idle()
        assertTrue("Repeat is granted to controllers", controller.isSessionCommandAvailable(repeat))
        val seen = mutableListOf<Int>()
        repeat(3) {
            assertEquals(SessionResult.RESULT_SUCCESS, await(controller.sendCustomCommand(repeat, Bundle.EMPTY)).resultCode)
            idle()
            seen += session.player.repeatMode
        }
        assertEquals(listOf(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF), seen)

        // A song's buttons carry Repeat, with the icon of the current mode.
        assertEquals(SessionResult.RESULT_SUCCESS, await(controller.sendCustomCommand(repeat, Bundle.EMPTY)).resultCode)
        val button = waitFor { session.mediaButtonPreferences.firstOrNull { it.sessionCommand?.customAction == PlaybackService.CMD_REPEAT }?.takeIf { it.icon == CommandButton.ICON_REPEAT_ALL } }
        assertEquals("Repeat queue", button.displayName.toString())
        // Kept for the next start of the app.
        val kept = waitFor { runBlocking { app.settings.settings.first().repeatMode }.takeIf { it == Player.REPEAT_MODE_ALL } }
        assertEquals(Player.REPEAT_MODE_ALL, kept)
    }

    @Test fun `a loop is set from start to end, seeks back to its start and clears on the next item`() = withService { _, session, controller ->
        session.player.setMediaItem(MediaItem.fromUri("file:///loop-test-sermon.mp3"))
        idle()
        session.player.seekTo(12_000)
        idle()
        send(controller, PlaybackService.CMD_LOOP_START)
        val pending = waitFor { RepeatLoop.fromExtras(controller.sessionExtras) }
        assertEquals(12_000L, pending.startMs)
        assertNull(pending.endMs)

        session.player.seekTo(42_000)
        idle()
        send(controller, PlaybackService.CMD_LOOP_END)
        val loop = waitFor { RepeatLoop.fromExtras(controller.sessionExtras)?.takeIf { it.active } }
        assertEquals(12_000L, loop.startMs)
        assertEquals(42_000L, loop.endMs)
        assertEquals("Setting the end goes back to the start", 12_000L, session.player.currentPosition)

        session.player.setMediaItem(MediaItem.fromUri("file:///another-item.mp3"))
        idle()
        val cleared = waitFor { if (RepeatLoop.fromExtras(controller.sessionExtras) == null) true else null }
        assertTrue(cleared)
    }

    @Test fun `an end before the start is refused`() = withService { _, session, controller ->
        session.player.setMediaItem(MediaItem.fromUri("file:///loop-refused.mp3"))
        idle()
        session.player.seekTo(30_000)
        idle()
        send(controller, PlaybackService.CMD_LOOP_START)
        session.player.seekTo(10_000)
        idle()
        val result = await(controller.sendCustomCommand(SessionCommand(PlaybackService.CMD_LOOP_END, Bundle.EMPTY), Bundle.EMPTY))
        assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE, result.resultCode)
        assertNotNull(RepeatLoop.fromExtras(controller.sessionExtras))
        assertNull(RepeatLoop.fromExtras(controller.sessionExtras)!!.endMs)
    }

    private fun send(controller: MediaController, action: String) {
        val result = await(controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY))
        assertEquals(action, SessionResult.RESULT_SUCCESS, result.resultCode)
        idle()
    }

    private fun withService(block: (LumenApp, MediaLibrarySession, MediaController) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val session = PlaybackService::class.java.getDeclaredField("session").let {
            it.isAccessible = true
            it.get(service.get()) as MediaLibrarySession
        }
        val controller = await(MediaController.Builder(app, session.token).buildAsync())
        try {
            block(app, session, controller)
        } finally {
            controller.release()
            service.destroy()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun <T : Any> waitFor(read: () -> T?): T {
        repeat(300) {
            idle()
            read()?.let { return it }
            Thread.sleep(10)
        }
        return read() ?: throw AssertionError("Condition not reached")
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        repeat(300) {
            idle()
            if (future.isDone) return future.get(1, TimeUnit.SECONDS)
            Thread.sleep(10)
        }
        return future.get(1, TimeUnit.SECONDS)
    }
}
