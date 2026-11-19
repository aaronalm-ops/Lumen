package app.lumen.player.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.lumen.player.data.LabelEntity
import app.lumen.player.remote.YtmLink
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 0.10.0: Play next and Add to queue on an item, and Send to YouTube Music on a playlist page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w430dp-h932dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QueueAndMirrorUiTest {
    @get:Rule val compose = createComposeRule()

    private val song = LibraryItem(YtmLink("aaaaaaaaaaa", false).toMediaEntity("A YouTube song", "Artist", 1), emptyList(), 1, 0, 0, "")
    private val local = LibraryItem(song.media.copy(uri = "file:///local.mp3", title = "A phone song", fingerprint = "local"), emptyList(), 1, 0, 0, "")

    @Test fun `queue rules put play next right after the current item and keep the current one with its row`() {
        assertEquals(3, QueueOrder.nextIndex(2, 5))
        assertEquals(0, QueueOrder.nextIndex(-1, 0))
        assertEquals(listOf("b", "c", "a"), QueueOrder.moved(listOf("a", "b", "c"), 0, 2))
        assertEquals(2, QueueOrder.currentAfterMove(current = 0, from = 0, to = 2))
        assertEquals(0, QueueOrder.currentAfterMove(current = 1, from = 0, to = 2))
        assertEquals(2, QueueOrder.currentAfterMove(current = 1, from = 2, to = 0))
    }

    @Test fun `an item's sheet offers play next, add to queue and rename for youtube music`() {
        val calls = mutableListOf<String>()
        compose.setContent {
            LumenTheme(dark = false) {
                LabelSheet(
                    target = LabelSheetTarget.ForItem(song), labels = emptyList(), downloadProgress = null,
                    onToggle = { _, _ -> }, onSave = { true }, onDelete = {}, onToggleOffline = {}, onDismiss = {},
                    queueActive = true,
                    onPlayNext = { calls += "next ${it.media.title}" },
                    onAddToQueue = { calls += "end ${it.media.title}" },
                )
            }
        }
        compose.onNodeWithText("Right after what is playing now").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Play next").performClick()
        compose.onNodeWithText("Add to queue").performClick()
        compose.runOnIdle { assertEquals(listOf("next A YouTube song", "end A YouTube song"), calls) }
    }

    @Test fun `a playlist with youtube music songs offers to send them, and says what stays in Lumen`() {
        val sent = mutableListOf<Long>()
        val label = LabelEntity(id = 7, name = "Drive home", hue = 0, lockKids = false, noInterruptions = false, shuffleOnOpen = false)
        compose.setContent {
            LumenTheme(dark = false) {
                PlaylistsScreen(
                    labels = listOf(label),
                    entries = mapOf(7L to listOf(PlaylistEntry(song, song.uri), PlaylistEntry(local, local.uri))),
                    items = listOf(song, local), player = PlayerState(), openId = 7, picker = false,
                    actions = PlaylistActions(
                        open = {}, newPlaylist = {}, edit = {}, play = { _, _, _ -> }, reorder = { _, _ -> }, remove = { _, _ -> },
                        openPicker = {}, toggleMember = { _, _ -> }, back = {}, sendToYouTube = { sent += it },
                    ),
                )
            }
        }
        compose.onNodeWithText("YouTube Music in the car").assertIsDisplayed()
        compose.onNodeWithText("Send the 1 YouTube Music song to a private playlist on your YouTube account, in this order. In the car, play it from YouTube Music. The other 1 item plays in Lumen.").assertIsDisplayed()
        compose.onNodeWithText("Send to YouTube Music").performClick()
        compose.runOnIdle { assertEquals(listOf(7L), sent) }
    }

    @Test fun `after a send the card says where to find it, and that it changed when it did`() {
        val label = LabelEntity(id = 7, name = "Drive home", hue = 0, lockKids = false, noInterruptions = false, shuffleOnOpen = false)
        val entries = listOf(PlaylistEntry(song, song.uri))
        val sentNow = MainViewModel.MirrorLink("PLx", 1_700_000_000_000, 1, mirrorSignatureOf(mirrorIdsOf(entries)))
        compose.setContent {
            LumenTheme(dark = false) {
                PlaylistsScreen(
                    labels = listOf(label), entries = mapOf(7L to entries), items = listOf(song), player = PlayerState(), openId = 7, picker = false,
                    actions = PlaylistActions(open = {}, newPlaylist = {}, edit = {}, play = { _, _, _ -> }, reorder = { _, _ -> }, remove = { _, _ -> }, openPicker = {}, toggleMember = { _, _ -> }, back = {}),
                    mirrorLinks = mapOf(7L to sentNow),
                )
            }
        }
        compose.onNodeWithText("Update").assertIsDisplayed()
        compose.onNodeWithText("Open in YouTube Music").assertIsDisplayed()
    }
}
