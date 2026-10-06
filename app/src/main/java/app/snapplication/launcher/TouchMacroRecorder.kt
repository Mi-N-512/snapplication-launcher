package app.snapplication.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

/** Explicit, bounded single-finger recorder used by RemoteActionService. Its transparent overlay
 * observes a gesture, then lets Android mirror it once to the guarded app after the finger lifts.
 * The service owns the always-visible Stop button above this overlay and persists successful steps. */
class TouchMacroRecorder(
    private val service: AccessibilityService,
    private val guard: () -> JSONObject?,
    private val recorded: (JSONObject) -> Unit,
    private val failed: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = service.getSystemService(WindowManager::class.java)
    private var overlay: View? = null
    private var points = JSONArray()
    private var context: JSONObject? = null
    private var startedAt = 0L
    private var busy = false
    private var closed = false
    private var generation = 0
    private val params = WindowManager.LayoutParams(-1, -1,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    /** Installs the recorder before the service installs its smaller Stop overlay. */
    fun start(): Boolean = runCatching {
        val surface = View(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnTouchListener { _, event -> touch(event) }
        }
        manager.addView(surface, params)
        overlay = surface
        true
    }.getOrDefault(false)

    /** Accepts only bounded one-finger paths in the current app's verified window. */
    private fun touch(event: MotionEvent): Boolean {
        if (closed || busy) return true
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            fail("Touch recording supports one finger at a time."); return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                context = guard()
                if (context == null) { fail("Recording stopped: this screen is unavailable or contains private input."); return true }
                points = JSONArray(); startedAt = SystemClock.uptimeMillis()
                addPoint(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_MOVE -> {
                if (context == null) return true
                if (SystemClock.uptimeMillis() - startedAt > MAX_DURATION) {
                    fail("Recording stopped: keep each gesture under three seconds."); return true
                }
                // Downsample dense move events; the final point is always retained.
                if (points.length() < MAX_POINTS - 1) addPoint(event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP -> {
                val before = context ?: return true
                if (SystemClock.uptimeMillis() - startedAt > MAX_DURATION) {
                    fail("Recording stopped: keep each gesture under three seconds."); return true
                }
                addPoint(event.rawX, event.rawY)
                val after = guard()
                if (after == null || !sameScreen(before, after)) {
                    fail("Recording stopped: the screen changed during the gesture."); return true
                }
                val duration = (SystemClock.uptimeMillis() - startedAt).coerceIn(40, MAX_DURATION)
                val step = JSONObject(before.toString()).put("kind", "gesture").put("points", points)
                    .put("duration", duration).put("startedAt", startedAt)
                mirror(step)
            }
            MotionEvent.ACTION_CANCEL -> { context = null; points = JSONArray() }
        }
        return true
    }

    /** Stores only normalized coordinates inside the app, never raw touches on keyboard/system windows. */
    private fun addPoint(x: Float, y: Float) {
        val current = context ?: return
        val bounds = current.optJSONArray("window") ?: return
        if (x < bounds.optDouble(0) || y < bounds.optDouble(1) || x >= bounds.optDouble(2) || y >= bounds.optDouble(3)) {
            fail("Recording stopped: the gesture left the app window."); return
        }
        val excluded = current.optJSONArray("excluded") ?: JSONArray()
        for (index in 0 until excluded.length()) {
            val area = excluded.optJSONArray(index) ?: continue
            if (x >= area.optDouble(0) && x < area.optDouble(2) && y >= area.optDouble(1) && y < area.optDouble(3)) {
                fail("Recording stopped: text input is not recorded."); return
            }
        }
        val width = current.optInt("width").coerceAtLeast(1)
        val height = current.optInt("height").coerceAtLeast(1)
        if (points.length() < MAX_POINTS) points.put(JSONArray().put((x / width).toDouble()).put((y / height).toDouble()))
    }

    /** Temporarily removes interception before injecting once, preventing the recorder from recording itself. */
    private fun mirror(step: JSONObject) {
        if (closed) return
        val surface = overlay ?: return
        val gesture = description(step, step.optInt("width"), step.optInt("height"))
            ?: run { fail("Recording stopped: invalid gesture."); return }
        busy = true
        val request = ++generation
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (runCatching { manager.updateViewLayout(surface, params) }.isFailure) {
            fail("Recording stopped: touch forwarding is unavailable."); return
        }
        // WindowManager applies flags asynchronously. Waiting two frames avoids hitting our own overlay.
        handler.postDelayed({
            if (closed || request != generation) return@postDelayed
            val current = guard()
            if (current == null || !sameScreen(step, current)) {
                fail("Recording stopped: the screen changed before the touch could run."); return@postDelayed
            }
            val accepted = runCatching { service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                /** Saves only gestures Android confirms completed; the next gesture cannot begin until then. */
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (closed || request != generation) return
                    recorded(step)
                    if (closed || request != generation) return
                    busy = false; context = null
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    if (runCatching { manager.updateViewLayout(surface, params) }.isFailure) fail("Recording stopped: overlay unavailable.")
                }
                /** Cancellation never retries or produces another touch. */
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (!closed && request == generation) fail("Recording stopped: Android cancelled the gesture.")
                }
            }, handler) }.getOrDefault(false)
            if (!accepted) fail("Android did not allow touch forwarding. Re-enable Snapplication accessibility support, then try again.")
        }, 80)
        handler.postDelayed({ if (!closed && request == generation && busy) fail("Recording stopped: touch forwarding timed out.") }, step.optLong("duration") + 1500)
    }

    /** Removes the intercepting window on every finish/failure path and invalidates queued callbacks. */
    fun close() {
        if (closed) return
        closed = true; generation++
        handler.removeCallbacksAndMessages(null)
        overlay?.let { runCatching { manager.removeView(it) } }
        overlay = null; context = null
    }

    /** Identifies our non-interactive accessibility window for the service's underlying-app guard. */
    fun ownsWindow(id: Int): Boolean = overlay?.let { surface ->
        val node = surface.createAccessibilityNodeInfo()
        try { node.windowId == id } finally { node.recycle() }
    } == true

    /** Restores the normal screen before reporting an error to the service's visible recorder. */
    private fun fail(message: String) { close(); failed(message) }

    companion object {
        private const val MAX_POINTS = 128
        private const val MAX_DURATION = 3000L

        /** Coordinate actions require the same app, layout, screen dimensions and orientation. */
        fun sameScreen(before: JSONObject, after: JSONObject): Boolean =
            before.optString("package") == after.optString("package") &&
                before.optString("screenGuard") == after.optString("screenGuard") &&
                before.optInt("rotation") == after.optInt("rotation") &&
                before.optInt("width") == after.optInt("width") && before.optInt("height") == after.optInt("height") &&
                before.optJSONArray("window")?.toString() == after.optJSONArray("window")?.toString() &&
                before.optJSONArray("excluded")?.toString() == after.optJSONArray("excluded")?.toString()

        /** Validates stored coordinates and constructs one stroke; malformed saved data cannot inject touches. */
        fun description(step: JSONObject, width: Int, height: Int, speed: Double = 1.0): GestureDescription? {
            val points = step.optJSONArray("points") ?: return null
            if (points.length() !in 1..MAX_POINTS || width <= 0 || height <= 0) return null
            val path = Path()
            for (index in 0 until points.length()) {
                val point = points.optJSONArray(index) ?: return null
                val x = point.optDouble(0, Double.NaN); val y = point.optDouble(1, Double.NaN)
                if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return null
                if (index == 0) path.moveTo((x * (width - 1)).toFloat(), (y * (height - 1)).toFloat())
                else path.lineTo((x * (width - 1)).toFloat(), (y * (height - 1)).toFloat())
            }
            val duration = step.optLong("duration", 0)
            if (duration !in 40..MAX_DURATION) return null
            return runCatching { GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0,
                (duration / speed.coerceIn(.25, 4.0)).toLong().coerceIn(40, MAX_DURATION))).build() }.getOrNull()
        }
    }
}
