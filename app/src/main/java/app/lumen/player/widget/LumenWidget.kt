package app.lumen.player.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lumen.player.MainActivity
import app.lumen.player.R
import app.lumen.player.diag.RingLog
import app.lumen.player.playback.PlaybackService
import app.lumen.player.ui.formatTime
import app.lumen.player.ui.speedLabel
import com.google.common.util.concurrent.MoreExecutors

/**
 * 4x2 home screen widget mirroring the lock screen: title, "47:12 · -22:20 · 1.25x" mono line,
 * back, play or pause, forward. Redrawn by the service on state changes and heartbeats only.
 */
class LumenWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetState.read(context)
        provideContent { Content(snapshot) }
    }

    @Composable
    private fun Content(s: WidgetSnapshot) {
        val context = LocalContext.current
        // Black surface, as on the lock screen. Colours are duplicated here because Glance cannot
        // read the Compose theme object; they are the DESIGN.md black-screen tokens.
        val surface = ColorProvider(Color(0xFF111111))
        val text = ColorProvider(Color(0xFFF5F5F5))
        val secondary = ColorProvider(Color(0xFF8C8C8C))
        val line = ColorProvider(Color(0xFF262626))
        val amber = ColorProvider(Color(0xFFE8862B))
        val glyphOnAmber = ColorProvider(Color(0xFF111111))

        val openApp = actionStartActivity(Intent(context, MainActivity::class.java))
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(surface)
                .cornerRadius(22.dp)
                .padding(14.dp)
                .clickable(openApp),
        ) {
            Text(
                text = if (s.hasItem) s.title else "Nothing playing",
                style = TextStyle(color = text, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = metaLine(s),
                style = TextStyle(color = secondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(10.dp))
            val fraction = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = fraction,
                modifier = GlanceModifier.fillMaxWidth().height(3.dp),
                color = amber,
                backgroundColor = line,
            )
            Spacer(GlanceModifier.height(10.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RingButton(
                    icon = R.drawable.ic_widget_back,
                    description = "Back",
                    tint = text,
                    action = actionSendBroadcast(WidgetActionReceiver.intent(context, WidgetActionReceiver.ACTION_BACK)),
                )
                Spacer(GlanceModifier.width(12.dp))
                Box(
                    modifier = GlanceModifier
                        .size(48.dp)
                        .background(amber)
                        .cornerRadius(24.dp)
                        .clickable(actionSendBroadcast(WidgetActionReceiver.intent(context, WidgetActionReceiver.ACTION_PLAY_PAUSE))),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(if (s.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play),
                        contentDescription = if (s.isPlaying) "Pause" else "Play",
                        modifier = GlanceModifier.size(22.dp),
                        colorFilter = ColorFilter.tint(glyphOnAmber),
                    )
                }
                Spacer(GlanceModifier.width(12.dp))
                RingButton(
                    icon = R.drawable.ic_widget_forward,
                    description = "Forward",
                    tint = text,
                    action = actionSendBroadcast(WidgetActionReceiver.intent(context, WidgetActionReceiver.ACTION_FORWARD)),
                )
            }
        }
    }

    @Composable
    private fun RingButton(icon: Int, description: String, tint: ColorProvider, action: Action) {
        Box(
            modifier = GlanceModifier
                .size(48.dp)
                .background(ColorProvider(Color(0xFF1A1A1A)))
                .cornerRadius(24.dp)
                .clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(icon),
                contentDescription = description,
                modifier = GlanceModifier.size(24.dp),
                colorFilter = ColorFilter.tint(tint),
            )
        }
    }

    private fun metaLine(s: WidgetSnapshot): String {
        if (!s.hasItem) return "Open Lumen to choose a file"
        val remaining = if (s.durationMs > 0) "-" + formatTime((s.durationMs - s.positionMs).coerceAtLeast(0L)) else "--:--"
        return "${formatTime(s.positionMs)} · $remaining · ${speedLabel(s.speed)}"
    }
}

class LumenWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LumenWidget()
}

/**
 * Receives the widget's button broadcasts and forwards them to the session through a short-lived
 * MediaController, which also starts the service after process death (the controller binds to
 * it and play() then goes through onPlaybackResumption).
 */
class WidgetActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PLAY_PAUSE = "app.lumen.player.widget.PLAY_PAUSE"
        const val ACTION_BACK = "app.lumen.player.widget.BACK"
        const val ACTION_FORWARD = "app.lumen.player.widget.FORWARD"

        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val RELEASE_DELAY_MS = 400L

        fun intent(context: Context, action: String): Intent =
            Intent(context, WidgetActionReceiver::class.java).setAction(action)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        val app = context.applicationContext
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish = Runnable {
            if (done.compareAndSet(false, true)) {
                MediaController.releaseFuture(future)
                pending.finish()
            }
        }
        // The system allows a receiver about ten seconds after goAsync. If the service never
        // answers (it is being killed, or the bind is refused) release and finish before then,
        // rather than letting the receiver be killed with the controller still bound.
        handler.postDelayed(finish, CONNECT_TIMEOUT_MS)
        future.addListener(
            {
                try {
                    val c = future.get()
                    when (action) {
                        ACTION_PLAY_PAUSE -> if (c.isPlaying) c.pause() else c.play()
                        ACTION_BACK -> c.seekBack()
                        ACTION_FORWARD -> c.seekForward()
                    }
                    RingLog.i("widget", "action $action sent")
                } catch (t: Throwable) {
                    RingLog.e("widget", "controller failed for $action", t)
                } finally {
                    // Give the command a moment to cross the binder before releasing.
                    handler.removeCallbacks(finish)
                    handler.postDelayed(finish, RELEASE_DELAY_MS)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }
}
