package app.lumen.player.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.test.core.app.ApplicationProvider
import app.lumen.player.LumenApp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumenApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemMediaArtworkTest {
    @Test fun `system session uses constant branded art across cover URIs embedded images and track changes`() {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val instance = service.get()
        val session = PlaybackService::class.java.getDeclaredField("session").let { it.isAccessible = true; it.get(instance) as MediaLibrarySession }
        val exo = PlaybackService::class.java.getDeclaredField("exo").let { it.isAccessible = true; it.get(instance) as ExoPlayer }
        val callbacks = mutableListOf<MediaMetadata>()
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { callbacks += mediaMetadata }
        }
        session.player.addListener(listener)
        try {
            val source = MediaMetadata.Builder().setTitle("First fixture").setArtist("Fixture artist")
                .setArtworkUri(Uri.parse("https://example.invalid/cover.png")).build()
            exo.setMediaItem(MediaItem.Builder().setMediaId("file:///fixture.mp3").setUri("file:///fixture.mp3").setMediaMetadata(source).build())
            shadowOf(Looper.getMainLooper()).idle()
            val first = session.player.mediaMetadata
            assertEquals("First fixture", first.title)
            assertEquals("Fixture artist", first.artist)
            assertNull(first.artworkUri)
            assertNotNull(first.artworkData)
            val cover = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.MAGENTA) }
            val embedded = ByteArrayOutputStream().use { cover.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            exo.replaceMediaItem(0, exo.getMediaItemAt(0).buildUpon().setMediaMetadata(source.buildUpon().setTitle("Second fixture")
                .setArtworkData(embedded, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build())
            shadowOf(Looper.getMainLooper()).idle()
            val second = session.player.mediaMetadata
            assertEquals("Second fixture", second.title)
            assertArrayEquals(first.artworkData, second.artworkData)
            assertFalse(embedded.contentEquals(second.artworkData!!))
            assertTrue(callbacks.isNotEmpty())
            callbacks.forEach { assertNull(it.artworkUri); assertArrayEquals(first.artworkData, it.artworkData) }
            // Robolectric's platform MediaController metadata is a stub. Exercise the real
            // Media3 binder/controller contract instead; OEM system rendering is a phone check.
            val future = androidx.media3.session.MediaController.Builder(app, session.token).buildAsync()
            repeat(300) {
                shadowOf(Looper.getMainLooper()).idle()
                if (!future.isDone) Thread.sleep(10)
            }
            val controller = future.get(1, java.util.concurrent.TimeUnit.SECONDS)
            val published = try {
                assertEquals("Second fixture", controller.mediaMetadata.title)
                assertNull(controller.mediaMetadata.artworkUri)
                assertArrayEquals(first.artworkData, controller.mediaMetadata.artworkData)
                requireNotNull(controller.mediaMetadata.artworkData)
            } finally { controller.release() }
            val expected = BitmapFactory.decodeByteArray(published, 0, published.size)
            assertEquals(Color.BLACK, expected.getPixel(0, 0))
            val preview = File("build/test-previews/system-media-art.png").also { it.parentFile?.mkdirs() }
            preview.outputStream().use { expected.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { session.player.removeListener(listener); service.destroy() }
    }
}
