package app.lumen.player

import android.app.Application
import android.content.Context
import app.lumen.player.data.LabelRepository
import app.lumen.player.data.LumenDb
import app.lumen.player.data.MediaPermissions
import app.lumen.player.data.MediaRepository
import app.lumen.player.data.PositionStore
import app.lumen.player.data.SettingsRepository
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

    /** Application-wide work that must outlive any screen: seeding labels, the start-up scan. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        db = LumenDb.build(this)
        store = PositionStore(this, db.progressDao())
        settings = SettingsRepository(db.progressDao())
        labels = LabelRepository(db.labelDao(), settings)
        media = MediaRepository(this, db.mediaDao(), labels, settings)
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
    }

    companion object {
        fun from(context: Context): LumenApp = context.applicationContext as LumenApp
    }
}
