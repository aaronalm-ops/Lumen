package app.lumen.player.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.lumen.player.diag.RingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the widget shows. Written by the service on every state change and heartbeat, read by
 * [LumenWidget.provideGlance] in the widget host's update. SharedPreferences because the widget
 * must render after the service process is gone.
 */
data class WidgetSnapshot(
    val hasItem: Boolean = false,
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val isPlaying: Boolean = false,
    /** The item takes the speech layout; paused on one, the widget shows the amber head. */
    val isSpeech: Boolean = false,
)

object WidgetState {
    private const val PREFS = "widget"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun read(context: Context): WidgetSnapshot {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WidgetSnapshot(
            hasItem = p.getBoolean("hasItem", false),
            title = p.getString("title", "") ?: "",
            positionMs = p.getLong("position", 0L),
            durationMs = p.getLong("duration", 0L),
            speed = p.getFloat("speed", 1.0f),
            isPlaying = p.getBoolean("playing", false),
            isSpeech = p.getBoolean("speech", false),
        )
    }

    /** Stores the snapshot and asks Glance to redraw every placed widget. */
    fun publish(context: Context, snapshot: WidgetSnapshot) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("hasItem", snapshot.hasItem)
            .putString("title", snapshot.title)
            .putLong("position", snapshot.positionMs)
            .putLong("duration", snapshot.durationMs)
            .putFloat("speed", snapshot.speed)
            .putBoolean("playing", snapshot.isPlaying)
            .putBoolean("speech", snapshot.isSpeech)
            .apply()
        scope.launch {
            try {
                LumenWidget().updateAll(app)
            } catch (t: Throwable) {
                RingLog.w("widget", "updateAll failed: $t")
            }
        }
    }
}
