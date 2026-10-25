package app.snapplication.launcher

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/*
Saves one small reading-page screenshot after a reader announces a change. There is no projection or
virtual display. Most readers use page-change events; Meebook Read needs an occasional still check
because its native canvas does not announce page turns. Used by or used with: RemoteActionService
validates the foreground page; ReaderSurface finds its bounds; LauncherState owns the opt-in;
ExternalPreview reads the shared local cache. Screen controls: No overlay or video notification.
MainActivity exposes the page-preview switch. Changes belong here: Adjust event settling and image
size here. Add app page recognition in ReaderSurface instead. Keep compatibility checks limited to
the visible reading page.

This class is a helper and does not inherit from another launcher class.

main schedules a settled page check and worker compares and saves pixels away from the screen
thread. generation and closed cancel outdated work. lastPixels and lastPixelOwner help avoid saving
an unchanged page. RemoteActionService owns this helper and calls changed after reader events.
capture takes one permitted screenshot; eligible checks the page again before saving it for
ExternalPreview.
*/
@RequiresApi(30)
class ReadingSnapshots(private val service: RemoteActionService) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var lastRequest = 0L
    private val pending = Runnable { capture() }
    private val compatibilityCheck = Runnable { capture() }
    private var lastPixels: IntArray? = null
    private var lastPixelOwner: String? = null

    // Coalesces reader changes and cancels pending work immediately when the page is obscured.
    fun changed(event: AccessibilityEvent? = null) {
        val state = LauncherState(service)
        if (state.mode != "Read" || !state.readPagePreviews || state.viewerSource != "App" ||
            RemoteActionService.capturePackage == null) {
            generation++; main.removeCallbacks(pending); main.removeCallbacks(compatibilityCheck); return
        }
        if (event != null && event.packageName?.toString() != RemoteActionService.capturePackage &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        generation++
        main.removeCallbacks(pending)
        main.removeCallbacks(compatibilityCheck)
        main.postDelayed(pending, maxOf(800L, 2000L - (SystemClock.uptimeMillis() - lastRequest)))
    }

    // Requests a single screenshot after rechecking the current window, never a recording session.
    private fun capture() {
        service.refreshReadingEligibility()
        val owner = RemoteActionService.capturePackage ?: return
        val bounds = RemoteActionService.captureBounds?.let(::Rect) ?: return
        val since = RemoteActionService.capturePackageSinceNanos
        val readerActivity = RemoteActionService.captureActivity
        val request = generation
        val epoch = synchronized(PreviewCaptureService.cacheLock) { PreviewCaptureService.cacheEpoch }
        if (!eligible(owner, since, request, epoch)) return
        val entry = service.packageManager.getLaunchIntentForPackage(owner)?.component ?: return
        lastRequest = SystemClock.uptimeMillis()
        try { service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                // Revalidates after Android responds, then compresses the limited image away from
                // input.
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    service.refreshReadingEligibility()
                    if (!eligible(owner, since, request, epoch)) { result.hardwareBuffer.close(); return }
                    scheduleCompatibilityCheck(owner)
                    worker.execute {
                        val buffer = result.hardwareBuffer
                        var wrapped: Bitmap? = null
                        var software: Bitmap? = null
                        var cropped: Bitmap? = null
                        var scaled: Bitmap? = null
                        try {
                            if (!eligible(owner, since, request, epoch)) return@execute
                            wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return@execute
                            software = wrapped!!.copy(Bitmap.Config.ARGB_8888, false) ?: return@execute
                            val crop = Rect(bounds)
                            if (!crop.intersect(0, 0, software!!.width, software!!.height)) return@execute
                            cropped = Bitmap.createBitmap(software!!, crop.left, crop.top, crop.width(), crop.height())
                            val ratio = minOf(1f, 960f / maxOf(crop.width(), crop.height()))
                            scaled = Bitmap.createScaledBitmap(cropped!!, maxOf(1, (crop.width() * ratio).toInt()),
                                maxOf(1, (crop.height() * ratio).toInt()), true)
                            val pixels = IntArray(scaled!!.width * scaled!!.height)
                            scaled!!.getPixels(pixels, 0, scaled!!.width, 0, 0, scaled!!.width, scaled!!.height)
                            val unchanged = owner == lastPixelOwner && lastPixels?.contentEquals(pixels) == true
                            val bookUri = if (owner == MeebookReaderBridge.OWNER) service.readingBookUri(readerActivity) else null
                            if (unchanged && PreviewCaptureService.frameFile(service, "Read", owner).isFile) {
                                synchronized(PreviewCaptureService.cacheLock) {
                                    if (eligible(owner, since, request, epoch)) {
                                        if (LauncherState(service).followMediaApps) LauncherState(service).rememberApp(entry.flattenToString())
                                        saveBookIdentity(owner, bookUri)
                                    }
                                }
                                return@execute
                            }
                            val bytes = ByteArrayOutputStream().use { out ->
                                check(scaled!!.compress(Bitmap.CompressFormat.JPEG, 85, out)); out.toByteArray()
                            }
                            synchronized(PreviewCaptureService.cacheLock) {
                                if (!eligible(owner, since, request, epoch)) return@synchronized
                                val file = android.util.AtomicFile(PreviewCaptureService.frameFile(service, "Read", owner))
                                val stream = file.startWrite()
                                try { stream.write(bytes); file.finishWrite(stream) }
                                catch (error: Exception) { file.failWrite(stream); throw error }
                                service.getSharedPreferences("externalPreview", android.content.Context.MODE_PRIVATE).edit()
                                    .putLong("capturedAt:Read:$owner", System.currentTimeMillis())
                                    .putInt("cleanPolicy:Read:$owner", 2).apply()
                                val state = LauncherState(service)
                                if (state.followMediaApps) state.rememberApp(entry.flattenToString())
                                saveBookIdentity(owner, bookUri)
                                lastPixels = pixels; lastPixelOwner = owner
                                lastError = null
                            }
                        } catch (_: Exception) {
                            // Keep the last good page after a failed write; surface the failure in
                            // settings.
                            lastError = "The page could not be saved. Check available device storage."
                        } finally {
                            listOfNotNull(scaled, cropped, software, wrapped).distinct().forEach { it.recycle() }
                            buffer.close()
                        }
                    }
                }

                // Does not retry indefinitely or silently fall back to energy-intensive screen
                // sharing.
                override fun onFailure(errorCode: Int) {
                    lastError = if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS)
                        "Reconnect Snapplication support in Android accessibility settings."
                    else "Android could not capture this page (code $errorCode). The last saved page is kept."
                    // A failed or blocked screenshot stops compatibility checking until another
                    // event.
                }
            }) } catch (_: IllegalStateException) {
            lastError = "Reconnect Snapplication support in Android accessibility settings."
        } catch (_: SecurityException) {
            lastError = "Android has not granted still-screenshot access. Reconnect accessibility support."
        }
    }

    // Checks only Meebook's event-silent canvas every five seconds; leaving the page cancels it.
    private fun scheduleCompatibilityCheck(owner: String) {
        main.removeCallbacks(compatibilityCheck)
        if (owner == "com.haoqing.app.hqreader" && !closed) main.postDelayed(compatibilityCheck, 5000)
    }

    // Associates a public book URI with this exact page; absent identity removes any older book
    // link.
    private fun saveBookIdentity(owner: String, uri: String?) {
        val prefs = service.getSharedPreferences("externalPreview", android.content.Context.MODE_PRIVATE)
        val key = "resumeUri:Read:$owner"
        if (prefs.getString(key, null) != uri) prefs.edit().putString(key, uri).apply()
    }

    // Rejects stale callbacks, locked screens, app changes and a preview switch turned off
    // mid-request.
    private fun eligible(owner: String, since: Long, request: Int, epoch: Long): Boolean {
        val state = LauncherState(service)
        return !closed && request == generation && state.mode == "Read" && state.readPagePreviews &&
            state.viewerSource == "App" && service.getSystemService(PowerManager::class.java).isInteractive &&
            !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            RemoteActionService.capturePackage == owner && RemoteActionService.capturePackageSinceNanos == since &&
            (state.followMediaApps || state.lastApp()?.let { ComponentName.unflattenFromString(it)?.packageName } == owner) &&
            synchronized(PreviewCaptureService.cacheLock) { PreviewCaptureService.cacheEpoch == epoch }
    }

    // Cancels queued callbacks and releases the worker when accessibility disconnects.
    fun close() {
        closed = true; generation++; main.removeCallbacks(pending); main.removeCallbacks(compatibilityCheck)
        worker.shutdown()
    }

    companion object {
        @Volatile var lastError: String? = null
            private set
    }
}
