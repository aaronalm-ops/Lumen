package app.lumen.player.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import app.lumen.player.data.Settings
import app.lumen.player.playback.RepeatLoop
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 0.9.1: Repeat opens a list in plain words, and the line under the transport says what repeats. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w430dp-h932dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepeatUiTest {
    @get:Rule val compose = createComposeRule()

    private fun screen(player: PlayerState, calls: MutableList<String>) {
        compose.setContent {
            LumenTheme(dark = true) {
                NowPlayingScreen(
                    player = player,
                    controller = null, item = null, labels = emptyList(), settings = Settings(), speech = false,
                    kidsLocked = false, noInterruptions = false, timeline = emptyList(), audioOnly = false,
                    downloadProgress = null,
                    actions = NowPlayingActions(
                        close = {}, playPause = {}, seekBack = {}, seekForward = {}, seekTo = {}, next = {}, previous = {},
                        toggleShuffle = {}, cycleRepeat = { calls += "cycle" }, cycleSpeed = {}, cyclePreset = {}, cycleSleep = {}, addBookmark = {},
                        toggleLayout = {}, unlockKids = {}, setAudioOnly = {}, openLabelSheet = {}, toggleOffline = {}, openChapterSheet = {},
                        deleteMark = {}, exportNotes = {}, setLevelOutVoices = {}, setTrimSilences = {}, openYouTubeMusic = {},
                        setRepeatMode = { calls += "mode $it" },
                        loopStart = { calls += "start" },
                        loopEnd = { calls += "end" },
                        loopClear = { calls += "clear" },
                    ),
                )
            }
        }
    }

    private val song = PlayerState(connected = true, hasItem = true, mediaId = "song", title = "A song", positionMs = 12_000, durationMs = 200_000)

    @Test fun `repeat opens the list and each choice says what it does`() {
        val calls = mutableListOf<String>()
        screen(song, calls)
        compose.onNodeWithContentDescription("Repeat: Off").performClick()
        listOf("Off", "Repeat queue", "Repeat this one", "Loop a section").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Set start at 00:12").assertIsDisplayed()
        compose.onNodeWithText("Repeat queue").performClick()
        compose.runOnIdle { assertEquals(listOf("mode ${Player.REPEAT_MODE_ALL}"), calls) }
    }

    @Test fun `a started loop offers Set end and Clear loop, and the line shows it`() {
        val calls = mutableListOf<String>()
        screen(song.copy(positionMs = 40_000, loop = RepeatLoop.Section("song", 12_000)), calls)
        compose.onNodeWithText("Loop starts at 00:12. Open Repeat to set the end").assertIsDisplayed()
        compose.onNodeWithContentDescription("Repeat: Loop start set").performClick()
        compose.onNodeWithText("Clear loop").assertIsDisplayed()
        compose.onNodeWithText("Set end").performClick()
        compose.runOnIdle { assertEquals(listOf("end"), calls) }
    }

    @Test fun `a running loop and repeat modes show on the line under the transport`() {
        screen(song.copy(loop = RepeatLoop.Section("song", 12_000, 42_000)), mutableListOf())
        compose.onNodeWithText("Looping 00:12 to 00:42").assertIsDisplayed()
        compose.onNodeWithContentDescription("Playback position, looping 00:12 to 00:42").assertIsDisplayed()
    }

    @Test fun `repeat queue shows on the line`() {
        screen(song.copy(repeat = Player.REPEAT_MODE_ALL), mutableListOf())
        compose.onNodeWithText("Repeating the queue").assertIsDisplayed()
        compose.onNodeWithContentDescription("Repeat: Repeat queue").assertIsDisplayed()
    }
}
