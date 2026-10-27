package app.lumen.player.remote

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.lumen.player.LumenApp
import app.lumen.player.diag.RingLog

/**
 * 0.5.0: Lumen's Notification access credential. Android hands the list of media sessions (and so
 * YouTube Music's controller) only to an app whose notification listener the owner switched on in
 * Settings, Notification access. Lumen reads no notification: every callback here is ignored, and
 * the listener only tells [RemoteController] when access starts, so a YouTube Music item already
 * playing is picked up at once.
 */
class LumenNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        RingLog.i("ytm", "notification access connected")
        LumenApp.from(this).remote.attachIfWatching()
    }

    override fun onListenerDisconnected() {
        RingLog.i("ytm", "notification access disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Not read: Lumen uses the access for media sessions only.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not read.
    }
}
