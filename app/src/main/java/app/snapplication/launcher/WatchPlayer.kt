package app.snapplication.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView

/** Owns embedded video and a media session; MainActivity supplies content and LauncherState stores progress. */
class WatchPlayer(context: Context, private val state: LauncherState, private val onError: (String) -> Unit) {
    val view = PlayerView(context)
    private val player = ExoPlayer.Builder(context).build()
    private val session = MediaSession.Builder(context, player).build()
    private val handler = Handler(Looper.getMainLooper())
    private var item: LibraryItem? = null
    private var queue = emptyList<LibraryItem>()
    /** Periodic checkpoint owned by this player and cancelled by close. */
    private val checkpoint = object : Runnable {
        /** Periodically persists progress while this player is alive. */
        override fun run() { save(); handler.postDelayed(this, 5000) }
    }

    init {
        view.player = player
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            /** Saves both sides of a manual/automatic item transition for independent episode resume. */
            @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                oldPosition.mediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { state.saveProgress(it, oldPosition.positionMs) }
            }

            /** Updates the resume target when a playlist control changes the playing episode. */
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                item = queue.getOrNull(player.currentMediaItemIndex)
                item?.let { state.remember(it) }
            }
            /** Shows recoverable playback failures without silently changing providers. */
            override fun onPlayerError(error: PlaybackException) {
                onError("Playback failed. Check the source or choose another file. (${error.errorCodeName})")
            }
        })
        handler.postDelayed(checkpoint, 5000)
    }

    /** Loads a selected item at its saved position; returning home alone does not autoplay it. */
    fun load(selection: LibraryItem, autoplay: Boolean) {
        if (item?.uri == selection.uri) { if (autoplay) player.play(); return }
        save()
        queue = state.watchQueue().takeIf { items -> items.any { it.uri == selection.uri } } ?: listOf(selection)
        val startIndex = queue.indexOfFirst { it.uri == selection.uri }.coerceAtLeast(0)
        item = selection
        state.remember(selection)
        val position = state.progress(selection.uri)
        player.setMediaItems(queue.map { MediaItem.Builder().setUri(it.uri).setMediaId(it.uri).build() }, startIndex, position)
        player.prepare()
        player.playWhenReady = autoplay
    }

    /** Commits current time for restoration after process death or shutdown. */
    fun save() { item?.let { state.saveProgress(it.uri, player.currentPosition) } }

    /** Stops playback when leaving the launcher so it does not consume power invisibly. */
    fun pause() { player.pause(); save() }

    /** Stages the next local episode at its beginning when the screen turns off past the user's threshold. */
    fun screenOff() {
        player.pause()
        save()
        if (player.hasNextMediaItem() && EpisodeResume.shouldAdvance(player.currentPosition, player.duration, state.completion)) {
            player.seekToNextMediaItem()
            player.seekTo(0)
            save()
        }
    }

    /** Releases decoder, media session and callbacks when MainActivity is destroyed. */
    fun close() {
        save()
        handler.removeCallbacks(checkpoint)
        view.player = null
        session.release()
        player.release()
    }
}
