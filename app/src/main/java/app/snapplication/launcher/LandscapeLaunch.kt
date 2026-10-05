package app.snapplication.launcher

import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo

/** Scopes a landscape request to the app opened by MainActivity/ExternalPreview; owned by RemoteActionService. */
class LandscapeLaunch(private val service: RemoteActionService) {
    private var target: String? = null
    private var expires = 0L
    private var entered = false
    private var overlay: View? = null
    private var fullscreenRequested = false

    /** Starts a non-touchable orientation request; never changes the phone's saved rotation settings. */
    fun begin(packageName: String) {
        clear()
        target = packageName; expires = SystemClock.uptimeMillis() + 8000
        val view = View(service)
        val params = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            alpha = 0f
        }
        if (runCatching { service.getSystemService(WindowManager::class.java).addView(view, params) }.isSuccess) overlay = view
    }

    /** Releases on app exit; requests only an explicitly labelled enter-fullscreen action, once per launch. */
    fun update(packageName: String?, root: AccessibilityNodeInfo?) {
        val expected = target ?: return
        if (!LauncherState(service).landscapeApps) { clear(); return }
        if (packageName == expected) {
            entered = true
            if (!fullscreenRequested && SystemClock.uptimeMillis() <= expires && root != null) {
                fullscreenRequested = clickFullscreen(root)
            }
        } else if (entered || SystemClock.uptimeMillis() > expires) clear()
    }

    /** Uses semantic labels instead of coordinates, avoiding exit/fullscreen toggles with unknown state. */
    private fun clickFullscreen(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 35 || !node.isVisibleToUser) return false
        val label = node.contentDescription?.toString()?.trim()?.lowercase()
            ?: node.text?.toString()?.trim()?.lowercase()
        if (node.isEnabled && label in setOf("full screen", "fullscreen", "enter full screen", "enter fullscreen")) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        for (i in 0 until node.childCount.coerceAtMost(150)) {
            val child = node.getChild(i) ?: continue
            val clicked = try { clickFullscreen(child, depth + 1) } finally { child.recycle() }
            if (clicked) return true
        }
        return false
    }

    /** Removes the orientation window on return Home, setting changes or service shutdown. */
    fun clear() {
        overlay?.let { runCatching { service.getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null; target = null; entered = false; fullscreenRequested = false
    }

    /** Identifies only this invisible one-pixel window so capture can ignore its empty pixels. */
    fun ownsWindow(id: Int): Boolean {
        val node = overlay?.createAccessibilityNodeInfo() ?: return false
        return try { node.windowId == id } finally { node.recycle() }
    }
}
