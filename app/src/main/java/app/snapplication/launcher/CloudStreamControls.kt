package app.snapplication.launcher

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/*
 * Uses CloudStream's visible next-episode button when its media session exposes only the current item.
 * Related classes: ExternalWatchPlayback requests this bounded action through RemoteActionService.
 * UI: Reveals the existing player's controls once, then clicks its enabled Next button if present.
 * Edit here for verified CloudStream control-ID changes. Never search the catalogue, choose an arbitrary
 * episode, or inject repeated taps. A changed foreground app, absent player or missing Next stops the action.
 */
class CloudStreamControls(private val service: RemoteActionService) {
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0

    // Reveals controls once and makes at most one Next request in the current foreground player.
    fun next(owner: String, isCurrent: () -> Boolean, result: (Boolean) -> Unit) {
        val request = ++generation
        var revealed = false
        var attempts = 0
        fun attempt() {
            if (request != generation || !isCurrent()) return
            val root = service.rootInActiveWindow
            if (root == null) { result(false); return }
            try {
                if (root.packageName?.toString() != owner || RemoteActionService.currentPackage != owner) {
                    result(false); return
                }
                val players = root.findAccessibilityNodeInfosByViewId("$owner:id/player_view")
                val bounds = try {
                    players.firstOrNull { it.isVisibleToUser }?.let { Rect().apply { it.getBoundsInScreen(this) } }
                } finally { players.forEach { it.recycle() } }
                if (bounds == null || bounds.isEmpty) { result(false); return }
                for (id in listOf("player_skip_episode", "player_go_forward")) {
                    val buttons = root.findAccessibilityNodeInfosByViewId("$owner:id/$id")
                    val clicked = try {
                        buttons.firstOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }
                            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    } finally { buttons.forEach { it.recycle() } }
                    if (clicked) { result(true); return }
                }
                if (!revealed) {
                    revealed = true
                    val titles = root.findAccessibilityNodeInfosByViewId("$owner:id/player_video_title")
                    val controlsVisible = try { titles.any { it.isVisibleToUser } } finally { titles.forEach { it.recycle() } }
                    if (!controlsVisible) {
                        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
                        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 40)).build()
                        if (!service.dispatchGesture(gesture, null, null)) { result(false); return }
                    }
                }
                if (++attempts < 5) handler.postDelayed({ attempt() }, 200) else result(false)
            } finally { root.recycle() }
        }
        attempt()
    }

    // Cancels pending probes when accessibility stops; no gesture loop survives service destruction.
    fun close() { generation++; handler.removeCallbacksAndMessages(null) }
}
