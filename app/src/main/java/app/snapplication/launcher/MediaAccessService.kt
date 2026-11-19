package app.snapplication.launcher

import android.service.notification.NotificationListenerService

/*
Supplies Android's opt-in media-session access for ExternalPlaybackPanel; no notification content is
read or stored.

MediaAccessService is a child of Android's NotificationListenerService. Its registered component
identifies the user-approved media access.

Android manages this service after the user enables notification access. It has no app-defined
fields or callbacks. ExternalPlaybackPanel, ExternalPreview and ExternalWatchPlayback use its
component name when asking Android for media sessions. The service does not itself read or save
notification messages.

Used by or used with: ExternalPlaybackPanel and ExternalPreview use its Android-approved
media-session access. Screen controls: No screen; the user grants or revokes access through Android
settings. Changes belong here: Keep this service limited to media-session access and QuietMode;
notification message collection is not implemented.

QuietMode: while connected the service re-checks every few seconds whether a video is playing or a
book is open, and asks Android for priority-only Do Not Disturb during that time.
*/
class MediaAccessService : NotificationListenerService() {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { QuietMode.evaluate(this@MediaAccessService); handler.postDelayed(this, 4000) }
    }

    fun post(work: () -> Unit) { handler.post(work) }

    override fun onListenerConnected() {
        QuietMode.service = this
        handler.removeCallbacks(tick); handler.post(tick)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(tick)
        runCatching { QuietMode.release(this) }
        if (QuietMode.service === this) QuietMode.service = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (QuietMode.service === this) QuietMode.service = null
        super.onDestroy()
    }
}
