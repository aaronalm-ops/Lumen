package app.lumen.player.playback

import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import app.lumen.player.LumenApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 0.8.7 regression: 0.8.6 wrapped session listeners with Kotlin `by` delegation, which skips
 * Java default methods, so play/pause/position never reached the app, notification or car.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumenApp::class)
class PlayerListenerForwardingTest {

    private fun signature(m: Method) = m.name + m.parameterTypes.joinToString(",", "(", ")") { it.name }

    private val unsafe: Any = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        .let { it.isAccessible = true; it.get(null)!! }

    /** A non-null stand-in for each parameter: the forwarder must not need real values. */
    private fun dummy(type: Class<*>): Any = when {
        type == java.lang.Boolean.TYPE -> true
        type == java.lang.Integer.TYPE -> 7
        type == java.lang.Long.TYPE -> 7L
        type == java.lang.Float.TYPE -> 0.5f
        type == MediaMetadata::class.java -> MediaMetadata.Builder().setTitle("source").build()
        type == List::class.java -> emptyList<Any>()
        type.isInterface -> Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> null }
        else -> unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)!!
    }

    @Test fun `every Player Listener callback is forwarded and only metadata is branded`() {
        val received = mutableListOf<String>()
        var metadataSeen: MediaMetadata? = null
        val recorder = Proxy.newProxyInstance(
            Player.Listener::class.java.classLoader,
            arrayOf(Player.Listener::class.java),
        ) { _, method, args ->
            when (method.name) {
                "equals" -> false
                "hashCode" -> 1
                "toString" -> "recorder"
                else -> {
                    received += signature(method)
                    if (method.name == "onMediaMetadataChanged") metadataSeen = args?.get(0) as MediaMetadata
                    null
                }
            }
        } as Player.Listener
        val branded = MediaMetadata.Builder().setTitle("branded").build()
        val wrapper = BrandedListener(recorder) { branded }

        val callbacks = Player.Listener::class.java.methods.filter { it.declaringClass == Player.Listener::class.java }
        assertTrue("Player.Listener has callbacks", callbacks.size > 30)
        callbacks.forEach { m -> m.invoke(wrapper, *m.parameterTypes.map { dummy(it) }.toTypedArray()) }

        assertEquals(callbacks.map { signature(it) }.sorted(), received.sorted())
        assertEquals("branded", metadataSeen?.title?.toString())
    }

    @Test fun `the real session player reports play state to its listeners`() {
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val instance = service.get()
        val session = PlaybackService::class.java.getDeclaredField("session")
            .let { it.isAccessible = true; it.get(instance) as MediaLibrarySession }
        val changes = mutableListOf<Boolean>()
        session.player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { changes += playWhenReady }
        })
        session.player.playWhenReady = true
        shadowOf(Looper.getMainLooper()).idle()
        session.player.playWhenReady = false
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true, false), changes)
        service.destroy()
    }
}
