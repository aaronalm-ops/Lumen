package app.lumen.player.update

import android.content.Context
import app.lumen.player.BuildConfig
import app.lumen.player.diag.RingLog
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateInfo(val tag: String, val url: String)

/**
 * Once per 24 hours asks GitHub for the latest release and compares its tag with the installed
 * version. The result is cached so the banner survives app restarts within the same day.
 */
object UpdateChecker {
    private const val PREFS = "update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_TAG = "tag"
    private const val KEY_URL = "url"
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    /** [force] skips the 24 hour interval (the Settings button). */
    suspend fun check(context: Context, force: Boolean = false): UpdateInfo? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (!force && now - last < INTERVAL_MS) {
            return cached(prefs.getString(KEY_TAG, null), prefs.getString(KEY_URL, null))
        }
        val fetched = withContext(Dispatchers.IO) { fetchLatest() }
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        if (fetched == null) return cached(prefs.getString(KEY_TAG, null), prefs.getString(KEY_URL, null))
        prefs.edit().putString(KEY_TAG, fetched.tag).putString(KEY_URL, fetched.url).apply()
        return cached(fetched.tag, fetched.url)
    }

    private fun cached(tag: String?, url: String?): UpdateInfo? {
        if (tag.isNullOrBlank() || url.isNullOrBlank()) return null
        return if (isNewer(tag, BuildConfig.VERSION_NAME)) UpdateInfo(tag, url) else null
    }

    private fun fetchLatest(): UpdateInfo? {
        val owner = BuildConfig.GITHUB_OWNER
        val url = URL("https://api.github.com/repos/$owner/lumen/releases/latest")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Lumen/${BuildConfig.VERSION_NAME}")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                RingLog.w("update", "GitHub returned $code")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            val html = json.optString("html_url", "")
            if (tag.isBlank() || html.isBlank()) return null
            RingLog.i("update", "latest release $tag")
            UpdateInfo(tag, html)
        } catch (t: Throwable) {
            RingLog.w("update", "check failed: $t")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Compares "v1.2.3" style tags. Pre-release suffixes such as "-run12" are ignored. */
    fun isNewer(remoteTag: String, localVersion: String): Boolean {
        val remote = parse(remoteTag)
        val local = parse(localVersion)
        for (i in 0 until 3) {
            if (remote[i] != local[i]) return remote[i] > local[i]
        }
        return false
    }

    private fun parse(version: String): IntArray {
        val core = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        return IntArray(3) { i -> parts.getOrNull(i)?.trim()?.toIntOrNull() ?: 0 }
    }
}
