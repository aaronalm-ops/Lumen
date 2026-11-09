package app.lumen.player.playback

import android.os.Looper
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.test.core.app.ApplicationProvider
import app.lumen.player.LumenApp
import app.lumen.player.data.LabelEntity
import app.lumen.player.remote.YtmLink
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumenApp::class)
class PlaybackBrowserTest {
    @Test fun `car browser starts without an activity and pages a playlist in user order`() {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        val discovered = app.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(app.packageName), 0)
        assertTrue(discovered.any { it.serviceInfo.name == PlaybackService::class.java.name && it.serviceInfo.exported && it.serviceInfo.enabled })
        val labelId = runBlocking {
            val base = YtmLink("aaaaaaaaaaa", false).toMediaEntity("First", "Artist", 10L)
            val first = base.copy(uri = "file:///first.mp3", folderUri = "test", durationMs = 200_000, fingerprint = "first", dateAdded = 10)
            val second = first.copy(uri = "file:///second.mp3", title = "Second", fingerprint = "second", dateAdded = 20)
            val remote = base.copy(uri = "ytm://aaaaaaaaaaa", title = "YouTube")
            app.db.mediaDao().upsertMediaAll(listOf(first, second, remote))
            val label = app.labels.save(LabelEntity(name = "Car order test", hue = 0, lockKids = false, noInterruptions = false, shuffleOnOpen = false))!!
            app.labels.attach(first.uri, label.id)
            app.labels.attach(remote.uri, label.id)
            app.labels.attach(second.uri, label.id)
            label.id
        }
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val instance = service.get()
        val session = PlaybackService::class.java.getDeclaredField("session").let {
            it.isAccessible = true
            it.get(instance) as MediaLibrarySession
        }
        val browser = await(MediaBrowser.Builder(app, session.token).buildAsync())
        try {
            val root = await(browser.getLibraryRoot(null))
            assertEquals("Lumen", root.value!!.mediaMetadata.title)
            val roots = await(browser.getChildren(PlaybackService.ROOT_ID, 0, 10, null)).value!!
            assertEquals(listOf("Continue", "Playlists", "Sermons", "Recent"), roots.map { it.mediaMetadata.title.toString() })
            val parent = PlaybackService.LABEL_PREFIX + labelId
            assertEquals("First", await(browser.getChildren(parent, 0, 1, null)).value!!.single().mediaMetadata.title)
            assertEquals("Second", await(browser.getChildren(parent, 1, 1, null)).value!!.single().mediaMetadata.title)
            assertTrue(await(browser.getChildren(parent, 2, 1, null)).value!!.isEmpty())
            assertNull(await(browser.getItem("missing")).value)
        } finally {
            browser.release()
            service.destroy()
        }
    }

    private fun <T> await(future: ListenableFuture<T>): T {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (future.isDone) return future.get(1, TimeUnit.SECONDS)
            Thread.sleep(10)
        }
        return future.get(1, TimeUnit.SECONDS)
    }
}
