package app.lumen.player.playback

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.test.core.app.ApplicationProvider
import app.lumen.player.LumenApp
import app.lumen.player.car.CarLibrary
import app.lumen.player.data.FolderEntity
import app.lumen.player.data.NaturalOrder
import app.lumen.player.remote.YtmLink
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** 0.10.0: Android Auto's Library tab: Recent, the phone's Folders in natural order, and Drive. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumenApp::class)
class CarFoldersTest {
    @Test fun `natural order puts part 2 before part 10 and ignores case`() {
        val sorted = listOf("Part 10", "part 2", "Part 1", "Intro", "Part 02b").sortedWith(NaturalOrder)
        assertEquals(listOf("Intro", "Part 1", "part 2", "Part 02b", "Part 10"), sorted)
    }

    @Test fun `library shows folders and drive, folders play all in natural order`() {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        runBlocking {
            val base = YtmLink("aaaaaaaaaaa", false).toMediaEntity("x", "Artist", 10L)
            val dao = app.db.mediaDao()
            dao.upsertFolder(FolderEntity("tree://talks", "Talks", 1L))
            dao.upsertFolder(FolderEntity("drive:sunday", "Sunday", 1L))
            val ten = base.copy(uri = "file:///talk10.mp3", folderUri = "tree://talks", title = "Talk 10", durationMs = 60_000, fingerprint = "t10", dateAdded = 1)
            val two = ten.copy(uri = "file:///talk2.mp3", title = "Talk 2", fingerprint = "t2")
            val drive = ten.copy(uri = "drive://abc", folderUri = "drive:sunday", title = "Sunday sermon", fingerprint = "d1", dateAdded = 5)
            dao.upsertMediaAll(listOf(ten, two, drive))
        }
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val session = PlaybackService::class.java.getDeclaredField("session").let {
            it.isAccessible = true
            it.get(service.get()) as MediaLibrarySession
        }
        val browser = await(MediaBrowser.Builder(app, session.token).buildAsync())
        try {
            val library = await(browser.getChildren(CarLibrary.LIBRARY_ID, 0, 10, null)).value!!.map { it.mediaMetadata.title.toString() }
            assertEquals(listOf("Recent", "Folders", "Drive"), library)
            val folders = await(browser.getChildren(CarLibrary.FOLDERS_ID, 0, 10, null)).value!!
            assertEquals(listOf("Talks"), folders.map { it.mediaMetadata.title.toString() })
            val talks = await(browser.getChildren(folders.single().mediaId, 0, 10, null)).value!!.map { it.mediaMetadata.title.toString() }
            assertEquals(listOf("Play all", "Shuffle", "Talk 2", "Talk 10"), talks)
            val driveRows = await(browser.getChildren(CarLibrary.DRIVE_ID, 0, 10, null)).value!!.map { it.mediaMetadata.title.toString() }
            assertEquals(listOf("Sunday sermon"), driveRows)
        } finally {
            browser.release()
            service.destroy()
        }
    }

    @Test fun `play all on a folder expands to its items in natural order`() {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        runBlocking {
            val base = YtmLink("aaaaaaaaaaa", false).toMediaEntity("x", "Artist", 10L)
            app.db.mediaDao().upsertFolder(FolderEntity("tree://songs", "Songs", 1L))
            app.db.mediaDao().upsertMediaAll(listOf(
                base.copy(uri = "file:///b10.mp3", folderUri = "tree://songs", title = "Track 10", durationMs = 1_000, fingerprint = "b10"),
                base.copy(uri = "file:///b9.mp3", folderUri = "tree://songs", title = "Track 9", durationMs = 1_000, fingerprint = "b9"),
            ))
        }
        val car = CarLibrary(app, app.media, app.labels, app.store, app.notesRepo) { m ->
            androidx.media3.common.MediaItem.Builder().setMediaId(m.uri).build()
        }
        val expanded = runBlocking { car.expand(CarLibrary.FOLDER_ALL_PREFIX + "tree://songs") }!!
        assertEquals(listOf("file:///b9.mp3", "file:///b10.mp3"), expanded.items.map { it.mediaId })
        assertTrue(CarLibrary.isCarOnly(CarLibrary.FOLDER_SHUFFLE_PREFIX + "tree://songs"))
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
