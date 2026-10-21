package app.lumen.player.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions behind the whole-phone scan. API 33 and later split media access into
 * READ_MEDIA_AUDIO and READ_MEDIA_VIDEO; 29 to 32 use READ_EXTERNAL_STORAGE for both. Audio is
 * the one that matters: a scan runs when audio is granted and includes video only when that is
 * granted too (Android 14 can hand back a partial "selected videos" grant, which counts as denied).
 */
object MediaPermissions {

    /** What to ask for with RequestMultiplePermissions. */
    val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private val audioPermission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val videoPermission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun audioGranted(context: Context): Boolean = has(context, audioPermission)

    fun videoGranted(context: Context): Boolean = has(context, videoPermission)

    /** True when the scan can run at all. */
    fun granted(context: Context): Boolean = audioGranted(context)

    /** One line for device.txt, for example "audio granted, video denied (READ_MEDIA_AUDIO, READ_MEDIA_VIDEO)". */
    fun describe(context: Context): String {
        val audio = if (audioGranted(context)) "granted" else "denied"
        val video = if (videoGranted(context)) "granted" else "denied"
        val names = required.joinToString(", ") { it.substringAfterLast('.') }
        return "audio $audio, video $video ($names)"
    }
}
