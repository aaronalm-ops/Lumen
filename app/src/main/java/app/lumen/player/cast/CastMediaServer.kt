package app.lumen.player.cast

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import app.lumen.player.diag.RingLog
import app.lumen.player.playback.lumenDataSourceFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 0.5.0: a very small HTTP/1.1 file server on the phone's Wi-Fi address, so a Chromecast can fetch
 * what Lumen is casting. The Default Media Receiver can only fetch a plain URL: it cannot read a
 * `content://` file on the phone, and it cannot add the bearer token a Drive download needs.
 *
 * - Bound only while casting: [urlFor] starts it on the first cast item, [stop] closes it when the
 *   session ends. Port 0 (a random free port), bound to the Wi-Fi IPv4 address only, never to
 *   every interface.
 * - Serves only the items handed to the receiver in this cast session, each under an unguessable
 *   path `/m/<32 hex>` from SecureRandom. Anything else is a 404. [stop] forgets every token.
 * - GET and HEAD, with `Range: bytes=a-b`, `bytes=a-` and `bytes=-n` (206 with Content-Range), so
 *   the receiver can seek. One request per connection (`Connection: close`).
 * - Reads through Lumen's own data source factory, so `content://`, `file://` and `drive://` (the
 *   offline copy, the 200 MB cache, or Drive with the token) behave exactly as they do for ExoPlayer.
 */
@UnstableApi
class CastMediaServer(context: Context) {

    companion object {
        private const val PATH_PREFIX = "/m/"
        private const val MAX_HEADER_BYTES = 8 * 1024
        private const val BUFFER_BYTES = 64 * 1024
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MAX_CONNECTIONS = 4
        private val TOKEN_PATTERN = Regex("[0-9a-f]{32}")
    }

    private class Entry(val uri: Uri, val mime: String, val sizeBytes: Long)

    private class Request(val method: String, val path: String, val range: String?)

    private val appContext = context.applicationContext
    private val random = SecureRandom()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val tokensByUri = ConcurrentHashMap<String, String>()

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var baseUrl: String? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    /** True while the server is bound. */
    val running: Boolean get() = socket != null

