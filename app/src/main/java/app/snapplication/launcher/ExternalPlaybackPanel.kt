package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.widget.LinearLayout

/*
 * Builds optional controls for other apps' active media sessions; hosted by MainActivity using MediaAccessService
 * access.
 *
 * Related classes: MainActivity, MediaAccessService and LauncherUi.
 * UI: Media access explanation, refresh button, session titles and supported transport controls.
 * Edit or extend: Add controls only when the other app advertises the corresponding media-session action.
 */
class ExternalPlaybackPanel(private val activity: Activity, private val ui: LauncherUi) {
    val view: LinearLayout = ui.column()
    private val sessions = activity.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(activity, MediaAccessService::class.java)

    // Refreshes the visible session snapshot; missing access is a recoverable opt-in state.
    fun refresh() {
        view.removeAllViews()
        view.addView(ui.label("Other app playback", 22f))
        view.addView(ui.label("Control playback only when an app exposes an active Android media session. The app owns its saved position; these controls cannot restore a closed session or embed its video.", 16f))
        view.addView(ui.button("Refresh sessions") { refresh() })
        view.addView(ui.button("Manage media access") { explainAccess() })
        val result = runCatching { sessions?.getActiveSessions(listener) ?: emptyList() }
        result.onFailure {
            view.addView(ui.label("Media access is unavailable. You can enable notification access in Android settings, then return and refresh.", 17f))
        }.onSuccess { controllers ->
            val external = controllers.filter { it.packageName != activity.packageName }
            if (external.isEmpty()) view.addView(ui.label("No active sessions from other apps. Start media in an app, return here and refresh.", 17f))
            external.forEach { controller -> addSession(controller) }
        }
    }

    // Explains Android's broad permission before opening its opt-in settings; never grants access automatically.
    fun explainAccess() {
        AlertDialog.Builder(activity).setTitle("Optional media access")
            .setMessage("Android requires notification access to list other apps' media sessions. This is a broad system permission. Snapplication uses media-session information for these controls; it does not read, save or send notification messages. You can turn access off in the same Android settings screen.")
            .setNegativeButton("Cancel", null).setPositiveButton("Open Android settings") { _, _ ->
                runCatching { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    .onFailure { message("Notification access settings are unavailable on this device.") }
            }.show()
    }

    // Shows only commands advertised by a session, with the app name and current media title.
    private fun addSession(controller: MediaController) {
        val name = runCatching {
            activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(controller.packageName, 0)).toString()
        }.getOrDefault(controller.packageName)
        val title = runCatching { controller.metadata?.description?.title?.toString() }.getOrNull()
        view.addView(ui.label(listOfNotNull(name, title).joinToString(" · "), 18f))
        val state = controller.playbackState
        view.addView(ui.label("At last refresh: ${when (state?.state) {
            PlaybackState.STATE_PLAYING -> "playing"
            PlaybackState.STATE_PAUSED -> "paused"
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> "loading"
            PlaybackState.STATE_STOPPED -> "stopped"
            else -> "status unavailable"
        }}", 15f))
        val actions = state?.actions ?: 0L
        addControl(controller, actions, PlaybackState.ACTION_PLAY, "Resume") { it.transportControls.play() }
        addControl(controller, actions, PlaybackState.ACTION_PAUSE, "Pause") { it.transportControls.pause() }
        addControl(controller, actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS, "Previous") { it.transportControls.skipToPrevious() }
        addControl(controller, actions, PlaybackState.ACTION_SKIP_TO_NEXT, "Next") { it.transportControls.skipToNext() }
        if (actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE) == 0L) {
            addControl(controller, actions, PlaybackState.ACTION_PLAY_PAUSE, "Play or pause") { active ->
                val code = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                active.dispatchMediaButtonEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                active.dispatchMediaButtonEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            }
        }
    }

    // Revalidates both access and the requested command before sending a user-initiated transport request.
    private fun addControl(controller: MediaController, actions: Long, flag: Long, title: String,
                           request: (MediaController) -> Unit) {
        if (actions and flag == 0L) return
        view.addView(ui.button(title) {
            runCatching {
                val active = sessions?.getActiveSessions(listener).orEmpty()
                    .firstOrNull { it.sessionToken == controller.sessionToken }
                    ?: error("Session ended")
                check((active.playbackState?.actions ?: 0L) and flag != 0L)
                request(active)
            }.onFailure { message("This control is no longer available. Refresh sessions or reopen the media app.") }
        })
    }

    // Reports unavailable controls without exposing session tokens or private media addresses.
    private fun message(text: String) {
        if (!activity.isFinishing) AlertDialog.Builder(activity).setMessage(text).setPositiveButton("OK", null).show()
    }
}
