package app.lumen.player.notes

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.lumen.player.LumenApp
import app.lumen.player.data.NotesEntity
import app.lumen.player.remote.YtmLink
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = LumenApp::class)
class NotesServiceJourneyTest {
    @Test fun `silent generation is bounded by the request deadline`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.flush()
            Thread.sleep(2_000)
            exchange.close()
        }
        server.start()
        val started = System.nanoTime()
        try {
            val client = GeminiClient("synthetic-key", "http://127.0.0.1:${server.address.port}", 500)
            assertThrows(java.net.SocketTimeoutException::class.java) {
                client.generate(listOf("fixture"), "fixture", JSONArray(), JSONObject(), 100, null)
            }
            assertTrue("Silent request ignored its deadline", (System.nanoTime() - started) / 1_000_000 < 5_000)
        } finally { server.stop(0) }
    }

    @Test fun `failed transcription saves text then retries continue and completed transcript avoids another upload`() {
        val app = ApplicationProvider.getApplicationContext<LumenApp>()
        val audio = File(app.cacheDir, "synthetic-notes.mp3").also { it.writeBytes(ByteArray(256)) }
        val media = YtmLink("aaaaaaaaaaa", false).toMediaEntity("Synthetic recording", "", 1L)
            .copy(uri = audio.toURI().toString(), folderUri = "fixture", mime = "audio/mpeg", sizeBytes = audio.length())
        runBlocking { app.db.mediaDao().upsertMedia(media) }
        app.notesRepo.setApiKey("synthetic-test-key")
        val uploads = AtomicInteger()
        val transcriptions = AtomicInteger()
        var secondRequest = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        server.createContext("/") { exchange ->
            try {
                val path = exchange.requestURI.path
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                when {
                    path == "/upload/v1beta/files" -> {
                        exchange.responseHeaders.add("X-Goog-Upload-URL", "$base/upload-data")
                        respond(exchange, "{}")
                    }
                    path == "/upload-data" -> { uploads.incrementAndGet(); respond(exchange, """{"file":{"name":"files/fixture","uri":"https://example.invalid/synthetic","mimeType":"audio/mp3"}}""") }
                    path == "/v1beta/files/fixture" -> respond(exchange, """{"state":"ACTIVE"}""")
                    path.contains(":streamGenerateContent") -> {
                        val request = JSONObject(body)
                        val isTranscript = request.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text").startsWith("You transcribe")
                        val events = if (isTranscript) {
                            if (transcriptions.incrementAndGet() == 1) {
                                "data: ${event("[{\"t\":\"00:00\",\"s\":\"First spoken line\"}]")}\n\ndata: {\"error\":{\"code\":429,\"message\":\"Synthetic quota interruption\"}}\n\n"
                            } else {
                                secondRequest = body
                                "data: ${event("[{\"t\":\"00:20\",\"s\":\"Second spoken line\"}]", "STOP")}\n\n"
                            }
                        } else "data: ${event("{\"title\":\"Fixture\",\"sections\":[{\"heading\":\"What was spoken\",\"t\":\"00:00\",\"prose\":[\"First spoken line. Second spoken line.\"]}]}", "STOP")}\n\n"
                        exchange.responseHeaders.add("Content-Type", "text/event-stream")
                        respond(exchange, events)
                    }
                    else -> { exchange.sendResponseHeaders(404, -1); exchange.close() }
                }
            } catch (_: Exception) { exchange.close() }
        }
        server.start()
        fun runService(): NotesEntity {
            val controller = Robolectric.buildService(NotesService::class.java).create()
            val service = controller.get()
            service.createClient = { GeminiClient(it, base, 15_000) }
            try {
                val now = System.currentTimeMillis()
                runBlocking {
                    val old = app.notesRepo.dao.notes(media.uri)
                    app.notesRepo.dao.upsert(NotesEntity(media.uri, media.title, media.sizeBytes, NotesRepository.STATUS_WORKING,
                        "Waiting to start", old?.json.orEmpty(), old?.model.orEmpty(), now, now))
                }
                service.onStartCommand(Intent(app, NotesService::class.java).putExtra(NotesService.EXTRA_URI, media.uri), 0, 1)
                repeat(1000) {
                    shadowOf(Looper.getMainLooper()).idle()
                    val row = runBlocking { app.notesRepo.dao.notes(media.uri) }!!
                    val pending = NotesService::class.java.getDeclaredField("pending").let { field -> field.isAccessible = true; field.get(service) as Set<*> }
                    if (row.status != NotesRepository.STATUS_WORKING && synchronized(pending) { pending.isEmpty() }) return row
                    Thread.sleep(10)
                }
                throw AssertionError("Notes service did not finish")
            } finally { controller.destroy() }
        }
        try {
            val failed = runService()
            assertEquals(NotesRepository.STATUS_FAILED, failed.status)
            assertTrue(failed.step.startsWith("Transcription failed:"))
            assertEquals(1, runBlocking { app.notesRepo.dao.segmentsFor(media.uri) }.size)
            val completed = runService()
            assertEquals(NotesRepository.STATUS_DONE, completed.status)
            assertTrue(secondRequest.contains("after 00:00"))
            assertEquals(listOf("First spoken line", "Second spoken line"), runBlocking { app.notesRepo.dao.segmentsFor(media.uri) }.map { it.text })
            assertEquals(2, uploads.get())
            assertEquals(NotesRepository.STATUS_DONE, runService().status)
            assertEquals("Writing notes again uploaded the audio", 2, uploads.get())
            assertEquals(2, transcriptions.get())
        } finally { server.stop(0) }
    }

    private fun respond(exchange: HttpExchange, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun event(text: String, finish: String = "") = JSONObject().put("candidates", JSONArray().put(JSONObject()
        .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))
        .also { if (finish.isNotBlank()) it.put("finishReason", finish) })).toString()
}
