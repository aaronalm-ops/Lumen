package app.lumen.player.notes

import android.app.Application
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeminiRecoveryTest {
    @Test fun `busy primary retries then falls back and recovers`() = fixture { base, handler ->
        val paths = mutableListOf<String>()
        handler.respond = { exchange, count ->
            paths += exchange.requestURI.path
            if (count < 3) error(exchange, 503) else success(exchange)
        }
        val notices = mutableListOf<GeminiClient.Retry>()
        val delays = mutableListOf<Long>()
        val reply = generate(GeminiClient("fixture-key", base, waitBeforeRetry = { delays += it }), notices)
        assertEquals("fallback", reply.model)
        assertEquals(listOf(false, true), notices.map { it.switchingModel })
        assertEquals(listOf("primary", "primary", "fallback"), paths.map { it.substringAfter("models/").substringBefore(":") })
        assertEquals(2, delays.size)
    }

    @Test fun `retry honours a short server retry-after and can recover on the same model`() = fixture { base, handler ->
        handler.respond = { exchange, count ->
            if (count == 1) { exchange.responseHeaders.add("Retry-After", "5"); error(exchange, 503) }
            else success(exchange)
        }
        val delays = mutableListOf<Long>()
        assertEquals("primary", generate(GeminiClient("fixture-key", base, waitBeforeRetry = { delays += it })).model)
        assertEquals(listOf(5_000L), delays)
    }

    @Test fun `repeated server errors stop after two attempts per model`() = fixture { base, handler ->
        handler.respond = { exchange, _ -> error(exchange, 503) }
        val notices = mutableListOf<GeminiClient.Retry>()
        val failed = assertThrows(GeminiClient.GeminiException::class.java) {
            generate(GeminiClient("fixture-key", base, waitBeforeRetry = {}), notices)
        }
        assertEquals(503, failed.code)
        assertEquals(4, handler.count.get())
        assertEquals(3, notices.size)
    }

    @Test fun `key and quota errors do not retry or switch models`() {
        for (code in listOf(400, 401, 403, 429)) fixture { base, handler ->
            handler.respond = { exchange, _ -> error(exchange, code) }
            assertThrows(GeminiClient.GeminiException::class.java) {
                generate(GeminiClient("fixture-key", base, waitBeforeRetry = { fail("Non-retryable error retried") }))
            }
            assertEquals(1, handler.count.get())
        }
    }

    @Test fun `long server retry-after does not turn into a hidden long-running job`() = fixture { base, handler ->
        handler.respond = { exchange, _ -> exchange.responseHeaders.add("Retry-After", "120"); error(exchange, 503) }
        assertThrows(GeminiClient.GeminiException::class.java) {
            generate(GeminiClient("fixture-key", base, waitBeforeRetry = { fail("Ignored long Retry-After") }))
        }
        assertEquals(1, handler.count.get())
    }

    private fun generate(client: GeminiClient, notices: MutableList<GeminiClient.Retry> = mutableListOf()) =
        client.generate(listOf("primary", "fallback"), "fixture", JSONArray(), JSONObject(), 100, null, onRetry = { notices += it })

    private class Handler {
        val count = AtomicInteger()
        lateinit var respond: (HttpExchange, Int) -> Unit
    }

    private fun fixture(block: (String, Handler) -> Unit) {
        val handler = Handler()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            handler.respond(exchange, handler.count.incrementAndGet())
        }
        server.start()
        try { block("http://127.0.0.1:${server.address.port}", handler) }
        finally { server.stop(0) }
    }

    private fun error(exchange: HttpExchange, code: Int) = respond(exchange, code, """{"error":{"code":$code,"message":"Synthetic provider error"}}""")
    private fun success(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        respond(exchange, 200, "data: " + JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "{}")))))) + "\n\n")
    }
    private fun respond(exchange: HttpExchange, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
