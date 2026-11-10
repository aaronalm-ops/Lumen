package app.lumen.player.remote

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import app.lumen.player.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class YouTubeHandoffTest {
    @Test fun `song launch uses foreground activity and returns into the existing task`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = host.get()
            RemoteController(activity).open(YtmLink("aaaaaaaaaaa", false), true, activity)
            val outgoing = shadowOf(activity).nextStartedActivity
            assertEquals("https://music.youtube.com/watch?v=aaaaaaaaaaa", outgoing.dataString)
            assertEquals(RemoteController.YTM_PACKAGE, outgoing.`package`)
            assertEquals(0, outgoing.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(RemoteController.RETURN_DELAY_MS))
            val returning = shadowOf(activity).nextStartedActivity
            assertEquals(MainActivity::class.java.name, returning.component?.className)
            assertNotEquals(0, returning.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            assertEquals(0, returning.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        } finally { host.pause().stop().destroy() }
    }

    @Test fun `explicit open cancels a pending return`() {
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = host.get()
            val remote = RemoteController(activity)
            remote.open(YtmLink("aaaaaaaaaaa", false), true, activity)
            shadowOf(activity).nextStartedActivity
            remote.openApp()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally { host.pause().stop().destroy() }
    }
}
