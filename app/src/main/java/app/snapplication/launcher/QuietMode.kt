package app.snapplication.launcher

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService

/*
Quiet while watching or reading: turns on Android's Do Not Disturb (priority only, so alarms and
the user's own priority exceptions still come through) while a video is playing or a book is open,
and turns it back off afterwards.

"Watching" means the app on screen owns a playing media session (or, without remote support, any
session is playing while Home is in Watch mode). "Reading" means Read mode with a book open in the
launcher or the chosen reading app in front.

It uses the notification-listener access the user already grants for media controls
(MediaAccessService); a listener may request an interruption filter without extra permissions. It
only restores the filter it set itself, and never overrides a Do Not Disturb the user turned on.
*/
object QuietMode {
    @Volatile internal var service: MediaAccessService? = null
    // MainActivity sets this while its own reader/EPUB panel or viewer shows a book.
    @Volatile var launcherReading = false
        set(value) { field = value; refresh() }
    private var lastWanted = 0L
    private const val GRACE_MS = 8000L

    fun accessGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val mine = ComponentName(context, MediaAccessService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    val active: Boolean get() = service?.let { applied(it) } ?: false

    fun refresh() { service?.let { s -> s.post { evaluate(s) } } }

    private fun applied(context: Context) =
        context.getSharedPreferences("quietMode", Context.MODE_PRIVATE).getBoolean("applied", false)

    private fun setApplied(context: Context, value: Boolean) =
        context.getSharedPreferences("quietMode", Context.MODE_PRIVATE).edit().putBoolean("applied", value).apply()

    // Decides whether quiet is wanted now; called every few seconds while the listener is connected.
    internal fun evaluate(listener: MediaAccessService) {
        val state = LauncherState(listener)
        val wanted = state.quietWhileWatching && (watching(listener, state) || reading(listener, state))
        val now = SystemClock.elapsedRealtime()
        if (wanted) {
            lastWanted = now
            if (!applied(listener) && listener.currentInterruptionFilter == NotificationListenerService.INTERRUPTION_FILTER_ALL) {
                runCatching { listener.requestInterruptionFilter(NotificationListenerService.INTERRUPTION_FILTER_PRIORITY) }
                    .onSuccess { setApplied(listener, true) }
            }
        } else if (applied(listener) && (!state.quietWhileWatching || now - lastWanted > GRACE_MS)) release(listener)
    }

    // Restores normal notifications only if the filter is still the one this launcher chose.
    internal fun release(listener: MediaAccessService) {
        if (!applied(listener)) return
        if (listener.currentInterruptionFilter == NotificationListenerService.INTERRUPTION_FILTER_PRIORITY)
            runCatching { listener.requestInterruptionFilter(NotificationListenerService.INTERRUPTION_FILTER_ALL) }
        setApplied(listener, false)
    }

    private fun watching(context: Context, state: LauncherState): Boolean {
        val sessions = runCatching {
            context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(context, MediaAccessService::class.java))
        }.getOrNull() ?: return false
        val playing = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (playing.isEmpty()) return false
        val foreground = RemoteActionService.currentPackage
        return if (RemoteActionService.instance != null && foreground != null) playing.any { it.packageName == foreground }
            else state.mode == "Watch"
    }

    private fun reading(context: Context, state: LauncherState): Boolean {
        if (state.mode != "Read") return false
        if (launcherReading) return true
        val foreground = RemoteActionService.currentPackage ?: return false
        if (foreground == context.packageName) return false
        val reader = state.lastApp()?.let(ComponentName::unflattenFromString)?.packageName
        return foreground == reader || RemoteActionService.capturePackage == foreground
    }
}
