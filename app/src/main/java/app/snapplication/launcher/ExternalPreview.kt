package app.snapplication.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/*
 * Presents captured frames or clearly labelled session artwork for MainActivity; uses LauncherState and
 * MediaAccessService without granting permissions.
 *
 * Related classes: MainActivity, LauncherState, PreviewCaptureService, MediaAccessService and RemoteActionService.
 * UI: Centre frame/artwork, fit behaviour and play/previous/next controls for an external app.
 * Edit or extend: Edit preview presentation and resume routing here. New content modes need their own capture
 * rules; this class cannot own another app buffer.
 */
class ExternalPreview(private val activity: Activity, private val state: LauncherState) {
    private val mode = state.mode
    private val component = synchronized(PreviewCaptureService.cacheLock) { state.lastApp()?.let(ComponentName::unflattenFromString) }
    private val packageName = component?.packageName
    private val ui = LauncherUi(activity, state, state.useWallpaper)
    private val prefs = activity.getSharedPreferences("externalPreview", Context.MODE_PRIVATE)
    private val sessions = activity.getSystemService(MediaSessionManager::class.java)
    private val access = ComponentName(activity, MediaAccessService::class.java)
    private val epoch = synchronized(PreviewCaptureService.cacheLock) { PreviewCaptureService.cacheEpoch }

