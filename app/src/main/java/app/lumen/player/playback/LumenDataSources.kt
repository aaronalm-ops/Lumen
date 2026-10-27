package app.lumen.player.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.lumen.player.LumenApp
import app.lumen.player.data.drive.DriveApi
import app.lumen.player.data.drive.DriveRepository
import app.lumen.player.diag.RingLog
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The 200 MB on-disk cache for Drive streams, in cacheDir/drive. One SimpleCache per process
 * (Media3 forbids two instances on one folder), shared by the player's data source and the
 * Library's "is this reachable offline" check.
 */
@UnstableApi
object DriveCache {
    const val MAX_BYTES = 200L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val app = context.applicationContext
            val dir = File(app.cacheDir, "drive").apply { mkdirs() }
            val created = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES), StandaloneDatabaseProvider(app))
            cache = created
            return created
        }
    }

    /** The cache key for a Drive file, stable across token changes (the URL would not be). */
    fun keyFor(fileId: String): String = "drive:$fileId"

    /** True when every byte of the file is in the cache, so it plays with no network. */
    fun isFullyCached(context: Context, fileId: String): Boolean {
        val c = get(context)
        val key = keyFor(fileId)
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        if (length == C.LENGTH_UNSET.toLong() || length <= 0L) return false
        return c.isCached(key, 0L, length)
    }
}

/**
 * The player's data source factory. Local content (`content://`, `file://`) keeps Media3's
 * DefaultDataSource; `drive://<id>` goes to [DriveDataSource]. Nothing else in the service
 * changes: MediaItems keep the `drive://` uri, so the progress keys stay `uri|duration`.
 */
@UnstableApi
class LumenDataSourceFactory(private val context: Context) : DataSource.Factory {
    private val local = DefaultDataSource.Factory(context)

    override fun createDataSource(): DataSource = SchemeDataSource(context, local)
}

/** Picks the concrete source when the DataSpec arrives, since the factory sees no uri. */
@UnstableApi
private class SchemeDataSource(private val context: Context, private val local: DataSource.Factory) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var delegate: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val source: DataSource = when (dataSpec.uri.scheme) {
            "drive" -> DriveDataSource(context)
            // 0.5.0: a YouTube Music item is never played here; the queue holds it on a second of
            // silence so ExoPlayer can move into it, and the service stops at once (see PlaybackService).
            YTM_SCHEME, YTM_LIST_SCHEME -> ByteArrayDataSource(SilentWav.bytes)
            else -> local.createDataSource()
        }
        listeners.forEach { source.addTransferListener(it) }
        delegate = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: throw IOException("read before open")

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        val d = delegate ?: return
        delegate = null
        d.close()
    }
}

/**
 * `drive://<id>`: the offline copy when "Keep offline" has finished, otherwise
 * `https://www.googleapis.com/drive/v3/files/<id>?alt=media` with a bearer token, through the
 * LRU cache so a seek back does not fetch again. DefaultHttpDataSource passes the player's
 * Range requests straight through. A 401 refreshes the token once and retries; a second 401 or
 * any 403 flags "Sign in to Drive again" for Settings and fails the open.
 */
@UnstableApi
class DriveDataSource(private val context: Context) : DataSource {

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val app = LumenApp.from(context)
    private val listeners = ArrayList<TransferListener>()
    private var delegate: DataSource? = null
    private var openedUri: Uri? = null

    /** The bearer token the open delegate was built with, so a refusal mid-read can drop it. */
    private var currentToken: String? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val fileId = dataSpec.uri.host?.takeIf { it.isNotBlank() } ?: dataSpec.uri.schemeSpecificPart.trim('/')
        if (fileId.isBlank()) throw IOException("Drive uri without a file id: ${dataSpec.uri}")
        openedUri = dataSpec.uri

        val offline = app.drive.offlineFile(fileId)
        if (offline != null && offline.length() > 0L) {
            val file = FileDataSource()
            listeners.forEach { file.addTransferListener(it) }
            delegate = file
            return file.open(dataSpec.buildUpon().setUri(Uri.fromFile(offline)).build())
        }

