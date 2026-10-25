package app.snapplication.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/*
Resumes a retained external player's session after ExternalPreview brings its task forward. Used by
or used with: ExternalPreview supplies the explicit Play request; MediaAccessService supplies
user-approved session access; RemoteActionService verifies the foreground app and CloudStream
controls. Screen controls: No screen of its own. Completion is applied on the next explicit Play,
never while browsing Home. Edit here to add supported transport integrations. Never restart tasks,
retain stream URLs, or claim ownership of another application's buffer. Retries expire and cannot
follow the user to another app.

This object is a shared helper and does not inherit from another launcher class.

handler schedules short resume attempts. generation cancels old attempts when the user leaves, and
advanceGuard prevents repeated Next commands. observed and observer track changes from the selected
media player. resume finds that player, checks whether the episode is complete, then requests Play
or Next. ExternalPreview calls this shared helper; the other app still owns its video and buffer.
*/
object ExternalWatchPlayback {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private val advanceGuard = EpisodeAdvanceGuard()
    private var observed: MediaController? = null
    private var observer: MediaController.Callback? = null

    // Cancels delayed commands when Home is entered again, without pausing another app's media.
    fun cancelResume() { generation++ }

    // Combines session and media identity; progress callbacks handle players that reuse both.
    private fun identity(controller: MediaController): List<Any?> = listOf(controller.sessionToken,
        controller.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        controller.metadata?.description?.title?.toString(),
        controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION))

    // Watches only long enough to acknowledge a new episode, then releases the controller callback.
    private fun markAdvance(controller: MediaController, previousIdentity: List<Any?>, position: Long) {
        observed?.let { old -> observer?.let { old.unregisterCallback(it) } }
        advanceGuard.sent(previousIdentity, position)
        val callback = object : MediaController.Callback() {
            // A new starting position allows another advancement even when the series title is
            // unchanged.
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (observer === this && state != null && !advanceGuard.blocked(identity(controller), state.position)) {
                    controller.unregisterCallback(this); observer = null; observed = null
                }
            }
            // A new metadata identity can acknowledge the transition before playback starts.
            override fun onMetadataChanged(metadata: MediaMetadata?) { onPlaybackStateChanged(controller.playbackState) }
        }
        observed = controller; observer = callback
        controller.registerCallback(callback, handler)
        callback.onPlaybackStateChanged(controller.playbackState)
        handler.postDelayed({
            if (observer === callback) { controller.unregisterCallback(callback); observer = null; observed = null }
        }, 30_000)
    }

    // Reads only the selected application's session using the existing notification-access grant.
    fun active(context: Context, owner: String): MediaController? = runCatching {
        context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, MediaAccessService::class.java))
            .firstOrNull { it.packageName == owner }
    }.getOrNull()

    // Evaluates actual reported duration/position; missing metadata cannot complete an episode.
    fun completed(context: Context, controller: MediaController): Boolean {
        val playback = controller.playbackState ?: return false
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        return EpisodeResume.shouldAdvance(playback.position, duration, LauncherState(context).completion)
    }

    // Waits briefly for the retained player to become foreground, then sends one explicit command.
    fun resume(context: Context, owner: String, onUnavailable: (String) -> Unit) {
        val app = context.applicationContext
        val request = ++generation
        var expected = active(app, owner)?.let(::identity)
        var attempts = 0
        fun attempt() {
            if (request != generation) return
            val foreground = RemoteActionService.currentPackage
            if (foreground != null && foreground != owner && foreground != app.packageName) return
            val controller = active(app, owner)
            if ((RemoteActionService.instance != null && foreground != owner) || controller == null) {
                if (++attempts < 10) handler.postDelayed({ attempt() }, 150)
                else onUnavailable("Open the episode in the app; its playback session is not available yet.")
                return
            }
            val playback = controller.playbackState ?: return
            val item = identity(controller)
            if (expected == null) expected = item
            val sameCompletedItem = expected == item && completed(app, controller)
            val alreadyAdvanced = advanceGuard.blocked(item, playback.position)
            if (BuildConfig.DEBUG) android.util.Log.d("SnappPlayback", "Resume $owner position=${playback.position} duration=${controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)} next=${playback.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L}")
            if (sameCompletedItem && !alreadyAdvanced) {
                if (playback.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) {
                    runCatching { controller.transportControls.skipToNext() }.onSuccess {
                        markAdvance(controller, item, playback.position)
                    }.onFailure { onUnavailable("The next-episode control is no longer available.") }
                    return
                }
                val service = RemoteActionService.instance
                if (owner.startsWith("com.lagradost.cloudstream") && service != null) {
                    service.nextCloudStreamEpisode(owner, {
                        request == generation && identity(controller) == item && completed(app, controller)
                    }) { advanced ->
                        if (request != generation || RemoteActionService.currentPackage != owner) return@nextCloudStreamEpisode
                        if (advanced) { markAdvance(controller, item, playback.position) }
                        else {
                            runCatching { play(controller) }
                            onUnavailable("No next episode is available from this player. Resuming the current episode.")
                        }
                    }
                    return
                }
            }
            runCatching { play(controller) }.onFailure { onUnavailable("Press Play in the app to continue.") }
        }
        handler.postDelayed({ attempt() }, 100)
    }

    // Play is explicit; a session already playing or buffering must never receive a toggle.
    private fun play(controller: MediaController) {
        val playback = controller.playbackState ?: return
        if (playback.state in setOf(PlaybackState.STATE_NONE, PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED) &&
            playback.actions and PlaybackState.ACTION_PLAY != 0L) controller.transportControls.play()
    }
}
