package app.lumen.player

import android.app.Application
import android.content.Context
import app.lumen.player.data.LumenDb
import app.lumen.player.data.PositionStore
import app.lumen.player.diag.RingLog

class LumenApp : Application() {

    lateinit var db: LumenDb
        private set
    lateinit var store: PositionStore
        private set

    override fun onCreate() {
        super.onCreate()
        db = LumenDb.build(this)
        store = PositionStore(this, db.progressDao())
        RingLog.i("app", "process start, version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    companion object {
        fun from(context: Context): LumenApp = context.applicationContext as LumenApp
    }
}
