package app.snapplication.launcher

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import java.io.File
import java.io.ByteArrayOutputStream

/** Keeps one local frame per Read/Watch app after explicit screen-sharing consent; MainActivity owns setup and ExternalPreview displays it. */
class PreviewCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("Preview frames")
    private lateinit var handler: Handler
    private var lastFrame = 0L
    @Volatile private var stopped = false
    private var starting = false
    private var sessionEpoch = 0L
    private var captureWidth = 0
    private var captureHeight = 0
    private var eligibleSince = Long.MIN_VALUE
    /** Requests one fresh frame after eligibility settles, including paused video and static book pages. */
    private val eligibilityRefresh = object : Runnable {
        override fun run() {
            if (stopped) return
            val target = RemoteActionService.capturePackage
            val since = RemoteActionService.capturePackageSinceNanos
            val state = LauncherState(this@PreviewCaptureService)
            val selected = state.lastApp()?.let { ComponentName.unflattenFromString(it)?.packageName }
            if (target != null && (state.followMediaApps || target == selected) && since != eligibleSince &&
                SystemClock.elapsedRealtimeNanos() - since >= 500_000_000L) refreshEligibleSurface(since)
            handler.postDelayed(this, 600)
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        /** New secondary displays do not change the captured default display. */
        override fun onDisplayAdded(displayId: Int) {}
        /** Removed secondary displays do not change this projection session. */
        override fun onDisplayRemoved(displayId: Int) {}
        /** Older Android versions report rotation through the default display rather than projection callbacks. */
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY && Build.VERSION.SDK_INT < 34) {
                val metrics = displayMetrics()
                resizeCapture(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            }
        }
    }

    /** Starts the capture worker; no projection begins until the consent result arrives. */
    override fun onCreate() {
        super.onCreate(); thread.start(); handler = Handler(thread.looper)
        sessionEpoch = synchronized(cacheLock) { cacheEpoch }
    }

    /** Starts a visible, user-stoppable capture session; Android never restarts it without new consent. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopped = true; stopSelf(); return START_NOT_STICKY }
        if (starting) return START_NOT_STICKY
        val consent = intent?.getParcelableExtra<Intent>("consent")
        if (consent == null) { stopSelf(); return START_NOT_STICKY }
        starting = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("preview", "Last-frame preview", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 41, Intent(this, PreviewCaptureService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "preview").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Snapplication preview active")
            .setContentText("Only your selected Read/Watch app is saved locally. Tap Stop to end.")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(41, notification)
        active = true
        handler.post { runCatching {
            if (stopped || synchronized(cacheLock) { sessionEpoch != cacheEpoch }) { stopSelf(); return@runCatching }
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, consent)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                /** Releases capture immediately when Android or the user revokes screen sharing. */
                override fun onStop() { stopped = true; stopSelf() }
                /** Resizes the same virtual display for rotation and Android 14 app-only capture bounds. */
                override fun onCapturedContentResize(width: Int, height: Int) {
                    resizeCapture(width, height, displayMetrics().densityDpi)
                }
            }, handler)
            val metrics = displayMetrics()
            val ratio = minOf(1f, 960f / maxOf(metrics.widthPixels, metrics.heightPixels))
            val width = (metrics.widthPixels * ratio).toInt().coerceAtLeast(1)
            val height = (metrics.heightPixels * ratio).toInt().coerceAtLeast(1)
            captureWidth = width; captureHeight = height
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader!!.setOnImageAvailableListener({ saveEligibleFrame(it) }, handler)
            display = projection!!.createVirtualDisplay("Selected app preview", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
            handler.post(eligibilityRefresh)
        }.onFailure { stopped = true; stopSelf() } }
        return START_NOT_STICKY
    }

    /** Reads default-screen pixel bounds for initial capture and pre-Android 14 rotation. */
    @Suppress("DEPRECATION")
    private fun displayMetrics(): android.util.DisplayMetrics = android.util.DisplayMetrics().also {
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(it)
    }

    /** Replaces only the image surface and dimensions; the consent token creates one virtual display only. */
    private fun resizeCapture(sourceWidth: Int, sourceHeight: Int, density: Int) {
        val current = display ?: return
        if (stopped || sourceWidth <= 0 || sourceHeight <= 0) return
        val ratio = minOf(1f, 960f / maxOf(sourceWidth, sourceHeight))
        val width = (sourceWidth * ratio).toInt().coerceAtLeast(1)
        val height = (sourceHeight * ratio).toInt().coerceAtLeast(1)
        if (width == captureWidth && height == captureHeight) return
        runCatching {
            val replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            try {
                replacement.setOnImageAvailableListener({ saveEligibleFrame(it) }, handler)
                current.resize(width, height, density)
                current.surface = replacement.surface
            } catch (error: Exception) { replacement.close(); throw error }
            val previous = reader
            reader = replacement; captureWidth = width; captureHeight = height
            eligibleSince = Long.MIN_VALUE
            previous?.close()
        }.onFailure { stopped = true; stopSelf() }
    }

    /** Replaces the buffer queue after a foreground eligibility change. A new surface cannot deliver
     * images queued while another app or player menu was visible; image timestamps need no clock assumption. */
    private fun refreshEligibleSurface(since: Long) {
        val current = display ?: return
        val previous = reader ?: return
        val replacement = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        runCatching {
            replacement.setOnImageAvailableListener({ saveEligibleFrame(it) }, handler)
            current.surface = replacement.surface
            reader = replacement
            eligibleSince = since
            previous.setOnImageAvailableListener(null, null)
            // The caller still owns one acquired image; let its use block close before closing its reader.
            handler.post { previous.close() }
        }.onFailure { replacement.close(); stopped = true; stopSelf() }
    }

    /** Discards all other apps, system windows and locked screens before touching pixel data. */
    private fun saveEligibleFrame(source: ImageReader) {
        if (source !== reader || stopped) return
        val frame = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        frame.use {
            val now = SystemClock.elapsedRealtimeNanos()
            if (stopped || now - lastFrame < 750_000_000L) return
            val power = getSystemService(PowerManager::class.java)
            if (!power.isInteractive || getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
            val target = RemoteActionService.capturePackage ?: return
            val since = RemoteActionService.capturePackageSinceNanos
            if (now - since < 500_000_000L) return
            val state = LauncherState(this)
            val mode = state.mode
            val chosen = state.lastApp()?.let { ComponentName.unflattenFromString(it)?.packageName }
            if ((!state.followMediaApps && target != chosen) || target == packageName) return
            val entry = packageManager.getLaunchIntentForPackage(target)?.component ?: return
            if (eligibleSince != since) { refreshEligibleSurface(since); return }
            lastFrame = now
            var padded: Bitmap? = null
            var bitmap: Bitmap? = null
            runCatching {
                val plane = frame.planes[0]
                val paddedWidth = plane.rowStride / plane.pixelStride
                padded = Bitmap.createBitmap(paddedWidth, frame.height, Bitmap.Config.ARGB_8888)
                padded!!.copyPixelsFromBuffer(plane.buffer)
                val safe = RemoteActionService.captureBounds ?: return@runCatching
                val metrics = displayMetrics()
                val left = (safe.left.toFloat() * frame.width / metrics.widthPixels).toInt().coerceIn(0, frame.width - 1)
                val top = (safe.top.toFloat() * frame.height / metrics.heightPixels).toInt().coerceIn(0, frame.height - 1)
                val right = (safe.right.toFloat() * frame.width / metrics.widthPixels).toInt().coerceIn(left + 1, frame.width)
                val bottom = (safe.bottom.toFloat() * frame.height / metrics.heightPixels).toInt().coerceIn(top + 1, frame.height)
                bitmap = Bitmap.createBitmap(padded!!, left, top, right - left, bottom - top)
                // Recheck foreground after copying so a transition cannot save an unrelated window.
                val bytes = ByteArrayOutputStream().use { out ->
                    check(bitmap!!.compress(Bitmap.CompressFormat.JPEG, 85, out)); out.toByteArray()
                }
                synchronized(cacheLock) {
                    val latest = LauncherState(this)
                    if (stopped || sessionEpoch != cacheEpoch || !power.isInteractive ||
                        getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                        RemoteActionService.capturePackage != target || RemoteActionService.capturePackageSinceNanos != since ||
                        latest.mode != mode || latest.viewerSource != "App" ||
                        (!latest.followMediaApps && latest.lastApp()?.let { ComponentName.unflattenFromString(it)?.packageName } != target)) return@synchronized
                    val file = android.util.AtomicFile(frameFile(this, mode, target))
                    val out = file.startWrite()
                    try { out.write(bytes); file.finishWrite(out) }
                    catch (error: Exception) { file.failWrite(out); throw error }
                    getSharedPreferences("externalPreview", MODE_PRIVATE).edit()
                        .putLong("capturedAt:$mode:$target", System.currentTimeMillis())
                        .putInt("cleanPolicy:$mode:$target", 2).commit()
                    // Home reads the identity under this same lock. A target changes only after its
                    // image exists; retaining each app's frame also makes manual switching reliable.
                    if (latest.followMediaApps) latest.rememberApp(entry.flattenToString())
                }
            }
            if (bitmap !== padded) bitmap?.recycle()
            padded?.recycle()
        }
    }

    /** Stops projection and discards queued images without deleting the intentionally saved last frame. */
    override fun onDestroy() {
        active = false; stopped = true
        handler.removeCallbacks(eligibilityRefresh)
        handler.post {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
            display?.release(); reader?.close(); projection?.stop(); thread.quitSafely()
        }
        super.onDestroy()
    }

    /** Capture is started by an explicit intent and exposes no binder interface. */
    override fun onBind(intent: Intent?): IBinder? = null

    /** Reports only the current process's live session; never persists consent tokens. */
    companion object {
        @Volatile var active = false; private set
        internal val cacheLock = Any()
        internal var cacheEpoch = 0L

        /** Names a private per-app frame without interpreting package names as paths. */
        internal fun frameFile(context: Context, mode: String, owner: String): File =
            File(context.filesDir, "preview-${if (mode == "Read") "Read" else "Watch"}-${owner.replace(Regex("[^A-Za-z0-9_.]"), "_")}.jpg")

        /** Cancels every pending cache write before removing images, AtomicFile sidecars and associated identities. */
        fun clearSaved(context: Context) {
            synchronized(cacheLock) {
                cacheEpoch++
                context.stopService(Intent(context, PreviewCaptureService::class.java))
                for (mode in listOf("Read", "Watch")) {
                    android.util.AtomicFile(File(context.filesDir, "preview-$mode.jpg")).delete()
                    android.util.AtomicFile(File(context.filesDir, "preview-art-$mode.jpg")).delete()
                }
                context.filesDir.listFiles()?.filter { it.name.startsWith("preview-Read-") || it.name.startsWith("preview-Watch-") }
                    ?.forEach { android.util.AtomicFile(it).delete() }
                context.getSharedPreferences("externalPreview", Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }
}