    // Builds the saved-frame surface with visible transport icons; tapping the image resumes its app.
    fun build(onResumeCapture: () -> Unit, onChoose: () -> Unit): View {
        val panel = FrameLayout(activity)
        val image = ImageView(activity).apply {
            scaleType = when (state.contentScale) {
                "Fill" -> ImageView.ScaleType.CENTER_CROP
                "Stretch" -> ImageView.ScaleType.FIT_XY
                else -> ImageView.ScaleType.FIT_CENTER
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        panel.addView(image, FrameLayout.LayoutParams(-1, -1))
        val title = ui.label(if (component == null) (if (mode == "Read") "Open a book in your reading app" else "Open a video in your player") else
            if (requiresCleanFrame()) "Watch a video to save a clean preview" else "No saved preview yet", 16f)
        panel.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val reveal = ui.button("Show preview controls") { }.apply {
            text = ""
            contentDescription = "Show preview controls"
            ui.flatFocus(this, android.graphics.Color.TRANSPARENT, outline = false)
        }
        panel.addView(reveal, FrameLayout.LayoutParams(-1, -1))
        val controls = ui.column(true).apply { setBackgroundColor(0x99101010.toInt()) }
        val previous = ui.button("⏮") { skip(PlaybackState.ACTION_SKIP_TO_PREVIOUS) }
        val play = ui.button(if (mode == "Read") "↗" else "▶") { openApp(resumePlayback = true) }.apply {
            contentDescription = if (mode == "Read") "Continue reading in app" else "Play in app"; textSize = 25f
        }
        val next = ui.button("⏭") { skip(PlaybackState.ACTION_SKIP_TO_NEXT) }
        listOf(previous, play, next).forEach {
            controls.addView(it, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
        }
        if (mode == "Read") { previous.visibility = View.GONE; next.visibility = View.GONE }
        panel.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val setup = ui.button("Enable page previews") { onResumeCapture() }.apply { visibility = View.GONE }
        panel.addView(setup, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val actions = active()?.playbackState?.actions ?: 0L
        previous.isEnabled = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        next.isEnabled = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        previous.contentDescription = if (previous.isEnabled) "Previous episode in app" else "Previous unavailable: app does not provide this control or media access is off"
        next.contentDescription = if (next.isEnabled) "Next episode in app" else "Next unavailable: app does not provide this control or media access is off"
        listOf(previous, play, next).forEach { button ->
            ui.flatFocus(button, android.graphics.Color.TRANSPARENT, outline = false)
            button.setTextColor(android.graphics.Color.WHITE)
            button.alpha = if (button.isEnabled) 1f else .35f
        }
        reveal.contentDescription = "Open preview app"
        reveal.setOnClickListener { if (component == null) onChoose() else openApp(resumePlayback = true) }
        // Capture status lives in settings; the saved page/frame stays unobstructed on Home.
        // Disk decoding and artwork compression run away from input/rendering; detaching cancels display delivery.
        val executor = Executors.newSingleThreadExecutor()
        var detached = false
        var started = false
        var displayedBitmap: Bitmap? = null
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            // Loads a bounded preview once this newly built Home surface becomes visible.
            override fun onViewAttachedToWindow(v: View) {
                if (started) return
                started = true
                executor.execute {
                    val result = runCatching { loadImage() }.getOrNull()
                    activity.runOnUiThread {
                        if (detached || activity.isDestroyed || synchronized(PreviewCaptureService.cacheLock) { epoch != PreviewCaptureService.cacheEpoch }) result?.first?.recycle()
                        else if (result != null) {
                            displayedBitmap = result.first
                            image.setImageBitmap(result.first)
                            title.text = result.second
                            title.visibility = if (result.second == "Last captured frame") View.GONE else View.VISIBLE
                            image.contentDescription = result.second
                            reveal.contentDescription = "${result.second}. Open preview app"
                        } else if (mode == "Read" && (!state.readPagePreviews || RemoteActionService.instance == null)) {
                            title.text = "Enable page previews, then open a book in your reader"
                            setup.visibility = View.VISIBLE
                        }
                    }
                }
            }
            // Stops work belonging to the departed Home screen and releases its image reference.
            override fun onViewDetachedFromWindow(v: View) {
                detached = true
                executor.shutdownNow()
                image.setImageDrawable(null)
                displayedBitmap?.recycle(); displayedBitmap = null
            }
        })
        return panel
    }

    // Finds only the selected app's live session; revoked or absent notification access produces no session.
    private fun active(): MediaController? = if (mode == "Read") null else runCatching {
        sessions?.getActiveSessions(access)?.firstOrNull { it.packageName == packageName }
    }.getOrNull()

    // Old Cloudstream screenshots may contain its catalogue; only the clean-player policy can replace them.
    private fun requiresCleanFrame(): Boolean = mode == "Watch" &&
        (packageName?.startsWith("com.lagradost.cloudstream") == true ||
            packageName in setOf("xyz.Quickdev.Animiru.mi", "com.futo.platformplayer", "com.google.android.youtube"))

    // Reads only this app's frame; legacy caches remain available until replaced or explicitly cleared.
    private fun savedFrame(): File? {
        val owner = packageName ?: return null
        val current = PreviewCaptureService.frameFile(activity, mode, owner)
        if (prefs.getLong("capturedAt:$mode:$owner", 0L) > 0 &&
            prefs.getInt("cleanPolicy:$mode:$owner", 0) >= 2 && current.isFile) return current
        // Earlier Read captures did not verify a document surface and may show the app's library.
        if (mode == "Read") return null
        val required = if (packageName in setOf("com.futo.platformplayer", "com.google.android.youtube")) 2 else 1
        return File(activity.filesDir, "preview-$mode.jpg").takeIf {
            prefs.getString("package:$mode", null) == owner && prefs.getLong("capturedAt:$mode", 0L) > 0 &&
                (!requiresCleanFrame() || prefs.getInt("cleanPolicy:$mode", 0) >= required)
        }
    }

    // Gives explicit captured frames precedence, then caches bounded app-supplied artwork separately.
    private fun loadImage(): Pair<Bitmap, String>? {
        if (packageName == null) return null
        synchronized(PreviewCaptureService.cacheLock) {
            if (epoch != PreviewCaptureService.cacheEpoch || Thread.currentThread().isInterrupted) return null
            savedFrame()?.let { file -> decode(file)?.let { return it to "Last captured frame" } }
        }
        val metadata = active()?.metadata
        val artwork = metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val file = File(activity.filesDir, "preview-art-$mode.jpg")
        if (artwork != null && artwork.width > 0 && artwork.height > 0) {
            val ratio = minOf(1f, 1024f / maxOf(artwork.width, artwork.height))
            val bounded = Bitmap.createScaledBitmap(artwork, maxOf(1, (artwork.width * ratio).toInt()), maxOf(1, (artwork.height * ratio).toInt()), true)
            try {
                val bytes = ByteArrayOutputStream().use { out ->
                    check(bounded.compress(Bitmap.CompressFormat.JPEG, 85, out)); out.toByteArray()
                }
                synchronized(PreviewCaptureService.cacheLock) {
                    if (epoch != PreviewCaptureService.cacheEpoch || Thread.currentThread().isInterrupted) return null
                    val atomic = android.util.AtomicFile(file)
                    val out = atomic.startWrite()
                    try { out.write(bytes); atomic.finishWrite(out) }
                    catch (error: Exception) { atomic.failWrite(out); throw error }
                    prefs.edit().putString("artworkPackage:$mode", packageName)
                        .putString("artworkTitle:$mode", metadata?.description?.title?.toString()?.take(200).orEmpty()).commit()
                }
            } finally { if (bounded !== artwork) bounded.recycle() }
        }
        return synchronized(PreviewCaptureService.cacheLock) {
            if (epoch != PreviewCaptureService.cacheEpoch || Thread.currentThread().isInterrupted) return null
            // Capture may have arrived while session artwork was being fetched or compressed.
            savedFrame()?.let { file -> decode(file)?.let { return it to "Last captured frame" } }
            if (prefs.getString("artworkPackage:$mode", null) != packageName) return null
            val name = prefs.getString("artworkTitle:$mode", "").orEmpty()
            decode(file)?.let { it to (if (name.isBlank()) "App artwork" else "$name · App artwork") }
        }
    }

    // Limits decoded image dimensions and rejects missing, corrupt or unexpectedly large cache files.
    private fun decode(file: File): Bitmap? {
        if (!file.isFile || file.length() !in 1..16_777_216L) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.path, options)
    }

    // Opens the app first; explicit Watch play requests can then resume its own advertised paused session.
    private fun openApp(resumePlayback: Boolean = false) {
        if (mode == "Read" && packageName == MeebookReaderBridge.OWNER &&
            MeebookReaderBridge.open(activity, prefs.getString("resumeUri:Read:$packageName", null))) return
        if (state.landscapeApps && activity.window.decorView.width > activity.window.decorView.height) {
            packageName?.let { RemoteActionService.instance?.prepareLandscape(it) }
        }
        val pending = runCatching { active()?.sessionActivity }.getOrNull()
        val opened = if (pending != null && runCatching { pending.send() }.isSuccess) true else {
            val selected = component ?: return
            runCatching {
                // Bring the existing task forward without asking Android to reset its activity stack.
                activity.startActivity(Intent.makeMainActivity(selected).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        }
        if (!opened) {
            message("This app is unavailable. Choose another app in Settings.")
            return
        }
        if (!resumePlayback || mode != "Watch") return
        packageName?.let { owner -> ExternalWatchPlayback.resume(activity, owner) { message(it) } }
    }

    // Revalidates advertised episode commands before dispatching, then brings the owning app forward.
    private fun skip(action: Long) {
        val controller = active()
        if (controller == null || (controller.playbackState?.actions ?: 0L) and action == 0L) {
            message("This app does not currently provide that episode control.")
            return
        }
        runCatching {
            if (action == PlaybackState.ACTION_SKIP_TO_PREVIOUS) controller.transportControls.skipToPrevious()
            else controller.transportControls.skipToNext()
            openApp(resumePlayback = false)
        }.onFailure { message("The playback session ended. Open the app to continue.") }
    }

    // Reports unavailable app/session actions without exposing media addresses or private metadata.
    private fun message(value: String) { Toast.makeText(activity, value, Toast.LENGTH_LONG).show() }
}
