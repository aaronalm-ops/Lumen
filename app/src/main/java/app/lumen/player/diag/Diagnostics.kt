package app.lumen.player.diag

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import app.lumen.player.BuildConfig
import app.lumen.player.LumenApp
import app.lumen.player.data.MediaPermissions
import app.lumen.player.data.SettingsRepository
import app.lumen.player.data.drive.isDrive
import app.lumen.player.data.isMediaStore
import app.lumen.player.playback.PlaybackService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Builds the diagnostics zip in cacheDir/diagnostics and hands it to the share sheet. */
@OptIn(UnstableApi::class)
object Diagnostics {

    suspend fun share(context: Context) {
        val zip = try {
            buildZip(context)
        } catch (t: Throwable) {
            RingLog.e("diag", "zip failed", t)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share diagnostics").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private suspend fun buildZip(context: Context): File {
        val app = LumenApp.from(context)
        val store = app.store
        val progressDump = store.dumpTable()
        val backupTail = store.backupTail(50)
        val mediaCount = try {
            app.media.count()
        } catch (t: Throwable) {
            -1
        }
        val folders = try {
            app.media.folderList()
        } catch (t: Throwable) {
            emptyList()
        }
        val mediaStoreCount = try {
            app.media.mediaStoreCount()
        } catch (t: Throwable) {
            -1
        }
        val lastScanAt = try {
            app.settings.get(SettingsRepository.KEY_MS_SCAN_AT)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        }
        val lastScanCount = try {
            app.settings.get(SettingsRepository.KEY_MS_SCAN_COUNT)?.toIntOrNull() ?: 0
        } catch (t: Throwable) {
            0
        }
        val driveLine = try {
            app.drive.describe()
        } catch (t: Throwable) {
            "drive: unavailable ($t)\n"
        }
        val libraryReport = buildString {
            append("media permission: ${MediaPermissions.describe(context)}\n")
            append(driveLine)
            append("drive scopes requested: drive.readonly, drive.file\n")
            append("mediastore last scan: ")
            if (lastScanAt > 0L) {
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.UK).format(Date(lastScanAt)))
                append(", $lastScanCount files\n")
            } else {
                append("never\n")
            }
            append("media rows: $mediaCount (mediastore $mediaStoreCount)\n")
            append("folders: ${folders.size} (${folders.count { it.isMediaStore }} phone buckets, ${folders.count { it.isDrive }} drive, ${folders.count { !it.isMediaStore && !it.isDrive }} picked)\n")
            folders.forEach { append("  ${if (it.isMediaStore) "phone " else if (it.isDrive) "drive " else "picked"} | ${it.name} | ${it.uri}\n") }
        }
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date())
            val zip = File(dir, "lumen-diagnostics-$stamp.zip")
            ZipOutputStream(FileOutputStream(zip)).use { out ->
                out.entry("device.txt", deviceReport(context) + libraryReport)
                out.entry("log.txt", RingLog.snapshot().joinToString("\n", postfix = "\n"))
                out.entry("progress-table.txt", progressDump)
                out.entry("positions-tail.jsonl", backupTail)
            }
            RingLog.i("diag", "zip written ${zip.length()} bytes")
            zip
        }
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    fun deviceReport(context: Context): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val bucket = try {
            bucketName(usm.appStandbyBucket)
        } catch (t: Throwable) {
            "unavailable ($t)"
        }
        return buildString {
            append("app: ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.UK).format(Date())}\n")
            append("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
            append("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
            append("one ui: ${oneUiVersion()}\n")
            append("security patch: ${Build.VERSION.SECURITY_PATCH}\n")
            append("ignoring battery optimisations: ${pm.isIgnoringBatteryOptimizations(context.packageName)}\n")
            append("app standby bucket: $bucket\n")
            append("service running: ${PlaybackService.isRunning}\n")
        }
    }

    /** Reads ro.build.version.oneui through SystemProperties; "unknown" when not a Samsung. */
    fun oneUiVersion(): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        val raw = get.invoke(null, "ro.build.version.oneui") as? String
        if (raw.isNullOrBlank()) {
            "unknown"
        } else {
            // Samsung encodes 8.5 as 80500, 6.1 as 60100.
            val n = raw.toIntOrNull()
            if (n == null) raw else "${n / 10000}.${(n % 10000) / 100} ($raw)"
        }
    } catch (t: Throwable) {
        "unknown"
    }

    private fun bucketName(bucket: Int): String = when (bucket) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE ($bucket)"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET ($bucket)"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT ($bucket)"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE ($bucket)"
        UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED ($bucket)"
        else -> "other ($bucket)"
    }
}
