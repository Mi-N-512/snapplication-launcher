package app.snapplication.launcher

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/*
 * Opt-in remote accessibility bridge used by MainActivity and RemoteControls. Records explicit, local, bounded
 * cross-app macros; never records typing, injects raw keys, or grants its own permissions.
 *
 * Related classes: MainActivity, RemoteControls, RemoteMappingPanel, TouchMacroRecorder, LandscapeLaunch and
 * PreviewCaptureService.
 * UI: Small recording/replay stop overlays and confirmation dialogs; remote setup lives in RemoteMappingPanel.
 * Edit or extend: Edit delivered-key actions, macro orchestration and foreground eligibility here. Keep
 * credential/permission exclusions and never grant this service its own access.
 */
class RemoteActionService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var readingSnapshots: ReadingSnapshots? = null
    private val meebookReader by lazy { MeebookReaderBridge(this) }
    private val controls by lazy { RemoteControls(this) }
    private val landscapeLaunch by lazy { LandscapeLaunch(this) }
    private val cloudStreamControls by lazy { CloudStreamControls(this) }

    // Uses the visible CloudStream player when its session has no exported Next transport action.
    fun nextCloudStreamEpisode(owner: String, isCurrent: () -> Boolean, result: (Boolean) -> Unit) {
        cloudStreamControls.next(owner, isCurrent, result)
    }

    // Receives an explicit app launch from landscape Home or its centre preview.
    fun prepareLandscape(target: String) {
        landscapeLaunch.begin(target)
        handler.postDelayed({ updateForeground() }, 8100)
    }

    // Releases the launch orientation immediately when its setting is disabled.
    fun cancelLandscape() { landscapeLaunch.clear() }
    // Avoids rotating a retained player when the launcher explicitly uses the same landscape layout.
    fun onLauncherResume(keepLandscape: Boolean) { landscapeLaunch.onLauncherResume(keepLandscape) }
    private var recordingName: String? = null
    private var recordingPackage: String? = null
    private var recordingStarted = false
    private var lastRecordedPackage: String? = null
    private var recordingSteps = JSONArray()
    private var lastRecordTime = 0L
    private var lastScrollKey = ""
    private var lastScrollTime = 0L
    private var touchRecorder: TouchMacroRecorder? = null
    private var ignoreClicksUntil = 0L
    private var stopButton: Button? = null
    private var confirmation: AlertDialog? = null
    private var replayStop: Button? = null
    private var runningMacro: String? = null
    private var replaySpeed = 1.0
    private var replayGeneration = 0
    private var lastCaptureWindowId: Int? = null
    private var activityPackage: String? = null
    private var activityClass: String? = null

    companion object {
        // Active user-enabled service, used by launcher settings without enabling accessibility itself.
        @Volatile var instance: RemoteActionService? = null
            private set
        // Foreground identity for remote macros; our recording overlay does not replace its target app.
        @Volatile var currentPackage: String? = null
            private set
        // Monotonic time of the last foreground package change.
        @Volatile var currentPackageSinceNanos: Long = 0L
            private set

        // Capture identity is stricter: overlaid, private-input and unverifiable windows publish null.
        @Volatile var captureBounds: Rect? = null
            private set
        @Volatile var capturePackage: String? = null
            private set
        @Volatile var captureActivity: String? = null
            private set
        // Capture consumers reject frames older than this privacy/window-state transition.
        @Volatile var capturePackageSinceNanos: Long = 0L
            private set

        // Returns local macro names for the settings screen and per-device mapping chooser.
        fun savedMacroNames(context: Context): List<String> = macros(context).keys().asSequence().sorted().toList()

        // Reads stored macro JSON defensively without exposing recorded labels in diagnostics.
        private fun macros(context: Context): JSONObject = runCatching {
            JSONObject(context.getSharedPreferences("remoteMacros", Context.MODE_PRIVATE).getString("macros", "{}")!!)
        }.getOrElse { JSONObject() }
    }

    // Registers the running instance; permission and service flags are supplied by Android settings/XML.
    override fun onServiceConnected() {
        instance = this
        updateForeground()
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            readingSnapshots = ReadingSnapshots(this)
            readingSnapshots?.changed()
        }
    }

    // Rechecks page privacy before an event-triggered still screenshot; does not schedule another one.
    fun refreshReadingEligibility() { updateForeground() }

    // Resolves only the captured reader window through its verified public book service.
    fun readingBookUri(readerActivity: String?): String? = meebookReader.bookUri(readerActivity)

    // Applies the page-preview switch without running a projection or polling timer.
    fun refreshReadingSnapshots() {
        updateForeground()
        if (android.os.Build.VERSION.SDK_INT >= 30) readingSnapshots?.changed()
    }

    // Records explicit clicks and app transitions across allowed apps; text input is never stored.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className?.toString()?.endsWith("Activity") == true) {
            activityPackage = event.packageName?.toString()
            activityClass = event.className?.toString()
        }
        updateForeground()
        if (android.os.Build.VERSION.SDK_INT >= 30) readingSnapshots?.changed(event)
        if (recordingName == null) return
        // Touch callbacks persist the mirrored gesture after completion. Accessibility echoes and
        // intermediate app switches must not insert semantic duplicates before that gesture.
        if (touchRecorder != null) return
        val active = currentPackage ?: return
        if (!recordablePackage(active)) return
        if (!recordingStarted) {
            if (!recordingPackage.isNullOrBlank() && active != recordingPackage) return
            recordingStarted = true
            recordingPackage = active
        }
        val root = rootInActiveWindow ?: return
        val sensitive = try { containsSensitiveInput(root, blockEditable = false) } finally { root.recycle() }
        if (sensitive) {
            // Keep the explicit recording session available, but never save private input actions.
            return
        }
        if (lastRecordedPackage != active) {
            // Launch steps become no-ops if a preceding click already opened the recorded app.
            appendRecordedStep(JSONObject().put("kind", if (active == packageName) "home" else "launch").put("package", active))
            lastRecordedPackage = active
        }
        if (recordingName == null || SystemClock.uptimeMillis() < ignoreClicksUntil || event?.packageName?.toString() != active) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            recordScroll(event, active)
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val node = event.source ?: return
        try {
            if (node.isPassword || node.isEditable || node.window?.let { window -> try { window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY } finally { window.recycle() } } == true) return
            val step = recordSelector(node) ?: return
            appendRecordedStep(step.put("kind", "click").put("package", active).put("action", AccessibilityNodeInfo.ACTION_CLICK))
        } finally { node.recycle() }
    }

    // Records one semantic scroll per event burst; no raw touch tracking or inferred coordinates.
    private fun recordScroll(event: AccessibilityEvent, owner: String) {
        if (android.os.Build.VERSION.SDK_INT < 28) return
        val delta = if (event.scrollDeltaY != 0) event.scrollDeltaY else event.scrollDeltaX
        if (delta == 0) return
        val node = event.source ?: return
        try {
            val id = node.viewIdResourceName?.takeIf { it.isNotBlank() } ?: return
            if (!node.isScrollable || node.isPassword || node.isEditable) return
            val action = if (delta > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.actionList.none { it.id == action }) return
            val now = SystemClock.uptimeMillis()
            val key = "$owner:$id:$action"
            val duplicate = key == lastScrollKey && now - lastScrollTime < 450
            lastScrollTime = now; lastScrollKey = key
            if (duplicate) return
            appendRecordedStep(JSONObject().put("kind", "scroll").put("package", owner).put("id", id)
                .put("action", action).put("guardClass", node.className?.toString().orEmpty())
                .put("guardParents", JSONArray(selectorParents(node))))
        } finally { node.recycle() }
    }

    // Adds a bounded action with its original delay and updates the visible recording control.
    private fun appendRecordedStep(step: JSONObject) {
        if (recordingName == null || !recordingStarted) return
        if (!step.has("delay")) step.put("delay", (SystemClock.uptimeMillis() - lastRecordTime).coerceIn(250, 2000))
        recordingSteps.put(step)
        lastRecordTime = SystemClock.uptimeMillis()
        stopButton?.text = "Stop recording (${recordingSteps.length()}/20)"
        if (recordingSteps.length() >= 20) stopRecording()
    }

    // Records delivered navigation keys; gestures that Android does not expose are not guessed.
    private fun recordNavigation(kind: String) {
        val active = currentPackage ?: return
        if (recordablePackage(active)) appendRecordedStep(JSONObject().put("kind", kind).put("package", active))
    }

    // Records explicit launcher navigation without executing it twice through Android global actions.
    fun recordLauncherNavigation(kind: String) {
        if (kind in setOf("home", "back")) recordNavigation(kind)
    }

    // Prefers stable click IDs; for unlabelled containers records a labelled descendant and its clickable ancestor.
    private fun recordSelector(node: AccessibilityNodeInfo): JSONObject? {
        val id = node.viewIdResourceName?.takeIf { it.isNotBlank() }
        fun labelled(current: AccessibilityNodeInfo, depth: Int): JSONObject? {
            if (depth > 8 || current.isPassword || current.isEditable) return null
            val label = (current.contentDescription ?: current.text)?.toString()?.takeIf { it.isNotBlank() && it.length <= 80 }
            if (label != null) return JSONObject().put("label", label).put("description", current.contentDescription != null)
                .put("ancestor", true).apply { current.viewIdResourceName?.let { put("id", it) } }
            for (index in 0 until current.childCount.coerceAtMost(50)) {
                val child = current.getChild(index) ?: continue
                val found = try { labelled(child, depth + 1) } finally { child.recycle() }
                if (found != null) return found
            }
            return null
        }
        val label = labelled(node, 0)
        if (node.isClickable && id != null) return JSONObject().put("id", id).apply {
            // Click events arrive after toggles change wording. Guard stable IDs by widget class instead.
            // A stable ID/class is not a guarantee that the app kept the same semantic action.
            node.className?.toString()?.takeIf { it.isNotBlank() }?.let { put("guardClass", it) }
            put("guardParents", JSONArray(selectorParents(node)))
        }
        return label
    }

    // Captures bounded parent IDs/classes without storing text, positions or private field values.
    private fun selectorParents(node: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        var parent = node.parent
        while (parent != null && result.size < 8) {
            val current = parent
            try {
                result.add("${current.viewIdResourceName.orEmpty()}|${current.className?.toString().orEmpty()}")
                parent = current.parent
            } finally { current.recycle() }
        }
        parent?.recycle()
        return result
    }

    // Guards new recordings by their container path; absent guards retain legacy replay behavior.
    private fun parentContextMatches(node: AccessibilityNodeInfo, step: JSONObject): Boolean {
        if (!step.has("guardParents")) return true
        val expected = step.optJSONArray("guardParents") ?: return false
        return MacroContext.matches((0 until expected.length()).map { expected.optString(it) }, selectorParents(node))
    }
    // Resolves the actual active window, while keeping macro and stricter capture eligibility separate.
    private fun updateForeground() {
        val root = rootInActiveWindow
        val visibleWindows = windows
        try {
            val activeWindow = visibleWindows.firstOrNull { it.id == root?.windowId }
            var next = root?.packageName?.toString()
            // A non-focusable recording control must not turn the macro's own app into a new target.
            if ((recordingName != null || replayStop != null) && next == packageName &&
                activeWindow?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                val underlying = visibleWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (it.type == AccessibilityWindowInfo.TYPE_SYSTEM && (it.isActive || it.isFocused)) }
                    .maxByOrNull { it.layer }?.root
                next = underlying?.packageName?.toString()
                underlying?.recycle()
            }
            if (next != currentPackage) {
                currentPackageSinceNanos = SystemClock.elapsedRealtimeNanos()
                currentPackage = next
            }
            landscapeLaunch.update(next, root)
            var capture: String? = null
            var nextBounds: Rect? = null
            if (root != null && activeWindow?.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                !containsSensitiveInput(root)) {
                val bounds = Rect().also { activeWindow.getBoundsInScreen(it) }
                val safe = Rect(bounds)
                var obscured = false
                visibleWindows.filter { it.id != activeWindow.id && it.layer > activeWindow.layer && !landscapeLaunch.ownsWindow(it.id) }.forEach { window ->
                    val overlay = Rect().also { window.getBoundsInScreen(it) }
                    if (Rect.intersects(bounds, overlay)) {
                        // Huawei exposes invisible edge gesture windows above fullscreen apps. Crop all
                        // their pixels rather than rejecting the whole app or capturing a system overlay.
                        val narrow = window.type == AccessibilityWindowInfo.TYPE_SYSTEM
                        when {
                            narrow && overlay.left <= bounds.left && overlay.width() <= bounds.width() / 10 -> safe.left = maxOf(safe.left, overlay.right)
                            narrow && overlay.right >= bounds.right && overlay.width() <= bounds.width() / 10 -> safe.right = minOf(safe.right, overlay.left)
                            narrow && overlay.top <= bounds.top && overlay.height() <= bounds.height() / 10 -> safe.top = maxOf(safe.top, overlay.bottom)
                            narrow && overlay.bottom >= bounds.bottom && overlay.height() <= bounds.height() / 10 -> safe.bottom = minOf(safe.bottom, overlay.top)
                            else -> obscured = true
                        }
                    }
                }
                if (!obscured && !safe.isEmpty) { capture = root.packageName?.toString(); nextBounds = safe }
            }
            val state = LauncherState(this)
            if (root != null && capture != null && state.viewerSource == "App") {
                val cloudstream = capture.startsWith("com.lagradost.cloudstream")
                val animiru = capture == "xyz.Quickdev.Animiru.mi"
                val grayjay = capture == "com.futo.platformplayer"
                val youtube = capture == "com.google.android.youtube"
                val playerId = when {
                    state.mode != "Watch" -> null
                    cloudstream -> "player_view"
                    youtube -> "watch_player"
                    grayjay -> "videodetail_player"
                    else -> null
                }
                val players = if (playerId != null) root.findAccessibilityNodeInfosByViewId("$capture:id/$playerId") else emptyList()
                val generic = if (state.mode == "Watch" && players.isEmpty() && !animiru) VideoSurface.find(this, root, capture) else null
                val player = players.firstOrNull { it.isVisibleToUser } ?: generic
                val video = player != null ||
                    (activityPackage == capture && activityClass?.endsWith(".PlayerActivity") == true)
                // A stopped projection must not strand Play on a different app. Each preview is still
                // loaded by its owner, so changing the resume target cannot reuse another app's image.
                if (state.mode == "Watch" && video && state.followMediaApps) {
                    val entry = packageManager.getLaunchIntentForPackage(capture)?.component
                    if (entry != null && state.lastApp() != entry.flattenToString()) {
                        synchronized(PreviewCaptureService.cacheLock) { state.rememberApp(entry.flattenToString()) }
                    }
                }
                // Grayjay retains its catalogue beneath the player. Inspect the player subtree only.
                val chrome = state.mode == "Watch" && hasPlayerChrome(if ((grayjay || youtube) && player != null) player else root)
                if ((grayjay || youtube || generic != null) && player != null) {
                    val playerBounds = Rect().also { player.getBoundsInScreen(it) }
                    nextBounds = nextBounds?.let { Rect(it).takeIf { rect -> rect.intersect(playerBounds) && !rect.isEmpty } }
                    if (nextBounds == null) capture = null
                }
                val readerBounds = if (state.mode == "Read") ReaderSurface.find(this, root,
                    activityClass.takeIf { activityPackage == capture }) else null
                if (state.mode == "Read") {
                    nextBounds = readerBounds?.let { page -> nextBounds?.let { Rect(it).takeIf { rect -> rect.intersect(page) && !rect.isEmpty } } }
                    if (nextBounds == null) capture = null
                }
                // Publish eligibility only. Capture commits the app identity together with a clean frame,
                // so entering another player's controls/catalogue cannot replace the saved resume target.
                if (state.mode == "Watch" && (!video || chrome)) {
                    capture = null; nextBounds = null
                }
                players.forEach { it.recycle() }
                generic?.recycle()
            } else {
                capture = null; nextBounds = null
            }
            val captureWindowId = if (capture == null) null else activeWindow?.id
            captureActivity = activityClass.takeIf { capture != null && activityPackage == capture }
            if (capture == MeebookReaderBridge.OWNER && state.mode == "Read" && state.readPagePreviews) meebookReader.connect()
            else meebookReader.close()
            if (capture != capturePackage || captureWindowId != lastCaptureWindowId || nextBounds != captureBounds) {
                lastCaptureWindowId = captureWindowId
                capturePackageSinceNanos = SystemClock.elapsedRealtimeNanos()
                captureBounds = nextBounds
                capturePackage = capture
            }
        } finally {
            root?.recycle()
            visibleWindows.forEach { it.recycle() }
        }
    }

    /* Keeps the last unobstructed Cloudstream picture when menus, transport controls or episode lists appear.
     * Subtitle subtrees belong to the watched content and are not treated as application chrome. */
    private fun hasPlayerChrome(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 40) return true
        if (!node.isVisibleToUser) return false
        val id = node.viewIdResourceName.orEmpty().lowercase()
        if (id.contains("subtitle") || id.contains("caption")) return false
        val kind = node.className?.toString().orEmpty()
        // Loading and volume/brightness indicators must not replace the last clean episode frame.
        if (kind.endsWith("ProgressBar") || kind.endsWith("ProgressIndicator") ||
            id.endsWith("/player_buffering") || id.endsWith("/exo_buffering")) return true
        if (!node.text.isNullOrBlank() || kind.endsWith("Button") || kind.endsWith("SeekBar") ||
            (node.isClickable && kind.endsWith("ImageView"))) return true
        for (index in 0 until node.childCount.coerceAtMost(300)) {
            val child = node.getChild(index) ?: continue
            val chrome = try { hasPlayerChrome(child, depth + 1) } finally { child.recycle() }
            if (chrome) return true
        }
        return false
    }

    // Handles only saved physical-key assignments outside the launcher; untouched keys pass through.
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (BuildConfig.FLAVOR == "ereader") return false
        if (event.action == KeyEvent.ACTION_UP && controls.hasHeldAssignment(event)) {
            controls.assignment(event) { false }
            return true
        }
        if (RemoteControls.learning || currentPackage == packageName) return false
        val device = event.device ?: return false
        // Learned hybrid remotes can report mouse sources for their buttons; the saved descriptor/key
        // is the authority. Avoid a full window-tree scan here: Android's key-filter deadline is short.
        if (device.isVirtual || event.keyCode in setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_UNKNOWN)) return false
        // Only text-sensitive shortcuts need a cross-process focus read. Navigation, locking,
        // unmapped keys and latched repeats/releases must not wait for another app's window tree.
        val action = controls.assignment(event) {
            val root = rootInActiveWindow
            try {
                root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { focused ->
                    try { focused.isEditable || focused.isPassword } finally { focused.recycle() }
                } ?: false
            } finally { root?.recycle() }
        }
        if (action == null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && event.keyCode == KeyEvent.KEYCODE_BACK) recordNavigation("back")
            return false
        }
        if (BuildConfig.DEBUG && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
            android.util.Log.d("SnappRemote", "Mapped ${KeyEvent.keyCodeToString(event.keyCode)} to $action")
        if ((event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) ||
            (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK && !event.isCanceled)) performMappedAction(action)
        return true
    }

    // Runs a saved action without injecting key events, avoiding feedback loops through this service.
    fun performMappedAction(action: String) {
        if (BuildConfig.FLAVOR == "ereader") return
        when (action) {
            "Home" -> { recordNavigation("home"); openRecordedApp(packageName) }
            "Back" -> { recordNavigation("back"); if (!performGlobalAction(GLOBAL_ACTION_BACK)) message("Android could not go back on this screen.") }
            "Record/stop macro" -> toggleRecording()
            "Stop action" -> finishReplay("Action stopped")
            "Screen off" -> {
                if (android.os.Build.VERSION.SDK_INT < 28 || !performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN))
                    message("Android could not lock the screen on this device.")
            }
            "Apps" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("openApps", true))
            "Play/pause" -> {
                val audio = getSystemService(AudioManager::class.java)
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            }
            "Up", "Down", "Left", "Right", "Select" -> moveFocus(action)
            else -> if (action.startsWith("Macro: ")) replay(action.removePrefix("Macro: "))
        }
    }

    // Moves accessibility/native focus where the target app exposes it; Select clicks the focused node.
    private fun moveFocus(action: String) {
        val root = rootInActiveWindow ?: return
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        try {
            if (action == "Select") { focused?.performAction(AccessibilityNodeInfo.ACTION_CLICK); return }
            val direction = when (action) {
                "Up" -> View.FOCUS_UP
                "Down" -> View.FOCUS_DOWN
                "Left" -> View.FOCUS_LEFT
                else -> View.FOCUS_RIGHT
            }
            val next = (focused ?: root).focusSearch(direction) ?: return
            try {
                if (!next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) next.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            } finally { next.recycle() }
        } finally { focused?.recycle(); root.recycle() }
    }

    // Starts or stops a named quick recording from a mapped remote key at any ordinary app screen.
    fun toggleRecording() {
        if (recordingName != null) { stopRecording(); return }
        val names = savedMacroNames().toSet()
        val base = "Remote " + java.text.SimpleDateFormat("yyyy-MM-dd HH-mm", java.util.Locale.ROOT).format(java.util.Date())
        var name = base
        var suffix = 2
        while (name in names) name = "$base (${suffix++})"
        val chosenName = name
        if (currentPackage == packageName) {
            openRecordedApp(packageName)
            handler.postDelayed({ startQuickRecording(chosenName) }, 450)
        } else startQuickRecording(chosenName)
    }

    // Starts the quick recorder after any launcher Home transition has settled.
    private fun startQuickRecording(name: String) {
        val touch = getSharedPreferences("remoteMacros", MODE_PRIVATE).getBoolean("touchRecording", true)
        if (touch && !canRecordTouches()) { message("Refresh Snapplication accessibility support: turn it off and on to allow recorded gestures."); return }
        if (beginRecording(name, touch = touch)) message("Recording started. Press the same remote button or Stop to save.")
        else message("Recording could not start. Enable accessibility support and try again.")
    }

    // Checks the capability Android actually bound, including devices retaining pre-update permissions.
    fun canRecordTouches(): Boolean = (serviceInfo?.capabilities ?: 0) and
        android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0

    // Arms up to 20 cross-app actions; an optional first app preserves older launcher integration.
    fun beginRecording(name: String, targetPackage: String = "", touch: Boolean = false): Boolean {
        if (BuildConfig.FLAVOR == "ereader") return false
        if (touch && !canRecordTouches()) return false
        if (name.isBlank() || name.length > 40 || (targetPackage.isNotBlank() && !recordablePackage(targetPackage)) ||
            name.trim() in savedMacroNames()) return false
        finishReplay()
        stopRecording()
        if (name.trim() in savedMacroNames()) return false
        replayGeneration++
        confirmation?.dismiss()
        recordingName = name.trim()
        recordingPackage = targetPackage
        recordingStarted = targetPackage.isBlank()
        lastRecordedPackage = null
        recordingSteps = JSONArray()
        lastRecordTime = SystemClock.uptimeMillis()
        ignoreClicksUntil = lastRecordTime + 400
        val button = Button(this).apply {
            text = "Stop recording (0/20)"
            setOnClickListener { stopRecording() }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.END }
        return runCatching {
            if (touch) {
                val recorder = TouchMacroRecorder(this, { touchGuard() }, { step ->
                    if (recordingName != null) {
                        val owner = step.optString("package")
                        val started = step.optLong("startedAt", SystemClock.uptimeMillis())
                        val gap = (started - lastRecordTime).coerceIn(0, 10000)
                        if (owner != lastRecordedPackage) {
                            appendRecordedStep(JSONObject().put("kind", if (owner == packageName) "home" else "launch")
                                .put("package", owner).put("delay", gap))
                            lastRecordedPackage = owner
                            step.put("delay", 0)
                        } else step.put("delay", gap)
                        step.remove("startedAt")
                        appendRecordedStep(step)
                    }
                }, { reason -> stopRecording(); message(reason) }, { paused ->
                    stopButton?.text = if (paused) "Paused on this screen · Stop (${recordingSteps.length()}/20)"
                        else "Stop recording (${recordingSteps.length()}/20)"
                })
                check(recorder.start())
                touchRecorder = recorder
            }
            getSystemService(WindowManager::class.java).addView(button, params)
            stopButton = button
            if (recordingStarted) {
                val active = currentPackage
                if (active != null && recordablePackage(active)) {
                    recordingPackage = active
                    appendRecordedStep(JSONObject().put("kind", if (active == packageName) "home" else "launch").put("package", active))
                    lastRecordedPackage = active
                }
            }
            true
        }.getOrElse {
            touchRecorder?.close(); touchRecorder = null
            stopButton?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
            stopButton = null
            recordingName = null
            recordingPackage = null
            false
        }
    }

    // Ends explicit recording, removes its overlay and saves only a nonempty macro to local preferences.
    fun stopRecording(): Boolean {
        touchRecorder?.close(); touchRecorder = null
        stopButton?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        stopButton = null
        val name = recordingName
        val target = recordingPackage
        recordingName = null
        recordingPackage = null
        recordingStarted = false
        lastRecordedPackage = null
        if (name == null || recordingSteps.length() == 0) return false
        val initial = recordingSteps.optJSONObject(0)?.optString("package").orEmpty().ifBlank { target.orEmpty() }
        val saved = macros(this).put(name, JSONObject().put("version", 2).put("package", initial).put("steps", recordingSteps))
        getSharedPreferences("remoteMacros", Context.MODE_PRIVATE).edit().putString("macros", saved.toString())
            .putString("pendingAssignment", name).apply()
        message("Saved macro: $name")
        return true
    }

    // Lists the saved local macros without requiring MainActivity to inspect service storage.
    fun savedMacroNames(): List<String> = savedMacroNames(this)

    // Removes a selected macro and cancels pending replay so deleted actions cannot continue running.
    fun deleteMacro(name: String) {
        finishReplay()
        val saved = macros(this)
        saved.remove(name)
        getSharedPreferences("remoteMacros", Context.MODE_PRIVATE).edit().putString("macros", saved.toString()).apply()
    }

    // Opens the first recorded app from Settings, then replays after its foreground identity is verified.
    fun openForReplay(name: String) {
        if (runningMacro == name) return
        finishReplay()
        val macro = macros(this).optJSONObject(name) ?: return
        if (macro.optBoolean("currentScreen", false)) { replay(name); return }
        val target = macro.optString("package")
        runningMacro = name
        val intent = packageManager.getLaunchIntentForPackage(target)
        if (intent == null) { finishReplay("Open the target screen and choose Run on current screen in Edit action."); return }
        val request = ++replayGeneration
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            finishReplay("The recorded app could not open."); return
        }
        awaitReplayApp(name, target, request, 0)
    }

    // Waits briefly for the requested app without sending any recorded click during its transition.
    private fun awaitReplayApp(name: String, target: String, request: Int, attempt: Int) {
        handler.postDelayed({
            if (request != replayGeneration) return@postDelayed
            updateForeground()
            if (currentPackage == target) { runningMacro = null; replay(name) }
            else if (attempt < 15) awaitReplayApp(name, target, request, attempt + 1)
            else finishReplay("Open the recorded app's starting screen, then use its mapped button.")
        }, 250)
    }

    // Replays legacy single-app or cross-app macros; optional confirmation remains a user preference.
    fun replay(name: String) {
        if (runningMacro == name) return
        if (recordingName != null) { message("Stop recording before running a macro."); return }
        val macro = macros(this).optJSONObject(name) ?: return
        val target = macro.optString("package")
        updateForeground()
        if ((macro.optInt("version", 1) < 2 && currentPackage != target) || !recordablePackage(target)) {
            message("Open the recorded app and its starting screen before running $name.")
            return
        }
        finishReplay()
        runningMacro = name
        replaySpeed = macro.optDouble("speed", 1.0).coerceIn(0.25, 4.0)
        val original = macro.optJSONArray("steps") ?: run { finishReplay(); return }
        val steps = JSONArray()
        var skipLaunch = macro.optBoolean("currentScreen", false)
        for (index in 0 until original.length()) {
            val step = original.optJSONObject(index) ?: continue
            if (skipLaunch && step.optString("kind") in setOf("home", "launch")) continue
            skipLaunch = false; steps.put(step)
        }
        if (steps.length() !in 1..20) { finishReplay("This action needs between 1 and 20 steps."); return }
        if (!getSharedPreferences("remoteMacros", MODE_PRIVATE).getBoolean("confirmReplay", false)) {
            if (steps.length() in 1..20) beginReplay(name, target, steps)
            return
        }
        confirmation = AlertDialog.Builder(this).setTitle("Run $name?")
            .setMessage("Repeat ${macro.optJSONArray("steps")?.length() ?: 0} recorded actions across their saved apps. Check the starting screen first.")
            .setNegativeButton("Cancel") { _, _ -> finishReplay() }.setOnCancelListener { finishReplay() }.setPositiveButton("Run") { _, _ ->
                if (steps.length() in 1..20) beginReplay(name, target, steps)
            }.create().apply {
                window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                show()
            }
    }

    // Keeps a visible cancel control available throughout playback, including transition waits.
    private fun beginReplay(name: String, target: String, steps: JSONArray) {
        finishReplay()
        runningMacro = name
        val stop = Button(this).apply {
            text = "Stop action"
            contentDescription = "Stop running action"
            setOnClickListener { finishReplay("Action stopped") }
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.END }
        if (runCatching { getSystemService(WindowManager::class.java).addView(stop, params) }.isFailure) {
            finishReplay("The action could not start because its Stop control is unavailable."); return
        }
        replayStop = stop
        runStep(target, steps, 0, replayGeneration)
    }

    // Invalidates queued steps and removes replay controls on cancellation, completion or failure.
    private fun finishReplay(feedback: String? = null) {
        replayGeneration++
        runningMacro = null
        replayStop?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        replayStop = null
        confirmation?.dismiss(); confirmation = null
        if (feedback != null) message(feedback)
    }

    // Replays legacy clicks and v2 cross-app actions, waiting for verified app transitions.
    private fun runStep(target: String, steps: JSONArray, index: Int, generation: Int, attempt: Int = 0) {
        if (generation != replayGeneration) return
        if (index >= steps.length()) { finishReplay("Action finished"); return }
        val step = steps.optJSONObject(index) ?: run { finishReplay("Action stopped: invalid step."); return }
        val expected = step.optString("package", target)
        handler.postDelayed({
            if (generation != replayGeneration) return@postDelayed
            updateForeground()
            val kind = step.optString("kind", "click")
            if (!recordablePackage(expected)) { finishReplay("Macro stopped: unavailable or protected app."); return@postDelayed }
            if (kind == "launch" || kind == "home") {
                val destination = if (kind == "home") packageName else expected
                if (kind == "home" && attempt == 0) {
                    // Even when already in this package, reset Settings/Drawer to the recorded Home.
                    if (openRecordedApp(destination)) runStep(target, steps, index, generation, 1)
                    else finishReplay("Macro stopped: Home could not open.")
                }
                else if (currentPackage == destination) runStep(target, steps, index + 1, generation)
                else if (attempt == 0 && openRecordedApp(destination)) runStep(target, steps, index, generation, 1)
                else if (attempt in 1..15) runStep(target, steps, index, generation, attempt + 1)
                else finishReplay("Macro stopped: the recorded app did not open.")
                return@postDelayed
            }
            if (currentPackage != expected) {
                if (attempt < 15) runStep(target, steps, index, generation, attempt + 1)
                else finishReplay("Macro stopped: the expected app is not visible.")
                return@postDelayed
            }
            val root = rootInActiveWindow
            if (root == null || containsSensitiveInput(root, blockEditable = false)) {
                root?.recycle()
                finishReplay("Macro stopped: the screen is unavailable or contains private input.")
                return@postDelayed
            }
            if (kind == "back") {
                root.recycle()
                if (performGlobalAction(GLOBAL_ACTION_BACK)) runStep(target, steps, index + 1, generation)
                else finishReplay("Macro stopped: Android could not go back.")
                return@postDelayed
            }
            if (kind == "gesture") {
                root.recycle()
                replayTouch(step, generation) { runStep(target, steps, index + 1, generation) }
                return@postDelayed
            }
            val matches = mutableListOf<AccessibilityNodeInfo>()
            collectMatches(root, step, matches)
            root.recycle()
            val action = if (kind == "scroll") step.optInt("action") else AccessibilityNodeInfo.ACTION_CLICK
            val allowed = kind != "scroll" || action in setOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            val success = allowed && matches.size == 1 && matches[0].performAction(action)
            matches.forEach { it.recycle() }
            if (!success) finishReplay("Macro stopped: a recorded control is unavailable or ambiguous.")
            else runStep(target, steps, index + 1, generation)
        }, if (attempt > 0) 250 else (step.optLong("delay", 350).coerceIn(0, 10000) / replaySpeed).toLong().coerceIn(40, 10000))
    }

    /* Describes the visible app layout without text; coordinate macros stop on a different screen,
     * keyboard, password or lock screen. Vendor dialogs are eligible; Android permission surfaces are not. */
    private fun touchGuard(): JSONObject? {
        if (!getSystemService(android.os.PowerManager::class.java).isInteractive ||
            getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return null
        val visible = windows
        try {
            val window = visible.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                (it.type == AccessibilityWindowInfo.TYPE_SYSTEM && (it.isActive || it.isFocused)) }
                .maxByOrNull { it.layer } ?: return null
            val root = window.root ?: return null
            try {
                val owner = root.packageName?.toString() ?: return null
                if (!recordablePackage(owner)) return null
                val bounds = Rect().also { window.getBoundsInScreen(it) }
                for (other in visible.filter { it.layer > window.layer }) {
                    if (other.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return null
                    if (other.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                        if (touchRecorder?.ownsWindow(other.id) == true || landscapeLaunch.ownsWindow(other.id)) continue
                        val overlayRoot = other.root
                        val ours = try { overlayRoot?.packageName?.toString() == packageName } finally { overlayRoot?.recycle() }
                        if (!ours) return null
                    } else {
                        val region = Rect().also { other.getBoundsInScreen(it) }
                        if (Rect.intersects(region, bounds) && region.width() > bounds.width() / 10 && region.height() > bounds.height() / 10) return null
                    }
                }
                val excluded = JSONArray()
                val structure = StringBuilder()
                var inspected = 0
                fun inspect(node: AccessibilityNodeInfo, depth: Int): Boolean {
                    if (!node.isVisibleToUser) return true
                    if (depth > 25 || ++inspected > 300) return false
                    if (node.isPassword || (node.isEditable && (node.isFocused || node.isAccessibilityFocused))) return false
                    if (node.isEditable) {
                        val box = Rect().also { node.getBoundsInScreen(it) }
                        excluded.put(JSONArray().put(box.left).put(box.top).put(box.right).put(box.bottom))
                    }
                    structure.append(depth).append(':').append(node.viewIdResourceName.orEmpty()).append(':')
                        .append(node.className).append(':').append(node.isClickable).append(';')
                    if (node.isClickable || node.isEditable) {
                        val location = Rect().also { node.getBoundsInScreen(it) }
                        structure.append(location.toShortString())
                    }
                    for (index in 0 until node.childCount.coerceAtMost(100)) {
                        val child = node.getChild(index) ?: continue
                        val allowed = try { inspect(child, depth + 1) } finally { child.recycle() }
                        if (!allowed) return false
                    }
                    return true
                }
                if (!inspect(root, 0) || inspected == 0) return null
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                val display = getSystemService(WindowManager::class.java).defaultDisplay
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(structure.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
                return JSONObject().put("package", owner).put("screenGuard", digest)
                    .put("rotation", display.rotation).put("width", metrics.widthPixels).put("height", metrics.heightPixels)
                    .put("window", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
                    .put("excluded", excluded)
            } finally { root.recycle() }
        } finally { visible.forEach { it.recycle() } }
    }

    // Replays a guarded coordinate stroke exactly once; timeout/cancel never retries an uncertain touch.
    private fun replayTouch(step: JSONObject, generation: Int, completed: () -> Unit) {
        val current = touchGuard()
        if (current == null || !TouchMacroRecorder.sameScreen(step, current)) {
            finishReplay("Macro stopped: return to the recorded screen and orientation."); return
        }
        val gesture = TouchMacroRecorder.description(step, current.optInt("width"), current.optInt("height"), replaySpeed)
            ?: run { finishReplay("Macro stopped: invalid recorded gesture."); return }
        var finished = false
        val accepted = runCatching { dispatchGesture(gesture, object : GestureResultCallback() {
            // Advances once only after Android confirms the touch finished.
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                if (generation != replayGeneration || finished) return
                finished = true; completed()
            }
            // Stops instead of retrying a partially delivered action.
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                if (generation != replayGeneration || finished) return
                finished = true; finishReplay("Macro stopped: Android cancelled the touch.")
            }
        }, handler) }.getOrDefault(false)
        if (!accepted) { finished = true; finishReplay("Android did not allow the touch. Re-enable accessibility support and try again."); return }
        handler.postDelayed({
            if (generation == replayGeneration && !finished) { finished = true; finishReplay("Macro stopped: touch timed out.") }
        }, 4500)
    }

    // Opens only an installed app's public launcher entry; no arbitrary intents or privileged screens.
    private fun openRecordedApp(target: String): Boolean {
        if (!recordablePackage(target)) return false
        val intent = if (target == packageName) Intent(this, MainActivity::class.java)
            else packageManager.getLaunchIntentForPackage(target) ?: return false
        return runCatching {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            true
        }.getOrDefault(false)
    }

    // Finds exact ID/label matches only; no guessed coordinates or partial-text clicks are replayed.
    private fun collectMatches(root: AccessibilityNodeInfo, step: JSONObject, matches: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 40 || matches.size > 1) return
        val id = step.optString("id")
        val value = if (step.optBoolean("description")) root.contentDescription else root.text
        val labelMatches = !step.has("label") || value?.toString() == step.optString("label")
        val matchesSelector = if (id.isNotBlank()) root.viewIdResourceName == id && labelMatches else labelMatches && step.has("label")
        if (matchesSelector && parentContextMatches(root, step) && (!step.has("guardClass") || root.className?.toString() == step.optString("guardClass")) && root.isVisibleToUser && root.isEnabled && !root.isEditable && !root.isPassword) {
            var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(root)
            var levels = 0
            while (candidate != null && !candidate.isClickable && step.optBoolean("ancestor") && levels++ < 5) {
                val parent = candidate.parent; candidate.recycle(); candidate = parent
            }
            val supported = if (step.optString("kind") == "scroll") candidate?.isScrollable == true &&
                candidate.actionList.any { it.id == step.optInt("action") } else candidate?.isClickable == true
            if (supported && candidate != null && candidate.isEnabled && !candidate.isEditable && !candidate.isPassword) matches.add(candidate) else candidate?.recycle()
        }
        for (i in 0 until root.childCount.coerceAtMost(300)) {
            val child = root.getChild(i) ?: continue
            try { collectMatches(child, step, matches, depth + 1) } finally { child.recycle() }
        }
    }

    // Rejects password/editable screens entirely so recordings never capture credentials or text entry.
    private fun containsSensitiveInput(root: AccessibilityNodeInfo, depth: Int = 0, blockEditable: Boolean = true): Boolean {
        if (depth > 40) return true
        if (root.isVisibleToUser && (root.isPassword || (blockEditable && root.isEditable))) return true
        for (i in 0 until root.childCount.coerceAtMost(300)) {
            val child = root.getChild(i) ?: continue
            val sensitive = try { containsSensitiveInput(child, depth + 1, blockEditable) } finally { child.recycle() }
            if (sensitive) return true
        }
        return false
    }

    // Allows explicitly chosen apps, including vendor utilities, while excluding permission, lock and system settings surfaces.
    private fun recordablePackage(target: String): Boolean {
        if (target.isBlank() || target in setOf("android", "com.android.systemui", "com.android.settings", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller", "com.google.android.packageinstaller")) return false
        return runCatching { packageManager.getApplicationInfo(target, 0); true }.getOrDefault(false)
    }

    // Gives concise feedback without displaying captured labels or app contents.
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    /* Android interrupts accessibility feedback here, not the service. We own no ongoing spoken/haptic feedback.
     * Keep macros alive: apps such as Huawei Clock call interrupt during ordinary tab changes.
     * Actual service shutdown still cancels all work through onUnbind/onDestroy. */
    override fun onInterrupt() {
        if (BuildConfig.DEBUG) android.util.Log.d("SnappMacro", "Accessibility interrupt")
    }

    // Releases overlays and capture identity when the user disables this accessibility service.
    override fun onUnbind(intent: Intent?): Boolean { cleanUp(); instance = null; return super.onUnbind(intent) }

    // Releases the same resources when Android destroys the service process normally.
    override fun onDestroy() {
        meebookReader.close()
        if (android.os.Build.VERSION.SDK_INT >= 30) readingSnapshots?.close()
        readingSnapshots = null
        cleanUp(); instance = null; super.onDestroy()
    }

    // Clears transient state; recorded clicks are saved only through the explicit Stop action.
    private fun cleanUp() {
        cloudStreamControls.close()
        landscapeLaunch.clear()
        if (BuildConfig.DEBUG) android.util.Log.d("SnappMacro", "Service cleanup; recording=${recordingPackage != null}")
        recordingSteps = JSONArray()
        stopRecording()
        finishReplay()
        confirmation?.dismiss()
        confirmation = null
        replayGeneration++
        handler.removeCallbacksAndMessages(null)
        controls.clearHeldButtons()
        currentPackage = null
        currentPackageSinceNanos = SystemClock.elapsedRealtimeNanos()
        lastCaptureWindowId = null
        captureBounds = null
        capturePackage = null
        captureActivity = null
        capturePackageSinceNanos = SystemClock.elapsedRealtimeNanos()
    }
}