    /**
     * The URL the receiver fetches for [uri], starting the server when needed. The same uri keeps
     * its token for the whole session, so a queue sent twice does not grow the table. Null when the
     * phone has no Wi-Fi (or Ethernet) address to bind to.
     */
    fun urlFor(uri: String, mime: String?, sizeBytes: Long): String? {
        val base = start() ?: return null
        val token = tokensByUri.getOrPut(uri) {
            val fresh = newToken()
            entries[fresh] = Entry(Uri.parse(uri), mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream", sizeBytes)
            fresh
        }
        return base + PATH_PREFIX + token
    }

    /** Binds the server if it is not bound yet; returns its base URL, or null when it cannot bind. */
    @Synchronized
    fun start(): String? {
        baseUrl?.let { if (socket != null) return it }
        val address = localAddress() ?: run {
            RingLog.w("cast", "server: no Wi-Fi address to bind to")
            return null
        }
        return try {
            val server = ServerSocket(0, 16, address)
            val pool = Executors.newFixedThreadPool(MAX_CONNECTIONS) { runnable ->
                Thread(runnable, "lumen-cast-http").apply { isDaemon = true }
            }
            socket = server
            workers = pool
            val url = "http://${address.hostAddress}:${server.localPort}"
            baseUrl = url
            acceptThread = Thread({ acceptLoop(server, pool) }, "lumen-cast-accept").apply {
                isDaemon = true
                start()
            }
            RingLog.i("cast", "server: listening on ${address.hostAddress}:${server.localPort}")
            url
        } catch (t: IOException) {
            RingLog.w("cast", "server: bind failed: $t")
            null
        }
    }

    /** Closes the socket and forgets every token. Safe to call when it is not running. */
    @Synchronized
    fun stop() {
        val server = socket ?: run {
            entries.clear()
            tokensByUri.clear()
            return
        }
        socket = null
        baseUrl = null
        try {
            server.close()
        } catch (t: IOException) {
            RingLog.w("cast", "server: close: $t")
        }
        workers?.shutdownNow()
        workers = null
        acceptThread = null
        entries.clear()
        tokensByUri.clear()
        RingLog.i("cast", "server: stopped")
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    /**
     * The Wi-Fi (or Ethernet) IPv4 address: an interface that is up, not loopback, with a site-local
     * IPv4 address, preferring `wlan*`. A Chromecast on the same network reaches it; nothing outside
     * the local network can, and the mobile data interface is never chosen.
     */
    private fun localAddress(): InetAddress? = try {
        val candidates = ArrayList<Pair<String, Inet4Address>>()
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces != null && interfaces.hasMoreElements()) {
            val nif = interfaces.nextElement()
            if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
            val addresses = nif.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && address.isSiteLocalAddress) candidates += nif.name to address
            }
        }
        candidates.firstOrNull { it.first.startsWith("wlan") }?.second
            ?: candidates.firstOrNull { it.first.startsWith("eth") }?.second
            ?: candidates.firstOrNull { !it.first.startsWith("rmnet") && !it.first.startsWith("ccmni") }?.second
    } catch (t: SocketException) {
        RingLog.w("cast", "server: interface list failed: $t")
        null
    }

    private fun acceptLoop(server: ServerSocket, pool: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (t: IOException) {
                // close() from stop() ends the loop here.
                break
            }
            try {
                pool.execute { handle(client) }
            } catch (t: RejectedExecutionException) {
                closeQuietly(client)
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.tcpNoDelay = true
            val request = readRequest(BufferedInputStream(client.getInputStream(), MAX_HEADER_BYTES))
            val out = BufferedOutputStream(client.getOutputStream(), BUFFER_BYTES)
            if (request == null) {
                writeStatus(out, 400, "Bad Request")
            } else {
                serve(request, out)
            }
            out.flush()
        } catch (t: IOException) {
            // The receiver drops connections it no longer needs (a seek, a buffer full): normal.
        } catch (t: Throwable) {
            RingLog.w("cast", "server: request failed: $t")
        } finally {
            closeQuietly(client)
        }
    }

    /** Reads the request line and headers (up to 8 KB); null when they are malformed. */
    private fun readRequest(input: InputStream): Request? {
        val header = StringBuilder()
        var matched = 0
        val terminator = "\r\n\r\n"
        while (header.length < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            val ch = b.toChar()
            header.append(ch)
            matched = if (ch == terminator[matched]) matched + 1 else if (ch == terminator[0]) 1 else 0
            if (matched == terminator.length) break
        }
        if (matched != terminator.length) return null
        val lines = header.split("\r\n")
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 3) return null
        var range: String? = null
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (line.substring(0, colon).trim().equals("Range", ignoreCase = true)) range = line.substring(colon + 1).trim()
        }
        return Request(parts[0].uppercase(Locale.ROOT), parts[1].substringBefore('?'), range)
    }

    private fun serve(request: Request, out: OutputStream) {
        if (request.method != "GET" && request.method != "HEAD") {
            writeStatus(out, 405, "Method Not Allowed", extra = "Allow: GET, HEAD\r\n")
            return
        }
        val token = request.path.removePrefix(PATH_PREFIX)
        val entry = if (request.path.startsWith(PATH_PREFIX) && TOKEN_PATTERN.matches(token)) entries[token] else null
        if (entry == null) {
            writeStatus(out, 404, "Not Found")
            return
        }
        val parsed = parseRange(request.range)
        var source: DataSource? = null
        try {
            var start = parsed?.first ?: 0L
            var endInclusive = parsed?.second ?: -1L
            if (parsed != null && start < 0L) {
                // bytes=-n: the last n bytes, so the length is needed first.
                val total = lengthOf(entry)
                if (total <= 0L) {
                    writeStatus(out, 416, "Range Not Satisfiable")
                    return
                }
                start = (total + start).coerceAtLeast(0L)
                endInclusive = total - 1
            }
            val opened = lumenDataSourceFactory(appContext).createDataSource()
            source = opened
            val remaining = try {
                opened.open(DataSpec.Builder().setUri(entry.uri).setPosition(start).build())
            } catch (e: IOException) {
                // A start past the end of the file is refused by the source itself.
                if (start > 0L) writeStatus(out, 416, "Range Not Satisfiable") else writeStatus(out, 500, "Internal Server Error")
                RingLog.w("cast", "server: open at $start failed: $e")
                return
            }
            val total = when {
                remaining != C.LENGTH_UNSET.toLong() -> start + remaining
                entry.sizeBytes > 0L -> entry.sizeBytes
                else -> -1L
            }
            // A range on a file of unknown length cannot be answered with a Content-Range.
            if (parsed != null && ((total >= 0L && start >= total) || (total < 0L && start > 0L))) {
                writeStatus(out, 416, "Range Not Satisfiable", extra = if (total >= 0L) "Content-Range: bytes */$total\r\n" else "")
                return
            }
            val last = when {
                total < 0L -> -1L
                endInclusive in start until total -> endInclusive
                else -> total - 1
            }
            val length = if (last >= start) last - start + 1 else -1L
            val head = StringBuilder()
            if (parsed != null && total >= 0L) {
                head.append("HTTP/1.1 206 Partial Content\r\n")
                head.append("Content-Range: bytes $start-$last/$total\r\n")
            } else {
                head.append("HTTP/1.1 200 OK\r\n")
            }
            head.append("Content-Type: ${entry.mime}\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            if (length >= 0L) head.append("Content-Length: $length\r\n")
            head.append("Cache-Control: no-store\r\n")
            head.append("Access-Control-Allow-Origin: *\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            if (request.method == "HEAD") return
            val buffer = ByteArray(BUFFER_BYTES)
            var left = length
            while (left != 0L) {
                val want = if (left < 0L) buffer.size else minOf(buffer.size.toLong(), left).toInt()
                val read = opened.read(buffer, 0, want)
                if (read == C.RESULT_END_OF_INPUT) break
                if (read <= 0) continue
                out.write(buffer, 0, read)
                if (left > 0L) left -= read
            }
        } finally {
            try {
                source?.close()
            } catch (t: IOException) {
                RingLog.w("cast", "server: source close: $t")
            }
        }
    }

    /** The full length of an entry, from the data source or the library row; -1 when unknown. */
    private fun lengthOf(entry: Entry): Long {
        val source = lumenDataSourceFactory(appContext).createDataSource()
        return try {
            val length = source.open(DataSpec.Builder().setUri(entry.uri).build())
            if (length != C.LENGTH_UNSET.toLong()) length else entry.sizeBytes.takeIf { it > 0L } ?: -1L
        } catch (t: IOException) {
            entry.sizeBytes.takeIf { it > 0L } ?: -1L
        } finally {
            try {
                source.close()
            } catch (t: IOException) {
                RingLog.w("cast", "server: source close: $t")
            }
        }
    }

    /**
     * `bytes=a-b` to (a, b), `bytes=a-` to (a, -1), `bytes=-n` to (-n, -1); null for no header or a
     * form this server does not take (several ranges), which is then answered with the whole file.
     */
    private fun parseRange(header: String?): Pair<Long, Long>? {
        if (header == null) return null
        val spec = header.trim()
        if (!spec.startsWith("bytes=", ignoreCase = true)) return null
        val value = spec.substring(6).trim()
        if (value.contains(',')) return null
        val dash = value.indexOf('-')
        if (dash < 0) return null
        val first = value.substring(0, dash).trim()
        val second = value.substring(dash + 1).trim()
        return when {
            first.isEmpty() -> second.toLongOrNull()?.takeIf { it > 0L }?.let { -it to -1L }
            else -> {
                val a = first.toLongOrNull() ?: return null
                val b = if (second.isEmpty()) -1L else second.toLongOrNull() ?: return null
                if (b in 0 until a) null else a to b
            }
        }
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String, extra: String = "") {
        val text = "HTTP/1.1 $code $reason\r\n${extra}Content-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (t: IOException) {
            RingLog.w("cast", "server: socket close: $t")
        }
    }
}