        val token = app.drive.auth.tokenBlocking() ?: run {
            scope.launch { app.drive.onAuthFailure(401) }
            throw IOException("Sign in to Drive again")
        }
        return try {
            openWithToken(dataSpec, fileId, token)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode == 401) {
                RingLog.w("drive", "401 while opening $fileId, refreshing the token once")
                app.drive.auth.invalidate(token)
                val fresh = app.drive.auth.tokenBlocking(forceRefresh = true)
                if (fresh != null) {
                    try {
                        return openWithToken(dataSpec, fileId, fresh)
                    } catch (again: HttpDataSource.InvalidResponseCodeException) {
                        if (again.responseCode == 401 || again.responseCode == 403) scope.launch { app.drive.onAuthFailure(again.responseCode) }
                        throw again
                    }
                }
                scope.launch { app.drive.onAuthFailure(401) }
            } else if (e.responseCode == 403) {
                scope.launch { app.drive.onAuthFailure(403) }
            }
            throw e
        }
    }

    private fun openWithToken(dataSpec: DataSpec, fileId: String, token: String): Long {
        closeDelegate()
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Lumen")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        val cached = CacheDataSource.Factory()
            .setCache(DriveCache.get(context))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
        listeners.forEach { cached.addTransferListener(it) }
        delegate = cached
        currentToken = token
        val resolved = dataSpec.buildUpon()
            .setUri(Uri.parse(DriveApi.mediaUrl(fileId)))
            .setKey(DriveCache.keyFor(fileId))
            .build()
        return cached.open(resolved)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val d = delegate ?: throw IOException("read before open")
        return try {
            d.read(buffer, offset, length)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // The cache served the first span and Drive refused the next one. The player will
            // surface the error; drop the token so the retry asks Play Services for a fresh one.
            if (e.responseCode == 401 || e.responseCode == 403) {
                RingLog.w("drive", "${e.responseCode} while reading, token dropped")
                if (e.responseCode == 401) app.drive.auth.invalidate(currentToken)
                scope.launch { app.drive.onAuthFailure(e.responseCode) }
            }
            throw e
        }
    }

    /** The `drive://` uri the player asked for, so MediaItems.uriOf and the progress key agree. */
    override fun getUri(): Uri? = if (delegate == null) null else openedUri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        closeDelegate()
        openedUri = null
    }

    private fun closeDelegate() {
        val d = delegate ?: return
        delegate = null
        currentToken = null
        try {
            d.close()
        } catch (t: IOException) {
            RingLog.w("drive", "close: $t")
        }
    }
}

private const val YTM_SCHEME = "ytm"
private const val YTM_LIST_SCHEME = "ytm-list"

/**
 * One second of silence as a 16-bit mono 8 kHz WAV (44-byte header plus 16,000 bytes), built once.
 * It is what a YouTube Music queue item "contains" for ExoPlayer; the player is stopped on it.
 */
private object SilentWav {
    private const val SAMPLE_RATE = 8_000
    private const val DATA_BYTES = SAMPLE_RATE * 2

    val bytes: ByteArray by lazy {
        val out = ByteArray(44 + DATA_BYTES)
        fun ascii(at: Int, text: String) = text.forEachIndexed { i, ch -> out[at + i] = ch.code.toByte() }
        fun le32(at: Int, value: Int) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value shr 8) and 0xFF).toByte()
            out[at + 2] = ((value shr 16) and 0xFF).toByte()
            out[at + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun le16(at: Int, value: Int) {
            out[at] = (value and 0xFF).toByte()
            out[at + 1] = ((value shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        le32(4, 36 + DATA_BYTES)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1)
        le16(22, 1)
        le32(24, SAMPLE_RATE)
        le32(28, SAMPLE_RATE * 2)
        le16(32, 2)
        le16(34, 16)
        ascii(36, "data")
        le32(40, DATA_BYTES)
        out
    }
}

/** Keeps the data source code in one file: the only reference the service needs. */
@UnstableApi
fun lumenDataSourceFactory(context: Context): DataSource.Factory = LumenDataSourceFactory(context)

/** Used by the Library to grey a Drive row that cannot be reached: not online, no copy, not fully cached. */
@UnstableApi
fun driveRowReachable(context: Context, uri: String, offlinePath: String?, online: Boolean): Boolean {
    if (online) return true
    if (offlinePath != null && File(offlinePath).exists()) return true
    return try {
        DriveCache.isFullyCached(context, DriveRepository.fileIdOf(uri))
    } catch (t: Throwable) {
        false
    }
}
