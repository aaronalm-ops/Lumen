package app.lumen.player.ui

import androidx.compose.ui.test.*
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.lumen.player.playback.PlaybackService
import androidx.media3.common.util.UnstableApi
import androidx.lifecycle.ViewModelProvider
import app.lumen.player.remote.YtmLink
import app.lumen.player.diag.RingLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import app.lumen.player.LumenApp
import app.lumen.player.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opens the real activity and root navigation; no Google sheet is launched by this test. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumenApp::class, qualifiers = "w430dp-h932dp-xhdpi")
class YouTubeNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun `remote queue keeps its order selection and originating playlist through handoff and skips`() {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        app.coldStart = false
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val binder = service.get().onBind(Intent("androidx.media3.session.MediaLibraryService"))
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java), binder)
        val rows = listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd").mapIndexed { index, id ->
            val media = YtmLink(id, false).toMediaEntity("Song ${index + 1}", "Fixture", 1L)
            LibraryItem(if (index == 3) media.copy(uri = "file:///synthetic.wav", mime = "audio/wav") else media,
                emptyList(), 1, 0, 0, "")
        }
        runBlocking { app.db.mediaDao().upsertMediaAll(rows.map { it.media }) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: MainViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[MainViewModel::class.java] }
                awaitState { vm.controller.value != null }
                scenario.onActivity { vm.play(rows[1], rows, originPlaylistId = 42L) }
                try { awaitState { vm.player.value.mediaId == rows[1].uri } }
                catch (error: Throwable) { throw AssertionError("Queue state: ${vm.player.value}\n${RingLog.snapshot().takeLast(30).joinToString("\n")}", error) }
                scenario.onActivity {
                    assertEquals(listOf("Song 1", "Song 2", "Song 3", "Song 4"),
                        (0 until vm.controller.value!!.mediaItemCount).map { vm.controller.value!!.getMediaItemAt(it).mediaMetadata.title.toString() })
                    vm.disconnect()
                    assertNotNull("YouTube handoff lost the controller", vm.controller.value)
                    vm.connect(it)
                    vm.previous()
                }
                awaitState { vm.player.value.title == "Song 1" }
                scenario.onActivity { assertFalse(vm.player.value.hasPrevious); vm.previous(); vm.next() }
                awaitState { vm.player.value.title == "Song 2" }
                scenario.onActivity { vm.next() }
                awaitState { vm.player.value.title == "Song 3" }
                scenario.onActivity {
                    vm.dismissNowPlaying()
                    assertFalse(vm.nowPlayingOpen.value)
                    assertEquals(Screen.Playlists, vm.screen.value)
                    assertEquals(42L, vm.openPlaylist.value)
                    assertEquals(rows[2].uri, vm.player.value.mediaId)
                    vm.next()
                }
                awaitState { vm.player.value.mediaId == rows[3].uri }
                scenario.onActivity { assertFalse(vm.player.value.hasNext); assertEquals("Song 4", vm.player.value.title) }
            }
        } finally { service.destroy() }
    }

    // Media3 posts binder/player events to the Android looper, independently of Compose's clock.
    private fun awaitState(condition: () -> Boolean) {
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    @Test fun `YouTube browser opens from Playlists Library and Settings and returns to each`() {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        app.coldStart = false // Navigation test begins after the deliberate cold-start splash.
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val binder = service.get().onBind(Intent("androidx.media3.session.MediaLibraryService"))
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app, PlaybackService::class.java), binder)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithContentDescription("Playlists").performClick()
                compose.onNodeWithText("Browse YouTube playlists").performScrollTo().performClick()
                compose.onNodeWithText("My playlists", ignoreCase = true).assertIsDisplayed()
                compose.onNodeWithText("Playlist link", ignoreCase = true).performClick()
                compose.onNodeWithText("Show the songs").assertIsDisplayed()
                compose.onNodeWithContentDescription("Close").performClick()
                compose.onNodeWithText("Browse YouTube playlists").assertExists()

                compose.onNodeWithContentDescription("Library").performClick()
                compose.onNodeWithText("YOUTUBE MUSIC").performClick()
                compose.onNodeWithText("Browse YouTube playlists").performScrollTo().performClick()
                compose.onNodeWithText("My playlists", ignoreCase = true).assertIsDisplayed()
                compose.onNodeWithContentDescription("Close").performClick()
                compose.onNodeWithText("YouTube Music, in Lumen").assertExists()

                compose.onNodeWithContentDescription("Home").performClick()
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText("Browse YouTube playlists").performScrollTo().performClick()
                compose.onNodeWithText("My playlists", ignoreCase = true).assertIsDisplayed()
                compose.onNodeWithContentDescription("Close").performClick()
                compose.onNodeWithText("Set up Android Auto").performScrollTo().performClick()
                compose.onNodeWithText("If Lumen is missing").assertExists()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText("Set up Android Auto").performScrollTo().assertExists()
            }
        } finally { service.destroy() }
    }
}
