package app.lumen.player.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.lumen.player.LumenApp
import app.lumen.player.data.LabelEntity
import app.lumen.player.remote.YoutubeApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumenApp::class)
class YoutubeImportJourneyTest {
    @Test fun `global entry points do not reuse a playlist left open elsewhere`() {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<LumenApp>())
        vm.openPlaylist.value = 42L
        vm.screen.value = Screen.Settings
        vm.openYtImport()
        assertNull(vm.ytImport.value!!.targetLabelId)
        vm.closeYtImport()
        vm.screen.value = Screen.Playlists
        vm.openYtImport()
        assertEquals(42L, vm.ytImport.value!!.targetLabelId)
        vm.closeYtImport()
    }

    @Test fun `selected songs reach their destination in order and reimport adds no duplicates`() {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        val target = runBlocking { app.labels.save(LabelEntity(name = "Destination", hue = 0, lockKids = false, noInterruptions = false, shuffleOnOpen = false))!!.id }
        val vm = MainViewModel(app)
        repeat(2) {
            vm.ytImport.value = selection().copy(targetLabelId = target)
            vm.addYtSelected()
            await { vm.ytImport.value == null }
            assertEquals(target, vm.openPlaylist.value)
        }
        val uris = runBlocking { app.labels.mediaUrisFor(target) }
        assertEquals(listOf("ytm://bbbbbbbbbbb", "ytm://aaaaaaaaaaa"), uris)
        assertEquals("B", runBlocking { app.media.byUri(uris.first()) }!!.title)
    }

    @Test fun `repeated new playlist names are unique even when UI label flow is not subscribed`() {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        val vm = MainViewModel(app)
        repeat(2) {
            vm.ytImport.value = selection().copy(newName = "Imported")
            vm.addYtSelected()
            await { vm.ytImport.value == null }
        }
        assertNotNull(runBlocking { app.labels.byName("Imported") })
        assertNotNull(runBlocking { app.labels.byName("Imported (2)") })
    }

    @Test fun `removed destination reports an error without storing a partial import`() {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        val vm = MainViewModel(app)
        val before = runBlocking { app.media.count() }
        vm.ytImport.value = selection().copy(targetLabelId = 999_999)
        vm.addYtSelected()
        await { vm.ytImport.value?.adding == false }
        assertTrue(vm.ytImport.value!!.error!!.contains("removed"))
        assertEquals(before, runBlocking { app.media.count() })
    }

    private fun selection() = MainViewModel.YtImport(
        browseAccount = false,
        tracks = listOf(YoutubeApi.Track("bbbbbbbbbbb", "B", "Artist", 200_000), YoutubeApi.Track("aaaaaaaaaaa", "A", "Artist", 240_000), YoutubeApi.Track("ccccccccccc", "Unselected", "Artist", 180_000)),
        selected = setOf("aaaaaaaaaaa", "bbbbbbbbbbb"),
    )

    private fun await(done: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("Import did not finish")
    }
}
