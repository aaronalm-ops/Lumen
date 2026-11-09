package app.lumen.player.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import app.lumen.player.data.Settings
import app.lumen.player.remote.YoutubeApi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w430dp-h932dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `seeking waits until release and dispatches once`() {
        val seeks = mutableListOf<Long>()
        compose.setContent {
            LumenTheme(dark = true) {
                Scrubber(20_000L, 100_000L, true, true, onSeek = { seeks += it })
            }
        }
        val slider = compose.onNodeWithContentDescription("Playback position")
        slider.performTouchInput { down(center); moveTo(centerRight) }
        compose.runOnIdle { assertTrue(seeks.isEmpty()) }
        slider.performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, seeks.size)
            assertTrue(seeks.single() in 90_000L..100_000L)
        }
    }

    @Test fun `TalkBack can seek and unknown durations stay disabled`() {
        val seeks = mutableListOf<Long>()
        compose.setContent { LumenTheme(dark = true) { Scrubber(0L, 100_000L, true, false, onSeek = { seeks += it }) } }
        compose.onNodeWithContentDescription("Playback position").performSemanticsAction(SemanticsActions.SetProgress) { it(75_000f) }
        compose.runOnIdle { assertEquals(listOf(75_000L), seeks) }
    }

    @Test fun `unknown duration cannot seek`() {
        compose.setContent { LumenTheme(dark = true) { Scrubber(0L, 0L, true, true, onSeek = { fail("Unknown duration sought") }) } }
        compose.onNodeWithContentDescription("Playback position").assertIsNotEnabled()
    }

    @Test fun `ribbon moves during playback and settles completely when paused`() {
        val playing = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LumenTheme(dark = true) {
                Scrubber(50_000L, 100_000L, true, true, isPlaying = playing.value, onSeek = {})
            }
        }
        compose.mainClock.advanceTimeBy(600)
        val first = compose.onRoot().captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(600)
        val second = compose.onRoot().captureToImage().asAndroidBitmap()
        assertFalse("Playing ribbon did not move", first.sameAs(second))
        compose.runOnUiThread { playing.value = false }
        compose.mainClock.advanceTimeBy(600)
        val held = compose.onRoot().captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(600)
        assertTrue("Paused ribbon kept animating", held.sameAs(compose.onRoot().captureToImage().asAndroidBitmap()))
    }

    @Test fun `YouTube errors keep retry and link import reachable`() {
        var connectCount = 0
        var linkCount = 0
        val actions = importActions(signIn = { connectCount++ }, showLink = { linkCount++ })
        compose.setContent {
            LumenTheme(dark = false) {
                YtImportScreen(MainViewModel.YtImport(needsSignIn = true, error = "Google denied access"), emptyList(), actions)
            }
        }
        compose.onNodeWithText("Google denied access").assertIsDisplayed()
        compose.onNodeWithText("Connect YouTube").performScrollTo().performClick()
        compose.onNodeWithText("Playlist link", ignoreCase = true).performClick()
        assertEquals(1, connectCount)
        assertEquals(1, linkCount)
        savePreview("youtube-connection")
    }

    @Test fun `account browser opens private playlist`() {
        var opened = ""
        val playlist = YoutubeApi.PlaylistSummary("private-list", "Sunday listening", 28, "private")
        compose.setContent {
            LumenTheme(dark = false) {
                YtImportScreen(MainViewModel.YtImport(accountLoaded = true, playlists = listOf(playlist)), emptyList(), importActions(open = { opened = it.id }))
            }
        }
        compose.onNodeWithText("Sunday listening").performScrollTo().performClick()
        assertEquals("private-list", opened)
        savePreview("youtube-playlists")
    }

    @Test fun `music player renders the moving ribbon with transport and secondary actions`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LumenTheme(dark = true) {
                NowPlayingScreen(
                    player = PlayerState(connected = true, hasItem = true, mediaId = "sample", title = "A light along the way", artist = "Preview recording", positionMs = 152_000, durationMs = 245_000, isPlaying = true),
                    controller = null, item = null, labels = emptyList(), settings = Settings(), speech = false,
                    kidsLocked = false, noInterruptions = false, timeline = emptyList(), audioOnly = false,
                    downloadProgress = null, actions = playerActions(),
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithContentDescription("Playback position").assertExists()
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithText("Sleep").assertExists()
        savePreview("music-ribbon")
    }

    @Test fun `Android Auto setup explains installation and returns to settings`() {
        var back = false
        compose.setContent { LumenTheme(dark = false) { AndroidAutoScreen(back = { back = true }, shareDiagnostics = {}) } }
        compose.onNodeWithText("If Lumen is missing").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(back)
        savePreview("android-auto-setup")
    }

    private fun savePreview(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File("build/test-previews/$name.png").also { it.parentFile?.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun importActions(signIn: () -> Unit = {}, showLink: () -> Unit = {}, open: (YoutubeApi.PlaylistSummary) -> Unit = {}) = YtImportActions(
        close = {}, browse = {}, showLink = showLink, openPlaylist = open, setLink = {}, load = {}, signIn = signIn,
        toggle = {}, selectAll = {}, setTarget = {}, setNewName = {}, add = {}, addAsOneLink = {},
    )

    private fun playerActions() = NowPlayingActions(
        close = {}, playPause = {}, seekBack = {}, seekForward = {}, seekTo = {}, next = {}, previous = {},
        toggleShuffle = {}, cycleRepeat = {}, cycleSpeed = {}, cyclePreset = {}, cycleSleep = {}, addBookmark = {},
        toggleLayout = {}, unlockKids = {}, setAudioOnly = {}, openLabelSheet = {}, toggleOffline = {}, openChapterSheet = {},
        deleteMark = {}, exportNotes = {}, setLevelOutVoices = {}, setTrimSilences = {}, openYouTubeMusic = {},
    )
}
