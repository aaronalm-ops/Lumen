package app.lumen.player

import android.app.Application
import android.content.Context
import app.lumen.player.data.LabelRepository
import app.lumen.player.data.LumenDb
import app.lumen.player.data.MediaPermissions
import app.lumen.player.data.MediaRepository
import app.lumen.player.data.PositionStore
import app.lumen.player.data.SettingsRepository
import app.lumen.player.data.backup.BackupManager
import app.lumen.player.data.drive.Connectivity
import app.lumen.player.data.drive.DriveAuth
import app.lumen.player.data.drive.DriveRepository
import app.lumen.player.diag.RingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LumenApp : Application() {

    lateinit var db: LumenDb
        private set
    lateinit var store: PositionStore
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var labels: LabelRepository
        private set
    lateinit var media: MediaRepository
        private set
    lateinit var connectivity: Connectivity
        private set
    lateinit var drive: DriveRepository
        private set
    lateinit var backup: BackupManager
        private set

    /** Application-wide work that must outlive any screen: seeding labels, the start-up scan. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True until the first activity has shown the branded splash; nothing else reads it. */
    @Volatile
    var coldStart: Boolean = true

    override fun onCreate() {
        super.onCreate()
        db = LumenDb.build(this)
        store = PositionStore(this, db.progressDao())
        settings = SettingsRepository(db.progressDao())
        labels = LabelRepository(db.labelDao(), settings)
        media = MediaRepository(this, db.mediaDao(), labels, settings)
        connectivity = Connectivity(this)
        drive = DriveRepository(this, db.mediaDao(), labels, settings, DriveAuth(this), connectivity)
        backup = BackupManager(this, db, store, settings, labels, drive)
        RingLog.i("app", "process start, version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appScope.launch {
            try {
                labels.seedDefaults()
                media.rescanAll()
                // The whole-phone index is refreshed in the background when it is stale, and
                // only when the permission is already held: the app never asks on its own.
                if (MediaPermissions.granted(this@LumenApp)) {
                    val lastScan = settings.get(SettingsRepository.KEY_MS_SCAN_AT)?.toLongOrNull() ?: 0L
                    if (System.currentTimeMillis() - lastScan > MediaRepository.RESCAN_AFTER_MS) {
                        RingLog.i("app", "mediastore scan is stale, rescanning")
                        media.scanMediaStore()
                    }
                }
            } catch (t: Throwable) {
                RingLog.e("app", "start-up scan failed", t)
            }
        }
        appScope.launch {
            try {
                // Drive: a stale listing is refreshed silently when the grant still holds; a
                // refused silent token only sets the "Sign in to Drive again" line.
                val folderId = settings.get(SettingsRepository.KEY_DRIVE_FOLDER_ID).orEmpty()
                if (folderId.isNotBlank() && BuildConfig.PICKER_API_KEY.isNotBlank()) {
                    val lastScan = settings.get(SettingsRepository.KEY_DRIVE_SCAN_AT)?.toLongOrNull() ?: 0L
                    if (System.currentTimeMillis() - lastScan > DriveRepository.RESCAN_AFTER_MS) {
                        RingLog.i("app", "drive scan is stale, rescanning")
                        drive.scan()
                    }
                    backup.autoBackupIfDue()
                }
            } catch (t: Throwable) {
                RingLog.e("app", "start-up drive work failed", t)
            }
        }
    }

    companion object {
        fun from(context: Context): LumenApp = context.applicationContext as LumenApp
    }
}
