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
Runs the explicit Wired Projection visual preset independently of ordinary guarded recordings.
ProjectionImageAction owns its reference; PreviewCaptureService supplies fresh, memory-only frames
under existing Android consent. RemoteActionService owns the Stop overlay and cancels this runner.
Screen controls: Bounded matching status and a final result, never background or automatic
acceptance. Extend only with independently verified dialog adapters. Never add a blind coordinate
fallback.

This class is a helper and does not inherit from another launcher class.

generation identifies the current request so cancelled comparisons cannot tap later. tapDispatched
prevents a second tap. imageStep holds a general image action, imageAspect checks the expected
screen shape, and marker draws optional feedback. RemoteActionService creates and cancels this
helper. start loads the reference, asks PreviewCaptureService for fresh frames, compares them, and
reports success or the reason it stopped.
*/
internal class ProjectionImageRunner(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var tapDispatched = false
    private val marker = MacroTapOverlay(service)
    private var imageStep: JSONObject? = null
    private var imageAspect = 2.0

    // Cancels every pending load, comparison and tap without retaining captured screen images.
    fun cancel() { generation++; PreviewCaptureService.cancelVisualFrame(); marker.clear() }

    // Checks two separately requested fresh frames; mismatch, timeout, lock or orientation change
    // stops without a tap.
    fun start(macro: JSONObject, dryRun: Boolean, finished: (String) -> Unit) {
        cancel()
        val request = generation
        tapDispatched = false
        imageStep = macro.optJSONArray("steps")?.optJSONObject(0)?.takeIf { it.optString("kind") == ImageTapAction.KIND }
        val step = imageStep
        imageAspect = if (step == null) 2.0 else step.optDouble("width") / step.optDouble("height")
        val deadline = SystemClock.elapsedRealtime() + 8000
        fun result(message: String) {
            if (request != generation) return
            cancel()
            service.getSharedPreferences("remoteMacros", 0).edit().putString("projectionImageResult", message).apply()
            finished(message)
        }
        if (step != null && !step.optBoolean("positionConfirmed")) { result("Place and confirm the tap on the image first."); return }
        if (!PreviewCaptureService.active) { result("Enable Watch screen capture before using image matching."); return }
        if (step == null && !dryRun && !externalDisplay()) { result("Connect the external display and open Wired Projection first. No tap sent."); return }
        main.postDelayed({ result(if (tapDispatched) "Tap completion was not confirmed. It was not retried."
            else "Image match timed out. No tap sent; check the dialog, orientation and capture permission.") }, 8000)
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val reference = runCatching {
                val bitmap = if (step == null) ProjectionImageAction.load(service, Uri.parse(macro.optString("overlayUri")))
                    else service.contentResolver.openInputStream(Uri.parse(macro.optString("overlayUri"))).use {
                        android.graphics.BitmapFactory.decodeStream(it) ?: error("Reference unavailable")
                    }
                try { if (step == null) ProjectionImageAction.sample(bitmap) else ImageTapAction.sample(bitmap) } finally { bitmap.recycle() }
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

    // Samples only while this explicit request is active; all pixel comparison happens on the
    // capture worker.
    private fun poll(reference: IntArray, request: Int, deadline: Long, consecutive: Int, dryRun: Boolean,
        result: (String) -> Unit) {
        if (request != generation || SystemClock.elapsedRealtime() >= deadline) return
        if (!unlocked()) { result("Unlock the phone first. No tap sent."); return }
        val requestedRotation = rotation()
        val requestedAt = SystemClock.elapsedRealtime()
        if (!PreviewCaptureService.requestVisualFrame { bitmap ->
            val sample = runCatching {
                require(kotlin.math.abs(bitmap.width.toDouble() / bitmap.height / imageAspect - 1.0) < .02)
                if (imageStep == null) ProjectionImageAction.sample(bitmap) else ImageTapAction.sample(bitmap)
            }.getOrNull()
            val matches = sample != null && if (imageStep == null) ProjectionImageMatch.matches(reference, sample)
                else ImageTapMatch.matches(reference, sample)
            main.post {
                if (request != generation || SystemClock.elapsedRealtime() >= deadline) return@post
                if (requestedRotation != rotation() || !unlocked()) { result("Screen changed or locked. No tap sent."); return@post }
                val stable = if (matches) consecutive + 1 else 0
                if (stable >= 2 && SystemClock.elapsedRealtime() - requestedAt < 500) {
                    if (dryRun) { result("Match verified twice. Test only: no tap sent."); return@post }
                    if (imageStep == null && !externalDisplay()) { result("External display disconnected. No tap sent."); return@post }
                    val size = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    service.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(size)
                    val point = imageStep?.optJSONArray("points")?.optJSONArray(0)
                    val x = point?.optDouble(0) ?: ProjectionImageAction.TAP_X.toDouble()
                    val y = point?.optDouble(1) ?: ProjectionImageAction.TAP_Y.toDouble()
                    if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) {
                        result("Invalid tap position. No tap sent."); return@post
                    }
                    val px = (size.widthPixels * x).toFloat(); val py = (size.heightPixels * y).toFloat()
                    val path = Path().apply { moveTo(px, py) }
                    if (service.getSharedPreferences("remoteMacros", 0).getBoolean("showPlaybackTaps", false)) marker.flash(px, py)
                    val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
                    // Dispatch once. Neither cancellation nor uncertain completion is ever retried.
                    tapDispatched = true
                    val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            val completedHere = request == generation
                            result(if (imageStep == null) "Matched Wired Projection and sent one Agree tap." else "Matched reference and sent one tap.")
                            if (completedHere && service.getSharedPreferences("remoteMacros", 0).getBoolean("showPlaybackTaps", false)) marker.flash(px, py)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) { result("Android cancelled the tap. It was not retried.") }
                    }, main)
                    if (!accepted) result("Android did not accept the tap. It was not retried.")
                } else main.postDelayed({ poll(reference, request, deadline, stable, dryRun, result) }, 250)
            }
        }) result("Screen capture is unavailable. Use Repair previews first.")
    }

    // Requires a public presentation display, excluding our private capture display; match-only
    // tests need none.
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
