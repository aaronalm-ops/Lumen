package app.lumen.player.car

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * 0.9.0: read-only artwork for Android Auto, which runs in its own process and cannot open
 * Lumen's file:// paths. Serves only Lumen's generated tiles (files/car-art) and cached covers
 * (files/art) by plain file name; nothing else in the app is reachable.
 */
class CarArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        val context = context ?: throw FileNotFoundException(uri.toString())
        val segments = uri.pathSegments
        if (segments.size != 2 || !isSafeName(segments[1])) throw FileNotFoundException(uri.toString())
        val dir = when (segments[0]) {
            "tile" -> CarArt.dir(context)
            "cover" -> File(context.filesDir, "art")
            else -> throw FileNotFoundException(uri.toString())
        }
        val file = File(dir, segments[1])
        if (file.parentFile?.canonicalPath != dir.canonicalPath || !file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/*"
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,120}$")

        fun isSafeName(name: String): Boolean = SAFE.matches(name) && !name.contains("..")
    }
}
