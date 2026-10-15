package app.snapplication.launcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import org.json.JSONObject
import java.util.concurrent.Executors

/*
 * Runs the explicit Wired Projection visual preset independently of ordinary guarded recordings.
 * ProjectionImageAction owns its reference; PreviewCaptureService supplies fresh, memory-only frames
 * under existing Android consent. RemoteActionService owns the Stop overlay and cancels this runner.
 * UI: Bounded matching status and a final result, never background or automatic acceptance.
 * Extend only with independently verified dialog adapters. Never add a blind coordinate fallback.
 */
internal class ProjectionImageRunner(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var tapDispatched = false

    // Invalidates every pending load, comparison and tap without retaining captured screen images.
    fun cancel() { generation++; PreviewCaptureService.cancelVisualFrame() }

    // Checks two separately requested fresh frames; mismatch, timeout, lock or orientation change stops without a tap.
    fun start(macro: JSONObject, dryRun: Boolean, finished: (String) -> Unit) {
        cancel()
        val request = generation
        tapDispatched = false
        val deadline = SystemClock.elapsedRealtime() + 8000
        fun result(message: String) {
            if (request != generation) return
            cancel()
            service.getSharedPreferences("remoteMacros", 0).edit().putString("projectionImageResult", message).apply()
            finished(message)
        }
        if (!PreviewCaptureService.active) { result("Enable Watch screen capture before using image matching."); return }
        if (!dryRun && !externalDisplay()) { result("Connect the external display and open Wired Projection first. No tap sent."); return }
        main.postDelayed({ result(if (tapDispatched) "Tap completion was not confirmed. It was not retried."
            else "Image match timed out. No tap sent; check the dialog, orientation and capture permission.") }, 8000)
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val reference = runCatching {
                val bitmap = ProjectionImageAction.load(service, Uri.parse(macro.optString("overlayUri")))
                try { ProjectionImageAction.sample(bitmap) } finally { bitmap.recycle() }
            }.getOrNull()
            main.post {
                if (request == generation) {
                    if (reference == null) result("Reference screenshot is unavailable. Choose it again.")
                    else poll(reference, request, deadline, 0, dryRun, ::result)
                }
            }
            worker.shutdown()
        }
    }

    // Samples only while this explicit request is active; all pixel comparison happens on the capture worker.
    private fun poll(reference: IntArray, request: Int, deadline: Long, consecutive: Int, dryRun: Boolean,
        result: (String) -> Unit) {
        if (request != generation || SystemClock.elapsedRealtime() >= deadline) return
        if (!unlocked()) { result("Unlock the phone first. No tap sent."); return }
        val requestedRotation = rotation()
        val requestedAt = SystemClock.elapsedRealtime()
        if (!PreviewCaptureService.requestVisualFrame { bitmap ->
            val sample = runCatching { ProjectionImageAction.sample(bitmap) }.getOrNull()
            val matches = sample != null && ProjectionImageMatch.matches(reference, sample)
            main.post {
                if (request != generation || SystemClock.elapsedRealtime() >= deadline) return@post
                if (requestedRotation != rotation() || !unlocked()) { result("Screen changed or locked. No tap sent."); return@post }
                val stable = if (matches) consecutive + 1 else 0
                if (stable >= 2 && SystemClock.elapsedRealtime() - requestedAt < 500) {
                    if (dryRun) { result("Match verified twice. Test only: no tap sent."); return@post }
                    if (!externalDisplay()) { result("External display disconnected. No tap sent."); return@post }
                    val size = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    service.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(size)
                    val path = Path().apply { moveTo(size.widthPixels * ProjectionImageAction.TAP_X,
                        size.heightPixels * ProjectionImageAction.TAP_Y) }
                    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
                    // Dispatch once. Neither cancellation nor uncertain completion is ever retried.
                    tapDispatched = true
                    val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) { result("Matched Wired Projection and sent one Agree tap.") }
                        override fun onCancelled(gestureDescription: GestureDescription?) { result("Android cancelled the tap. It was not retried.") }
                    }, main)
                    if (!accepted) result("Android did not accept the tap. It was not retried.")
                } else main.postDelayed({ poll(reference, request, deadline, stable, dryRun, result) }, 250)
            }
        }) result("Screen capture is unavailable. Use Repair previews first.")
    }

    // Requires a public presentation display, excluding our private capture display; match-only tests need none.
    private fun externalDisplay(): Boolean = service.getSystemService(DisplayManager::class.java).displays.any {
        it.displayId != Display.DEFAULT_DISPLAY && it.flags and Display.FLAG_PRESENTATION != 0 &&
            it.flags and Display.FLAG_PRIVATE == 0
    }

    // A locked or sleeping phone is never an eligible image-action target.
    private fun unlocked(): Boolean = service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    @Suppress("DEPRECATION")
    private fun rotation(): Int = service.getSystemService(WindowManager::class.java).defaultDisplay.rotation
}
