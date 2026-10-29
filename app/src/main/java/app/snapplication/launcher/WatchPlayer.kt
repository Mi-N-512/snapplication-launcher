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

/*
Owns embedded video and a media session; MainActivity supplies content and LauncherState stores
progress.

This class is a helper and does not inherit from another launcher class.

player is the local video engine; view shows its picture and controls; session exposes its media
controls to Android. item and queue identify the selected video and nearby files. stagedNext tracks
preparation of the next file, while checkpoint saves progress regularly. MainActivity creates this
player and calls load. pause saves progress and checks whether to prepare the next file; close
releases playback resources. setTapOnly hides the player controls so a tap on the picture plays or
pauses, with tapBadge briefly confirming which.

Used by or used with: MainActivity supplies LibraryItem content; LauncherState stores progress;
EpisodeResume supplies completion rules. Screen controls: Embedded video surface and its player
controls, with an Android media session. Changes belong here: Edit local/direct-link playback and
progress here. External app buffering is outside this player; future Gaming rendering should be a
separate component.
*/
class WatchPlayer(context: Context, private val state: LauncherState, private val onError: (String) -> Unit) {
    val view = PlayerView(context)
    private val player = ExoPlayer.Builder(context).build()
    private val session = MediaSession.Builder(context, player).build()
    private val handler = Handler(Looper.getMainLooper())
    private var item: LibraryItem? = null
    private var queue = emptyList<LibraryItem>()
    private var stagedNext = false
    private val nextTitle = android.widget.TextView(context).apply {
        textSize = 16f; setTextColor(android.graphics.Color.WHITE); setBackgroundColor(0xAA101010.toInt())
        setPadding(16, 8, 16, 8); visibility = android.view.View.GONE
    }
    private val tapBadge = android.widget.TextView(context).apply {
        textSize = 32f; setTextColor(android.graphics.Color.WHITE); setBackgroundColor(0x99101010.toInt())
        setPadding(40, 16, 40, 16); visibility = android.view.View.GONE
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val hideBadge = Runnable { tapBadge.visibility = android.view.View.GONE }
    private val tapLayer = android.view.View(context).apply {
        visibility = android.view.View.GONE
        isFocusable = true; isClickable = true
        contentDescription = "Video. Select to play or pause"
        setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
            tapBadge.text = if (player.playWhenReady) "▶" else "❚❚"
            tapBadge.visibility = android.view.View.VISIBLE
            tapBadge.removeCallbacks(hideBadge); tapBadge.postDelayed(hideBadge, 900)
        }
    }
    // Periodic checkpoint owned by this player and cancelled by close.
    private val checkpoint = object : Runnable {
        // Periodically saves progress while this player is alive.
        override fun run() { save(); handler.postDelayed(this, 5000) }
    }

    init {
        view.player = player
        view.addView(nextTitle, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        view.addView(tapLayer, android.widget.FrameLayout.LayoutParams(-1, -1))
        view.addView(tapBadge, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        player.addListener(object : Player.Listener {
            // Keeps preview-only mode paused even if a hardware/media-session Play command arrives.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && !state.playVideosInLauncher) player.pause()
                if (playWhenReady) { nextTitle.visibility = android.view.View.GONE; stagedNext = false }
            }
            // Claims readiness only once the next local file has actually rendered its first frame.
            override fun onRenderedFirstFrame() {
                if (nextTitle.visibility == android.view.View.VISIBLE)
                    nextTitle.text = "Next ready: ${item?.title.orEmpty()}"
            }
            // Saves both sides of a manual/automatic item transition for independent episode
            // resume.
            @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                oldPosition.mediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { state.saveProgress(it, oldPosition.positionMs) }
            }

            // Updates the resume target when a playlist control changes the playing episode.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                item = queue.getOrNull(player.currentMediaItemIndex)
                item?.let { state.remember(it) }
            }
            // Shows recoverable playback failures without silently changing providers.
            override fun onPlayerError(error: PlaybackException) {
                onError("Playback failed. Check the source or choose another file. (${error.errorCodeName})")
            }
        })
        handler.postDelayed(checkpoint, 5000)
    }

    // Changes the live video's aspect handling without pausing or reloading playback.
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun applyContentScale() {
        view.resizeMode = when (state.contentScale) {
            "Fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "Stretch" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // Loads a selected item at its saved position; returning home alone does not autoplay it.
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun load(selection: LibraryItem, autoplay: Boolean) {
        applyContentScale()
        if (item?.uri == selection.uri) { if (autoplay && state.playVideosInLauncher) player.play(); return }
        save()
        queue = state.watchQueue().takeIf { items -> items.any { it.uri == selection.uri } } ?: listOf(selection)
        val startIndex = queue.indexOfFirst { it.uri == selection.uri }.coerceAtLeast(0)
        item = selection
        state.remember(selection)
        val position = state.progress(selection.uri)
        player.setMediaItems(queue.map { MediaItem.Builder().setUri(it.uri).setMediaId(it.uri).build() }, startIndex, position)
        player.prepare()
        player.playWhenReady = autoplay && state.playVideosInLauncher
    }

    // Tap-only viewer: no control bar; a tap or Select on the picture toggles playback and shows
    // a short badge. PlayerView ignores touches without its controller, so a clear layer takes
    // them.
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun setTapOnly(enabled: Boolean) {
        view.useController = !enabled && state.playVideosInLauncher
        tapLayer.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        if (enabled) view.hideController()
    }

    // Commits current time for restoration after process death or shutdown.
    fun save() { item?.let { state.saveProgress(it.uri, player.currentPosition) } }

    // Stops playback when leaving the launcher so it does not consume power invisibly.
    fun pause() { player.pause(); save(); prepareNextIfComplete() }

    // Stages the next local episode at its beginning when the screen turns off past the user's
    // threshold.
    fun screenOff() {
        player.pause()
        save()
        prepareNextIfComplete()
    }

    // Prepares the next local file, paused at its first frame, when returning Home after the
    // threshold.
    private fun prepareNextIfComplete() {
        if (!state.playVideosInLauncher || stagedNext) return
        if (player.hasNextMediaItem() && EpisodeResume.shouldAdvance(player.currentPosition, player.duration, state.completion)) {
            stagedNext = true
            val next = queue.getOrNull(player.currentMediaItemIndex + 1)
            nextTitle.text = "Preparing next: ${next?.title.orEmpty()}"
            nextTitle.visibility = android.view.View.VISIBLE
            player.seekToNextMediaItem()
            player.seekTo(0)
            // The transition callback arrives later; switch the resume target now so a stop or
            // process death before it cannot reopen the finished episode or reset its progress.
            if (next != null) { item = next; state.remember(next) }
            save()
        }
    }

    // Releases decoder, media session and callbacks when MainActivity is destroyed.
    fun close() {
        save()
        handler.removeCallbacks(checkpoint)
        tapBadge.removeCallbacks(hideBadge)
        view.player = null
        session.release()
        player.release()
    }
}
