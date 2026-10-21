package app.lumen.player.data.drive

import app.lumen.player.BuildConfig
import app.lumen.player.diag.RingLog
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/** One file or folder as Drive lists it. */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedMs: Long,
    val createdMs: Long,
) {
    val isFolder: Boolean get() = mimeType == DriveApi.MIME_FOLDER
    val isMedia: Boolean get() = mimeType.startsWith("audio/") || mimeType.startsWith("video/")
}

/** Thrown for a 401 or 403 so callers can turn it into "Sign in to Drive again". */
class DriveAuthException(val code: Int) : IOException("Drive returned $code")

/**
 * Drive REST v3 over HttpURLConnection with a bearer token. Only the four calls Lumen needs:
 * list a folder's children, stream a file, find or create the "Lumen" folder and upload a
 * backup (multipart, create or update). Every call is blocking; run it on Dispatchers.IO.
 */
object DriveApi {
    const val MIME_FOLDER = "application/vnd.google-apps.folder"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val FIELDS = "nextPageToken,files(id,name,mimeType,size,modifiedTime,createdTime)"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** The URL the player streams from; honours Range. */
    fun mediaUrl(fileId: String): String = "$API/files/${encode(fileId)}?alt=media"

    /** `files.list` for one folder, all pages, newest page size allowed (1000). */
    fun listChildren(token: String, folderId: String): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var pageToken: String? = null
        do {
            val q = "'${folderId.replace("'", "\\'")}' in parents and trashed = false"
            val url = StringBuilder("$API/files?q=${encode(q)}&fields=${encode(FIELDS)}&pageSize=1000")
            url.append("&spaces=drive&supportsAllDrives=true&includeItemsFromAllDrives=true")
            if (pageToken != null) url.append("&pageToken=").append(encode(pageToken))
            val json = getJson(token, url.toString())
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out += DriveFile(
                    id = f.optString("id", ""),
                    name = f.optString("name", ""),
                    mimeType = f.optString("mimeType", ""),
                    size = f.optString("size", "").toLongOrNull() ?: 0L,
                    modifiedMs = parseRfc3339(f.optString("modifiedTime", "")),
                    createdMs = parseRfc3339(f.optString("createdTime", "")),
                )
            }
            pageToken = json.optString("nextPageToken", "").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return out.filter { it.id.isNotBlank() }
    }

    /** One file's metadata, used to confirm the picked folder's name. */
    fun getFile(token: String, fileId: String): DriveFile? {
        val url = "$API/files/${encode(fileId)}?fields=${encode("id,name,mimeType,size,modifiedTime,createdTime")}&supportsAllDrives=true"
        val f = getJson(token, url)
        val id = f.optString("id", "")
        if (id.isBlank()) return null
        return DriveFile(
            id = id,
            name = f.optString("name", ""),
            mimeType = f.optString("mimeType", ""),
            size = f.optString("size", "").toLongOrNull() ?: 0L,
            modifiedMs = parseRfc3339(f.optString("modifiedTime", "")),
            createdMs = parseRfc3339(f.optString("createdTime", "")),
        )
    }

    /** Opens the media stream for a download. The caller closes the stream and disconnects. */
    fun openMedia(token: String, fileId: String): Pair<HttpURLConnection, InputStream> {
        val connection = connect(token, mediaUrl(fileId), "GET")
        val code = connection.responseCode
        if (code == 401 || code == 403) {
            connection.disconnect()
            throw DriveAuthException(code)
        }
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException("Drive download returned $code")
        }
        return connection to connection.inputStream
    }

    /** The app's own "Lumen" folder (created by this app, so visible under drive.file). */
    fun findOrCreateFolder(token: String, name: String): String {
        val q = "name = '${name.replace("'", "\\'")}' and mimeType = '$MIME_FOLDER' and trashed = false"
        val json = getJson(token, "$API/files?q=${encode(q)}&fields=${encode("files(id,name)")}&pageSize=10&spaces=drive")
        val files = json.optJSONArray("files") ?: JSONArray()
        for (i in 0 until files.length()) {
            val id = files.getJSONObject(i).optString("id", "")
            if (id.isNotBlank()) return id
        }
        val body = JSONObject().put("name", name).put("mimeType", MIME_FOLDER).toString()
        val created = sendJson(token, "$API/files?fields=id", "POST", body)
        val id = created.optString("id", "")
        if (id.isBlank()) throw IOException("Drive did not return a folder id")
        return id
    }

    /** A file by name inside a folder, or null. */
    fun findInFolder(token: String, folderId: String, name: String): String? {
        val q = "name = '${name.replace("'", "\\'")}' and '${folderId.replace("'", "\\'")}' in parents and trashed = false"
        val json = getJson(token, "$API/files?q=${encode(q)}&fields=${encode("files(id)")}&pageSize=5&spaces=drive")
        val files = json.optJSONArray("files") ?: JSONArray()
        for (i in 0 until files.length()) {
            val id = files.getJSONObject(i).optString("id", "")
            if (id.isNotBlank()) return id
        }
        return null
    }

    /**
     * Multipart upload (`uploadType=multipart`): a JSON metadata part and the content part.
     * Creates the file in [folderId], or replaces the content of [existingId] with PATCH.
     */
    fun uploadMultipart(token: String, folderId: String, name: String, mime: String, bytes: ByteArray, existingId: String?): String {
        val boundary = "lumen_" + System.currentTimeMillis()
        val metadata = JSONObject().put("name", name)
        if (existingId == null) metadata.put("parents", JSONArray().put(folderId))
        val head = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString()).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: ").append(mime).append("\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val url = if (existingId == null) "$UPLOAD/files?uploadType=multipart&fields=id"
        else "$UPLOAD/files/${encode(existingId)}?uploadType=multipart&fields=id"
        val connection = connect(token, url, if (existingId == null) "POST" else "PATCH")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        connection.setFixedLengthStreamingMode(head.size + bytes.size + tail.size)
        try {
            connection.outputStream.use { out ->
                out.write(head)
                out.write(bytes)
                out.write(tail)
                out.flush()
            }
            val json = readJson(connection)
            val id = json.optString("id", "")
            if (id.isBlank()) throw IOException("Drive upload returned no id")
            return id
        } finally {
            connection.disconnect()
        }
    }

    // Plumbing.

    private fun connect(token: String, url: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Lumen/${BuildConfig.VERSION_NAME}")
        return connection
    }

    private fun getJson(token: String, url: String): JSONObject {
        val connection = connect(token, url, "GET")
        try {
            return readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun sendJson(token: String, url: String, method: String, body: String): JSONObject {
        val connection = connect(token, url, method)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        if (code == 401 || code == 403) {
            RingLog.w("drive", "${connection.requestMethod} ${connection.url.path} returned $code")
            throw DriveAuthException(code)
        }
        if (code !in 200..299) {
            val error = try {
                connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(300)
            } catch (t: Throwable) {
                null
            }
            throw IOException("Drive returned $code for ${connection.url.path}: ${error.orEmpty()}")
        }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** "2026-10-21T08:15:30.000Z" to epoch ms; 0 when absent or malformed. */
    fun parseRfc3339(text: String): Long {
        if (text.isBlank()) return 0L
        val trimmed = text.substringBefore('.').removeSuffix("Z")
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return try {
            format.parse(trimmed)?.time ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }
}
