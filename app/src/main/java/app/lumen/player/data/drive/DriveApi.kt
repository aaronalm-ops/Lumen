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
    /** The shared drive the item lives in, "" for My Drive and items shared from one. */
    val driveId: String = "",
) {
    val isFolder: Boolean get() = mimeType == DriveApi.MIME_FOLDER
    val isMedia: Boolean get() = mimeType.startsWith("audio/") || mimeType.startsWith("video/")
}

/**
 * Thrown for a 401 or 403 so callers can turn it into "Sign in to Drive again". [reason] is the
 * first reason Drive gave in the error body ("insufficientPermissions", "authError", ...), "" when
 * there was no readable body.
 */
class DriveAuthException(val code: Int, val reason: String = "") : IOException("Drive returned $code${if (reason.isNotBlank()) " ($reason)" else ""}") {
    /** The token lacks a scope (a drive.file-only token asked to look inside a folder). */
    val insufficientScope: Boolean
        get() = code == 403 && (reason == "insufficientPermissions" || reason == "insufficientScopes" || reason == "ACCESS_TOKEN_SCOPE_INSUFFICIENT")
}

/** One shared drive from `drives.list`. */
data class SharedDrive(val id: String, val name: String)

/** Audio and video among a folder's direct children; [more] when the one page was full. */
data class MediaCount(val audio: Int, val video: Int, val more: Boolean)

/**
 * Drive REST v3 over HttpURLConnection with a bearer token. The calls Lumen needs: list a
 * folder's children, list folders for the folder browser (My Drive, Shared with me, shared
 * drives), count a folder's audio and video, stream a file, read the account's email, find or
 * create the "Lumen" folder and upload a backup (multipart, create or update). Every call is
 * blocking; run it on Dispatchers.IO.
 *
 * Every files.list sends `supportsAllDrives=true&includeItemsFromAllDrives=true`; inside a
 * shared drive it also sends `corpora=drive&driveId=<id>`, which Drive needs to list that
 * drive's items. Elsewhere corpora is left at its default (`user`), which covers My Drive and
 * items shared with the user.
 */
object DriveApi {
    const val MIME_FOLDER = "application/vnd.google-apps.folder"
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val FIELDS = "nextPageToken,files(id,name,mimeType,size,modifiedTime,createdTime,driveId)"
    private const val FOLDER_FIELDS = "nextPageToken,files(id,name,mimeType,driveId)"
    private const val ALL_DRIVES = "&supportsAllDrives=true&includeItemsFromAllDrives=true"

    /** The id Drive accepts for the root of My Drive. */
    const val ROOT = "root"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    /** The URL the player streams from; honours Range. */
    fun mediaUrl(fileId: String): String = "$API/files/${encode(fileId)}?alt=media"

    /**
     * `files.list` for one folder's direct children (files and folders), all pages, the largest
     * page size allowed (1000). [driveId] is the shared drive the folder lives in, "" otherwise.
     */
    fun listChildren(token: String, folderId: String, driveId: String = ""): List<DriveFile> =
        listAll(token, "${inParents(folderId)} and trashed = false", FIELDS, driveId, orderBy = null)

    /** The folders directly inside [folderId], by name, for the folder browser. */
    fun listFolders(token: String, folderId: String, driveId: String = ""): List<DriveFile> =
        listAll(token, "${inParents(folderId)} and mimeType = '$MIME_FOLDER' and trashed = false", FOLDER_FIELDS, driveId, orderBy = "name")

    /** Folders shared directly with the user, by name ("Shared with me" in the browser). */
    fun listSharedWithMeFolders(token: String): List<DriveFile> =
        listAll(token, "sharedWithMe = true and mimeType = '$MIME_FOLDER' and trashed = false", FOLDER_FIELDS, driveId = "", orderBy = "name")

