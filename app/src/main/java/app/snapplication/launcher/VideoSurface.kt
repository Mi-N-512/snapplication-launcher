package app.snapplication.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.accessibility.AccessibilityNodeInfo

/** Finds video surfaces in otherwise unknown apps for RemoteActionService, guarded by their own media session. */
object VideoSurface {
    private var lastDiagnostic = ""
    /** Requires a current media session plus a substantial visible video region; audio sessions alone never follow. */
    fun find(context: Context, root: AccessibilityNodeInfo, owner: String): AccessibilityNodeInfo? {
        val sessions = runCatching { context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, MediaAccessService::class.java)) }.getOrDefault(emptyList())
        if (sessions.none { it.packageName == owner && it.playbackState?.state in setOf(
                PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING) }) {
            report(owner, "no active playback session"); return null
        }
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        val minimumArea = bounds.width().toLong() * bounds.height() / 8
        return scan(root, minimumArea, 0).also { report(owner, if (it == null) "no visible video surface" else "video surface detected") }
    }

    /** Emits state changes in private debug builds without logging titles, URLs or captured content. */
    private fun report(owner: String, status: String) {
        val value = "$owner: $status"
        if (BuildConfig.DEBUG && value != lastDiagnostic) android.util.Log.d("SnappVideo", value)
        lastDiagnostic = value
    }

    /** Copies a matching node for the caller to recycle; bounds and depth limit catalogue/thumbnail false positives. */
    private fun scan(node: AccessibilityNodeInfo, minimumArea: Long, depth: Int): AccessibilityNodeInfo? {
        if (depth > 35 || !node.isVisibleToUser) return null
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase()
        val kind = node.className?.toString().orEmpty()
        val description = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val video = id in setOf("video_view", "videoview", "video_player", "video_surface", "video_surface_view", "surface_view", "surface_video", "exo_surface") ||
            kind.endsWith("SurfaceView") || kind.endsWith("TextureView") || kind.endsWith(".VideoView") ||
            description == "video player" || description.startsWith("video player.")
        if (video && bounds.width().toLong() * bounds.height() >= minimumArea && bounds.width() > bounds.height()) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount.coerceAtMost(150)) {
            val child = node.getChild(i) ?: continue
            val found = try { scan(child, minimumArea, depth + 1) } finally { child.recycle() }
            if (found != null) return found
        }
        return null
    }
}
