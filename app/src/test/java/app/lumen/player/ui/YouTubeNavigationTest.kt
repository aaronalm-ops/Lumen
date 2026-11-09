package app.lumen.player.ui

import androidx.compose.ui.test.*
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.lumen.player.playback.PlaybackService
import androidx.media3.common.util.UnstableApi
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