    /** `drives.list`: the shared drives the user is a member of, all pages. Empty for most accounts. */
    fun listSharedDrives(token: String): List<SharedDrive> {
        val out = ArrayList<SharedDrive>()
        var pageToken: String? = null
        do {
            val url = StringBuilder("$API/drives?pageSize=100&fields=${encode("nextPageToken,drives(id,name)")}")
            if (pageToken != null) url.append("&pageToken=").append(encode(pageToken))
            val json = getJson(token, url.toString())
            val drives = json.optJSONArray("drives") ?: JSONArray()
            for (i in 0 until drives.length()) {
                val d = drives.getJSONObject(i)
                val id = d.optString("id", "")
                if (id.isNotBlank()) out += SharedDrive(id, d.optString("name", ""))
            }
            pageToken = json.optString("nextPageToken", "").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Audio and video among [folderId]'s direct children: one `files.list` page of up to 1000
     * with only `mimeType` per file, so it stays cheap. [MediaCount.more] is set when Drive had
     * another page.
     */
    fun countMedia(token: String, folderId: String, driveId: String = ""): MediaCount {
        val q = "${inParents(folderId)} and trashed = false and (mimeType contains 'audio/' or mimeType contains 'video/')"
        val url = StringBuilder("$API/files?q=${encode(q)}&fields=${encode("nextPageToken,files(mimeType)")}&pageSize=1000&spaces=drive")
        url.append(ALL_DRIVES).append(corpora(driveId))
        val json = getJson(token, url.toString())
        val files = json.optJSONArray("files") ?: JSONArray()
        var audio = 0
        var video = 0
        for (i in 0 until files.length()) {
            val mime = files.getJSONObject(i).optString("mimeType", "")
            if (mime.startsWith("audio/")) audio++ else if (mime.startsWith("video/")) video++
        }
        return MediaCount(audio, video, more = json.optString("nextPageToken", "").isNotBlank())
    }

    /** One file's metadata (name and shared drive), used before each scan. */
    fun getFile(token: String, fileId: String): DriveFile? {
        val url = "$API/files/${encode(fileId)}?fields=${encode("id,name,mimeType,size,modifiedTime,createdTime,driveId")}&supportsAllDrives=true"
        val f = getJson(token, url)
        val id = f.optString("id", "")
        if (id.isBlank()) return null
        return fileOf(f)
    }

    /** `about.get`: the signed-in account's email address, "" when Drive does not say. */
    fun accountEmail(token: String): String {
        val json = getJson(token, "$API/about?fields=${encode("user(emailAddress)")}")
        return json.optJSONObject("user")?.optString("emailAddress", "").orEmpty()
    }

    private fun listAll(token: String, q: String, fields: String, driveId: String, orderBy: String?): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var pageToken: String? = null
        do {
            val url = StringBuilder("$API/files?q=${encode(q)}&fields=${encode(fields)}&pageSize=1000&spaces=drive")
            url.append(ALL_DRIVES).append(corpora(driveId))
            if (orderBy != null) url.append("&orderBy=").append(encode(orderBy))
            if (pageToken != null) url.append("&pageToken=").append(encode(pageToken))
            val json = getJson(token, url.toString())
            val files = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) out += fileOf(files.getJSONObject(i))
            pageToken = json.optString("nextPageToken", "").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return out.filter { it.id.isNotBlank() }
    }

    private fun fileOf(f: JSONObject): DriveFile = DriveFile(
        id = f.optString("id", ""),
        name = f.optString("name", ""),
        mimeType = f.optString("mimeType", ""),
        size = f.optString("size", "").toLongOrNull() ?: 0L,
        modifiedMs = parseRfc3339(f.optString("modifiedTime", "")),
        createdMs = parseRfc3339(f.optString("createdTime", "")),
        driveId = f.optString("driveId", ""),
    )

    private fun inParents(folderId: String): String = "'${quote(folderId)}' in parents"

    /** Inside a shared drive Drive needs the drive named; elsewhere the default corpus is right. */
    private fun corpora(driveId: String): String =
        if (driveId.isBlank()) "" else "&corpora=drive&driveId=${encode(driveId)}"

    private fun quote(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    /** Opens the media stream for a download. The caller closes the stream and disconnects. */
    fun openMedia(token: String, fileId: String): Pair<HttpURLConnection, InputStream> {
        val connection = connect(token, mediaUrl(fileId), "GET")
        val code = connection.responseCode
        if (code == 401 || code == 403) {
            val reason = errorReason(readError(connection))
            connection.disconnect()
            throw DriveAuthException(code, reason)
        }
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException("Drive download returned $code")
        }
        return connection to connection.inputStream
    }

    /**
     * The app's own "Lumen" folder. With drive.readonly the name query also sees any folder the
     * owner made called "Lumen", which drive.file may not write into, so only a folder this app
     * created or opened (`isAppAuthorized`) is taken; otherwise a new one is made.
     */
    fun findOrCreateFolder(token: String, name: String): String {
        val q = "name = '${quote(name)}' and mimeType = '$MIME_FOLDER' and trashed = false and 'me' in owners"
        val json = getJson(token, "$API/files?q=${encode(q)}&fields=${encode("files(id,name,isAppAuthorized)")}&pageSize=50&spaces=drive")
        val files = json.optJSONArray("files") ?: JSONArray()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val id = f.optString("id", "")
            if (id.isNotBlank() && f.optBoolean("isAppAuthorized", false)) return id
        }
        val body = JSONObject().put("name", name).put("mimeType", MIME_FOLDER).toString()
        val created = sendJson(token, "$API/files?fields=id", "POST", body)
        val id = created.optString("id", "")
        if (id.isBlank()) throw IOException("Drive did not return a folder id")
        return id
    }

    /** A file by name inside a folder that this app created (so drive.file may update it), or null. */
    fun findInFolder(token: String, folderId: String, name: String): String? {
        val q = "name = '${quote(name)}' and ${inParents(folderId)} and trashed = false"
        val json = getJson(token, "$API/files?q=${encode(q)}&fields=${encode("files(id,isAppAuthorized)")}&pageSize=10&spaces=drive")
        val files = json.optJSONArray("files") ?: JSONArray()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val id = f.optString("id", "")
            if (id.isNotBlank() && f.optBoolean("isAppAuthorized", false)) return id
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
            val reason = errorReason(readError(connection))
            RingLog.w("drive", "${connection.requestMethod} ${connection.url.path} returned $code ${reason.ifBlank { "(no reason)" }}")
            throw DriveAuthException(code, reason)
        }
        if (code !in 200..299) {
            val error = readError(connection).take(300)
            throw IOException("Drive returned $code for ${connection.url.path}: $error")
        }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun readError(connection: HttpURLConnection): String = try {
        connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
    } catch (t: Throwable) {
        ""
    }

    /**
     * The first reason in a Drive error body: `error.errors[0].reason` (v2-style, for example
     * "insufficientPermissions"), then `error.details[].reason` (for example
     * "ACCESS_TOKEN_SCOPE_INSUFFICIENT"), then `error.status`.
     */
    private fun errorReason(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val error = JSONObject(body).optJSONObject("error") ?: return ""
            val errors = error.optJSONArray("errors")
            if (errors != null) {
                for (i in 0 until errors.length()) {
                    val reason = errors.optJSONObject(i)?.optString("reason", "").orEmpty()
                    if (reason.isNotBlank()) return reason
                }
            }
            val details = error.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    val reason = details.optJSONObject(i)?.optString("reason", "").orEmpty()
                    if (reason.isNotBlank()) return reason
                }
            }
            error.optString("status", "")
        } catch (t: Throwable) {
            ""
        }
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
