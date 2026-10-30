package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import java.util.concurrent.Executors

/*
MainActivity puts the launcher's screens together and connects their buttons to actions. Think of it
as a toy shelf: this class chooses which tray you see, and the other classes look after the toys in
each tray. You can change one tray without rebuilding the whole shelf. Android opens this class when
the launcher starts or receives a Home request.

MainActivity is a child of Android's Activity class. Activity gives it screen creation, pause,
resume and input callbacks.

state stores the launcher choices, ui builds styled controls, and root holds the current page.
screen names that page; settingsCategory and settingsQuery keep the current settings selection.
watch and reader hold the built-in viewers. worker handles file work away from the screen thread,
and scanGeneration rejects results for a page already left. guideStep and guideActive track the
introduction. Android calls onCreate to restore the screen, onResume to refresh it, and onStop to
pause playback and monitoring. The panels and helpers listed below are used by MainActivity; they do
not inherit from it.

Where to find things (the names below are the exact class and method names):

- Home layout and favourite blocks: MainActivity.home() and favouriteColumn(); circleKey() draws
the Circles style. Own books and videos shown full screen: MainActivity.viewer().

- The centre's saved picture and return-to-app action: ExternalPreview.

ReadingSnapshots saves still reading pages. PreviewCaptureService saves Watch frames.
ExternalPreview displays the saved picture; ReaderSurface recognises reading pages.

- Video playing inside Home: WatchPlayer. Pages shown inside Home: ReaderPanel (PDF, CBZ,
images) and EpubPanel (EPUB).

- Folder items and saved places: LocalLibrary, LibraryItem and LauncherState.

- All Apps, A-Z navigation and changing favourite order: DrawerPanel.

MainActivity.drawer() opens it; manageFavourites() opens its favourite editor.

- Settings pages and search: MainActivity.settings() and organizeSettings().

Status & repair gathers setup checks; LauncherBackup saves/restores portable choices.

- First setup and the introduction: DeviceProfileSetup and LauncherWalkthrough,

opened by MainActivity.introduction().

- Colours and reusable buttons: LauncherUi and ColourPicker.

- Wallpaper: LauncherWallpaper. App icons: IconPacks and AppIconTheme.

- Remote screen: RemoteMappingPanel, opened by MainActivity.remoteSettings().

RemoteControls stores button choices; RemoteActionService handles actions across apps.

- Recorded actions: MainActivity.macroSettings(), MacroEditor and TouchMacroRecorder.

MacroPreviewView rehearses timing over an optional reference image without pressing real app
buttons.

- Other apps' media controls: ExternalPlaybackPanel. Heat notice: ThermalMonitor.

Buttons and controls connected here include ALL APPS, empty favourite slots, Home/Back, content
opening, folder/link selection, Read/Watch selection, settings switches, permission setup and macro
commands. DrawerPanel and RemoteMappingPanel build their own controls. To change screen layout,
start with home() or begin(). To add a setting, edit settings() and its organizeSettings() grouping,
then save its value in LauncherState when needed. To add behaviour inside one panel, follow the
class map above to that panel first.

Planned, not implemented: a future Gaming mode needs more than another mode button. Start at
chooseMode(), home(), library() and applyOrientation(); add matching saved state in LauncherState
and a dedicated game panel/launch flow. Read and Watch are the current modes. No game session
capture or game suspension is provided by this class. This map helps you find the right file before
changing how the launcher looks or works.
*/
class MainActivity : Activity() {
    private lateinit var phoneRotation: PhoneRotation
    private lateinit var thermal: ThermalMonitor
    private var thermalText: String? = null
    private var thermalBanner: android.widget.TextView? = null
    private lateinit var remote: RemoteControls
    private lateinit var state: LauncherState
    private lateinit var ui: LauncherUi
    private lateinit var root: LinearLayout
    private var watch: WatchPlayer? = null
    private var reader: ReaderPanel? = null
    private var epub: EpubPanel? = null
    private val drawerGestureAreas = mutableListOf<View>()
    private var screen = "Home"
    private var pickerMode = "Watch"
    private var choosingPreview = false
    private var guideStep = 0
    private var guideActive = false
    private var guidePanel: View? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var scanGeneration = 0
    private var drawerPanel: DrawerPanel? = null
    private val appsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var homeAppsChanged = false
    // Rebuilds All Apps, or Home when a favourite changed, once a burst of package events settles.
    private val refreshApps = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        when (screen) {
            "Apps" -> drawer(editFavourites = drawerPanel?.isEditing == true)
            "Home" -> if (homeAppsChanged) home()
        }
        homeAppsChanged = false
    }
    // Keeps the visible app list and Home favourites in step with installs and removals.
    private val appsCallback = object : android.content.pm.LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: android.os.UserHandle) = appsChanged(listOf(packageName))
        override fun onPackageAdded(packageName: String, user: android.os.UserHandle) = appsChanged(listOf(packageName))
        override fun onPackageChanged(packageName: String, user: android.os.UserHandle) = appsChanged(listOf(packageName))
        override fun onPackagesAvailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) = appsChanged(packageNames.toList())
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) = appsChanged(packageNames.toList())
    }
    // Home is rebuilt only for favourite apps, so a background update never interrupts playback.
    private fun appsChanged(packages: List<String>) {
        if (state.favourites().any { flat -> ComponentName.unflattenFromString(flat)?.packageName in packages }) homeAppsChanged = true
        appsHandler.removeCallbacks(refreshApps); appsHandler.postDelayed(refreshApps, 400)
    }
    // Receives system screen-off events to stage the next episode through WatchPlayer.
    private val screenReceiver = object : BroadcastReceiver() {
        // Applies completion behavior only when the device screen actually turns off.
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) watch?.screenOff()
        }
    }

    // Restores launcher preferences and builds the appropriate mode without requesting root
    // privileges.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Book copies from a run Android ended early are never reused; remove them before any
        // reader opens a new one.
        val started = System.currentTimeMillis()
        cacheDir.listFiles { file -> (file.name.startsWith("reader-") || file.name.startsWith("epub-")) && file.lastModified() < started }
            ?.forEach { it.delete() }
        RemoteControls.trackLauncherWindow(window.decorView)
        state = LauncherState(this)
        state.initializeReaderProfile()
        phoneRotation = PhoneRotation(this) { state.deviceProfile == "Phone" && BuildConfig.FLAVOR != "tv" && state.orientation == "Auto" }
        remote = RemoteControls(this)
        thermal = ThermalMonitor(this) { text -> thermalText = text; updateThermalBanner() }
        pickerMode = savedInstanceState?.getString("pickerMode") ?: state.mode
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        applyOrientation()
        val guidePrefs = getSharedPreferences("launcherGuide", MODE_PRIVATE)
        guideActive = state.profileSetupComplete && guidePrefs.getBoolean("active", false)
        guideStep = guidePrefs.getInt("step", 0).coerceIn(0, 4)
        settingsCategory = savedInstanceState?.getString("settingsCategory")
        settingsQuery = savedInstanceState?.getString("settingsQuery").orEmpty()
        when (savedInstanceState?.getString("screen")) {
            "Status & repair" -> statusSettings()
            "Backup & restore" -> backupSettings()
            "Settings" -> settings()
            "Theme Mode" -> themeSettings()
            "Favourites Style" -> favouriteSettings()
            "Apps" -> drawer(editFavourites = guideActive && guideStep == 1)
            "Remote buttons" -> remoteSettings()
            "Recorded actions" -> macroSettings()
            "Home preview" -> previewSettings()
            "Page previews" -> readingPreviewSettings()
            "Other app playback" -> externalPlayback()
            "Device diagnostics" -> diagnostics()
            "Library" -> library()
            "Welcome" -> introduction(savedInstanceState?.getInt("guideStep") ?: 0)
            else -> home()
        }
        if (guideActive && savedInstanceState == null) showGuideStep()
        else if (!state.tutorialSeen && screen != "Welcome") introduction()
        savedInstanceState?.getString("learningAction")?.takeIf { it in remote.actions }?.let { action ->
            root.post { if (!isDestroyed && !isFinishing) remote.learn(action) { remoteSettings() } }
        }
    }

    // Preserves picker intent across rotation so a Watch folder cannot become a Read folder
    // accidentally.
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("settingsCategory", settingsCategory)
        outState.putString("settingsQuery", settingsQuery)
        outState.putString("learningAction", remote.learningAction)
        outState.putInt("guideStep", guideStep); outState.putString("pickerMode", pickerMode); outState.putString("screen", screen); super.onSaveInstanceState(outState)
    }

    // Returns HOME intents to the familiar home screen.
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (intent.getBooleanExtra("openApps", false)) drawer() else home() }

    // Phone mode follows rotation; explicit mode/orientation choices and dedicated profiles stay
    // fixed.
    private fun applyOrientation() {
        if (state.orientation == "Auto") RemoteActionService.instance?.cancelLandscape()
        if (state.automaticPhoneMode) {
            RemoteActionService.instance?.cancelLandscape()
            state.mode = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "Watch" else "Read"
        }
        if (state.mode == "Read") {
            stopService(Intent(this, PreviewCaptureService::class.java))
            RemoteActionService.instance?.cancelLandscape()
        }
        RemoteActionService.instance?.refreshReadingSnapshots()
        requestedOrientation = when (state.orientation) {
            "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "Auto" -> if (state.deviceProfile == "Phone" && BuildConfig.FLAVOR != "tv" && phoneRotation.supported)
                ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> if (state.mode == "Watch") ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    // Creates a page shell with persistent visible navigation and a readable title.
    private fun begin(title: String): LinearLayout {
        scanGeneration++
        watch?.pause()
        reader?.close(); reader = null
        epub?.close(); epub = null
        screen = title
        drawerGestureAreas.clear(); drawerSwipeEligible = false
        ui = LauncherUi(this, state, state.useWallpaper && state.theme != "E-paper")
        val immersiveHome = state.fullscreen
        // Edge swipes still reveal Android navigation when fullscreen is enabled.
        val lightBars = if (ui.text == Color.BLACK)
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        window.decorView.systemUiVisibility = lightBars or if (immersiveHome)
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            else 0
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (immersiveHome) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        val showWallpaper = state.useWallpaper && state.theme != "E-paper"
        if (showWallpaper) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (showWallpaper) Color.TRANSPARENT else ui.background))
        root = ui.column()
        root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> LauncherFont.apply(view) }
        root.setBackgroundColor(if (showWallpaper) (ui.background and 0xffffff) or 0x30000000 else ui.background)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (immersiveHome) view.setPadding(0, 0, 0, 0)
            else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        if (title != "Home" && title != "Apps" && title != "Viewer") {
            val header = ui.column(true).apply { setBackgroundColor(ui.textSurface()) }
            header.addView(ui.button("‹") { navigateBack() }.apply {
                contentDescription = "Back"
                ui.flatFocus(this, Color.TRANSPARENT, outline = false)
                stateListAnimator = null; elevation = 0f
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(52)))
            header.addView(ui.label(if (title == "Settings") "Launcher Settings" else title, 19f).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
            root.addView(header)
        }
        thermalBanner = ui.label("", 16f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setBackgroundColor(ui.surface)
        }
        root.addView(thermalBanner)
        updateThermalBanner()
        setContentView(if (showWallpaper) LauncherWallpaper.wrap(this, root) else root)
        val pageRoot = root
        pageRoot.post { if (root === pageRoot && !isDestroyed) attachGuide() }
        if (!guideActive && title != "Welcome" && state.animations && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            root.alpha = 0f; root.animate().alpha(1f).setDuration(140).start()
        }
        return root
    }

    // Gives portrait reading pages more room while retaining the Make layout for Watch and
    // landscape.
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun home(measuredLandscape: Boolean? = null) {
        begin("Home")
        val landscape = measuredLandscape ?: (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val homeRoot = root
        homeRoot.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            if (!guideActive && right > left && bottom > top && ((right - left > bottom - top) != landscape)) {
                view.post { if (root === homeRoot && screen == "Home") home(right - left > bottom - top) }
            }
        }
        val body = ui.column(landscape)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val viewer = ui.column().apply { background = ui.homeSurface() }
        val panel = FrameLayout(this)
        viewer.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        val favourites = state.favourites().filter { flat ->
            val component = ComponentName.unflattenFromString(flat)
            component != null && runCatching { packageManager.getActivityInfo(component, 0) }.isSuccess
        }.take(state.favouriteCount)
        if (landscape) {
            val total = if (state.dynamicBlocks) favourites.size.coerceAtLeast(2) else state.favouriteCount
            val left = (total + 1) / 2
            val right = if (state.dynamicBlocks && state.asymmetricBlocks) (total - left).coerceAtLeast(1) else left
            body.addView(favouriteColumn(favourites.take(left), slotCount = left), LinearLayout.LayoutParams(0, -1, .15f))
            body.addView(viewer, LinearLayout.LayoutParams(0, -1, .70f))
            body.addView(favouriteColumn(favourites.drop(left), slotCount = right), LinearLayout.LayoutParams(0, -1, .15f))
        } else {
            val previewShare = if (state.mode == "Read") .72f else .57f
            body.addView(viewer, LinearLayout.LayoutParams(-1, 0, previewShare))
            body.addView(favouriteColumn(favourites, portrait = true, slotCount = if (state.dynamicBlocks) favourites.size.coerceAtLeast(2) else state.favouriteCount), LinearLayout.LayoutParams(-1, 0, 1f - previewShare))
        }
        val last = state.last(state.mode)
        if (state.viewerSource == "App") {
            panel.addView(ExternalPreview(this, state).build(onResumeCapture = { repairPreviews() },
                onUseFolder = { useFolderSource() }) {
                choosingPreview = true; drawer()
            }, FrameLayout.LayoutParams(-1, -1))
        } else if (last == null) {
            val empty = ui.column()
            empty.addView(ui.label(if (state.mode == "Watch") "Your next watch starts here" else "Pick up your next chapter", 24f))
            empty.addView(ui.label("Choose a folder to start. Your place will be saved.", 17f))
            empty.addView(ui.button("Choose folder") { pickFolder() })
            if (state.mode == "Watch") empty.addView(ui.button("Open video link") { directLink() })
            panel.addView(empty)
        } else if (state.mode == "Watch") {
            val player = watch ?: WatchPlayer(this, state) { message(it) }.also { watch = it }
            (player.view.parent as? ViewGroup)?.removeView(player.view)
            panel.addView(player.view, FrameLayout.LayoutParams(-1, -1))
            player.load(last, false)
            player.view.useController = state.playVideosInLauncher
            player.setTapOnly(state.playVideosInLauncher && state.tapViewer)
            if (!state.playVideosInLauncher) {
                player.view.hideController()
                addOpenContentOverlay(panel, last, "▶")
            } else if (state.fullscreenMedia) {
                // Home shows a still preview; selecting it plays full screen.
                player.setTapOnly(true); player.view.useController = false
                addViewerOverlay(panel, last, "▶")
            } else if (!state.tapViewer) player.view.showController()
        } else if (!ReadingFormats.canRenderInline(last.title, last.mime)) {
            panel.addView(ui.label(last.title, 24f))
            addOpenContentOverlay(panel, last, "Open book in reader")
        } else if (ReadingFormats.inlineKind(last.title, last.mime) == ReadingFormats.InlineKind.EPUB) {
            epub = EpubPanel(this, state, ui).also { panel.addView(it.view); it.load(last) }
            if (!state.readInLauncher) addOpenContentOverlay(panel, last, "Open book")
            else if (state.fullscreenMedia) addViewerOverlay(panel, last, "")
        } else {
            reader = ReaderPanel(this, state, ui).also { panel.addView(it.view); it.load(last) }
            if (!state.readInLauncher) addOpenContentOverlay(panel, last, "Open book")
            else if (state.fullscreenMedia) addViewerOverlay(panel, last, "")
        }
        root.addView(ui.row("ALL APPS") { drawer() }.apply {
            ui.flatFocus(this, ui.textSurface(), outline = false)
            gravity = android.view.Gravity.CENTER; textSize = 11f
            // The compact footer must not inherit the drawer row's 24 dp vertical padding.
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
            minHeight = 0; minimumHeight = 0; includeFontPadding = false
            setSingleLine(true)
            typeface = android.graphics.Typeface.MONOSPACE
            drawerGestureAreas.add(this)
        }, LinearLayout.LayoutParams(-1, ui.dp(if (landscape) 32 else 40)))
    }

    // Makes the preview an accessible launch target while keeping its image visible underneath.
    private fun addOpenContentOverlay(panel: FrameLayout, item: LibraryItem, label: String) {
        panel.addView(ui.button(label) { openContentInApp(item) }.apply {
            contentDescription = "Open ${item.title} in another app"
            textSize = if (item.mode == "Watch") 36f else 18f
            ui.flatFocus(this, Color.TRANSPARENT, outline = false)
        }, FrameLayout.LayoutParams(-1, -1))
    }

    // Makes the Home preview open the same book or video full screen.
    private fun addViewerOverlay(panel: FrameLayout, item: LibraryItem, label: String) {
        panel.addView(ui.button(label) { viewer(item) }.apply {
            contentDescription = "${item.title}. Open full screen"
            textSize = 36f
            ui.flatFocus(this, Color.TRANSPARENT, outline = false)
        }, FrameLayout.LayoutParams(-1, -1))
    }

    // Shows one of the user's own books or videos across the whole screen. Back returns to Home;
    // the reading page or playback position is saved as usual.
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun viewer(item: LibraryItem) {
        begin("Viewer")
        // Video sits on black; books keep the theme's own background so their title line is readable.
        root.setBackgroundColor(if (item.mode == "Watch") Color.BLACK else ui.background)
        val panel = FrameLayout(this)
        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        if (item.mode == "Watch") {
            val player = watch ?: WatchPlayer(this, state) { message(it) }.also { watch = it }
            (player.view.parent as? ViewGroup)?.removeView(player.view)
            panel.addView(player.view, FrameLayout.LayoutParams(-1, -1))
            player.setTapOnly(state.tapViewer)
            if (!state.tapViewer) player.view.showController()
            player.load(item, true)
        } else if (ReadingFormats.inlineKind(item.title, item.mime) == ReadingFormats.InlineKind.EPUB) {
            epub = EpubPanel(this, state, ui).also { panel.addView(it.view); it.load(item) }
        } else {
            reader = ReaderPanel(this, state, ui).also { panel.addView(it.view); it.load(item) }
        }
    }

    // Hands the exact selected document/link to Android's viewer with a temporary read grant.
    private fun openContentInApp(item: LibraryItem) {
        watch?.pause()
        val uri = Uri.parse(item.uri)
        val extension = item.title.substringAfterLast('.', "").lowercase()
        val mime = if (item.mode == "Read") ReadingFormats.mime(item.title, item.mime)
            else if (extension == "cbz") "application/vnd.comicbook+zip"
            else if (item.mime !in setOf("application/octet-stream", "application/zip", "")) item.mime else when (extension) {
            "pdf" -> "application/pdf"
            "epub" -> "application/epub+zip"
            "cbz" -> "application/vnd.comicbook+zip"
            else -> if (item.mode == "Watch") "video/*" else "image/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(item.title, uri)
        }
        try {
            startActivity(intent)
            // External reading should follow the displayed page after leaving the folder picker.
            // The original document remains in Library for reopening if no page can be captured.
            if (item.mode == "Read") state.viewerSource = "App"
        }
        catch (_: android.content.ActivityNotFoundException) {
            message("No installed app can open this content. Install a compatible viewer, or enable in-launcher playback/reading in Settings.")
        } catch (_: SecurityException) {
            message("This content is no longer accessible. Choose it again from your folder.")
        }
    }

    // Summarises whether Android may close Snapplication or the app Home resumes to save battery.
    private fun keepReadyStatus(): String {
        val power = getSystemService(android.os.PowerManager::class.java)
        val owner = state.lastApp()?.let(ComponentName::unflattenFromString)?.packageName
        val self = power.isIgnoringBatteryOptimizations(packageName)
        val app = owner?.let { power.isIgnoringBatteryOptimizations(it) }
        return when {
            self && (app == null || app) -> "Protected from battery optimisation"
            !self && app == false -> "Android may close Snapplication and your app"
            !self -> "Android may close Snapplication"
            else -> "Android may close the app Home resumes"
        }
    }

    // Android decides when to close apps; this opens the switches that make it far less likely.
    private fun keepAppsReady() {
        val owner = state.lastApp()?.let(ComponentName::unflattenFromString)?.packageName
        val ownerLabel = owner?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrNull() }
        val huawei = android.os.Build.MANUFACTURER.equals("HUAWEI", true) || android.os.Build.MANUFACTURER.equals("HONOR", true)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "Battery optimisation · set Snapplication${if (ownerLabel != null) " and $ownerLabel" else ""} to Don't optimise" to {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        if (owner != null) actions += "$ownerLabel app info · allow background activity" to {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$owner")))
        }
        // Huawei protects its App launch screen from other apps; its Battery page links to it.
        if (huawei) actions += "Huawei Battery · open App launch, set both apps to Manage manually" to {
            openSettings(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
        }
        AlertDialog.Builder(this).setTitle("Keep apps ready")
            .setMessage("Android, not Snapplication, decides when to close apps. These switches make it much less likely, " +
                "so the app reopens where you left it. You can also lock the app in Recents (swipe down on its card). " +
                "Some players still reload protected streams after a while.")
            .setPositiveButton("Choose") { _, _ ->
                AlertDialog.Builder(this).setTitle("Keep apps ready")
                    .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
                    .setNegativeButton("Close", null).show()
            }.setNegativeButton("Cancel", null).show()
    }

    // Opens a system settings page, explaining when this device does not offer it.
    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { message("This settings page is not available on this device.") }
    }

    // Picks Watch, Read or Universal without the tutorial; the user's look is kept.
    private fun chooseDeviceMode() {
        val profiles = arrayOf("TV", "Reader", "Phone")
        val labels = arrayOf("Watch · videos in landscape", "Read · books in portrait", "Universal · read upright, watch sideways")
        AlertDialog.Builder(this).setTitle("Mode").setSingleChoiceItems(labels, profiles.indexOf(state.deviceProfile)) { dialog, index ->
            state.switchDeviceProfile(profiles[index]); dialog.dismiss(); applyOrientation(); settings()
        }.setNegativeButton("Cancel", null).show()
    }

    // Names the current combination of viewer buttons and progress bar.
    private fun viewerLayoutName(): String = when {
        state.tapViewer && state.showProgress -> "Clean + progress"
        state.tapViewer -> "Clean"
        state.showProgress -> "Progress"
        else -> "Standard"
    }

    // Sets viewer buttons and progress bar together; both stay individually adjustable after.
    private fun chooseViewerLayout() {
        val names = arrayOf("Standard · buttons on the viewer", "Progress · buttons and a progress bar",
            "Clean · no buttons, tap the centre to continue", "Clean + progress · no buttons, with a progress bar")
        val current = listOf("Standard", "Progress", "Clean", "Clean + progress").indexOf(viewerLayoutName())
        AlertDialog.Builder(this).setTitle("Quick layout").setSingleChoiceItems(names, current) { dialog, index ->
            state.tapViewer = index >= 2; state.showProgress = index % 2 == 1; dialog.dismiss(); settings()
        }.setNegativeButton("Cancel", null).show()
    }

    // Lets the viewer switch between an app resume entry and user-selected folder media.
    private fun chooseViewerSource() {
        AlertDialog.Builder(this).setTitle("Preview source").setItems(arrayOf("Resume the last app", "Files from a folder on this device")) { _, index ->
            if (index == 0) { state.viewerSource = "App"; settings() } else useFolderSource()
        }.setNegativeButton("Cancel", null).show()
    }

    // Switches this mode to folder content and asks for the folder straight away when none is
    // granted yet; Home offers the library when a folder is already available.
    private fun useFolderSource() {
        state.viewerSource = "Folder"
        val folder = state.folder(state.mode)
        val granted = folder != null && contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == folder }
        if (!granted) pickFolder() else if (screen == "Settings") settings() else library()
    }

    // Saves preview and local-media sizing without changing another app's display.
    private fun chooseContentScale() {
        val values = arrayOf("Fit", "Fill", "Stretch")
        val labels = arrayOf("Fit to preview · show the whole page or image", "Crop to fill · no distortion", "Stretch · fill all dimensions")
        val returnToPagePreviews = screen == "Page previews"
        AlertDialog.Builder(this).setTitle("Preview sizing")
            .setSingleChoiceItems(labels, values.indexOf(state.contentScale)) { dialog, index ->
                state.contentScale = values[index]
                reader?.applyContentScale(); epub?.applyContentScale(); watch?.applyContentScale()
                dialog.dismiss()
                if (returnToPagePreviews) readingPreviewSettings() else settings()
            }.setNegativeButton("Cancel", null).show()
    }

    // Preserves the configured Make block count; unassigned cells open the app picker.
    private fun favouriteColumn(components: List<String>, portrait: Boolean = false, slotCount: Int): LinearLayout {
        val shell = ui.column().apply { background = ui.homeSurface() }
        if (state.showFavouriteHeading) shell.addView(ui.section("Favourites").apply {
            // The enclosing favourite panel already supplies the selected glass or solid surface.
            setBackgroundColor(Color.TRANSPARENT)
        })
        val column = ui.column().apply {
            gravity = android.view.Gravity.CENTER
            dividerDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(ui.homeLine); setSize(ui.dp(1), ui.dp(1)) }
            // Circles float in open space, so they have no grid lines between them.
            showDividers = if (state.circles) LinearLayout.SHOW_DIVIDER_NONE else LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        shell.addView(column, LinearLayout.LayoutParams(-1, 0, 1f))
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (portrait && (state.grid || state.dynamicBlocks)) minOf(4, (slotCount + 1) / 2).coerceAtLeast(1) else if (!portrait && slotCount > 6) 2 else 1
        val displayedSlots = if (portrait && state.dynamicBlocks && !state.asymmetricBlocks) ((slotCount + columns - 1) / columns) * columns else slotCount
        val slots = List(displayedSlots) { components.getOrNull(it) }
        slots.chunked(columns).forEach { group ->
            val row = ui.column(true).apply {
                gravity = android.view.Gravity.CENTER
                dividerDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(ui.homeLine); setSize(ui.dp(1), ui.dp(1)) }
                showDividers = if (state.circles) LinearLayout.SHOW_DIVIDER_NONE else LinearLayout.SHOW_DIVIDER_MIDDLE
            }
            group.forEach { flat ->
                if (state.circles) {
                    val component = flat?.let(ComponentName::unflattenFromString)
                    val info = component?.let { runCatching { packageManager.getActivityInfo(it, 0) }.getOrNull() }
                    row.addView(if (component == null || info == null) circleKey(null, "Add app to Home", slotCount, { drawer(editFavourites = true) }, null)
                        else {
                            val label = info.loadLabel(packageManager).toString()
                            circleKey(IconPacks.load(this@MainActivity, component) { info.loadIcon(packageManager) }, label, slotCount,
                                { launch(component) }, { favouriteActions(flat, label) { home() } })
                        }, LinearLayout.LayoutParams(0, -1, 1f))
                    return@forEach
                }
                if (flat == null) {
                    val add = ui.button("+") { drawer(editFavourites = true) }.apply { contentDescription = "Add app to Home" }
                    ui.flatFocus(add, ui.textSurface(), outline = false)
                    row.addView(add, LinearLayout.LayoutParams(0, -1, 1f))
                    return@forEach
                }
                val component = ComponentName.unflattenFromString(flat) ?: return@forEach
                val info = runCatching { packageManager.getActivityInfo(component, 0) }.getOrNull() ?: return@forEach
                val label = info.loadLabel(packageManager).toString()
                val button = ui.button(if (state.grid || !state.showLabels) "" else label) { launch(component) }
                val icon = IconPacks.load(this@MainActivity, component) { info.loadIcon(packageManager) }.apply { setBounds(0, 0, ui.dp(40), ui.dp(40)) }
                if (!state.grid && state.showLabels) {
                    // An invisible twin of the icon on the right keeps the name centred on the row.
                    if (portrait) button.setCompoundDrawables(icon, null,
                        android.graphics.drawable.ColorDrawable(Color.TRANSPARENT).apply { setBounds(icon.bounds) }, null)
                    else button.setCompoundDrawables(null, icon, null, null)
                }
                button.compoundDrawablePadding = ui.dp(12)
                button.textSize = if (landscape) 12f else 18f
                // Narrow cells shrink the label before cutting it short. Auto-size needs maxLines
                // rather than setSingleLine, which scrolls sideways and never reports overflow.
                if (!state.grid && state.showLabels) {
                    button.maxLines = 1
                    button.setAutoSizeTextTypeUniformWithConfiguration(11, if (landscape) 12 else 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                } else button.setSingleLine(true)
                button.ellipsize = android.text.TextUtils.TruncateAt.END
                button.gravity = android.view.Gravity.CENTER
                button.contentDescription = label; button.tooltipText = label
                ui.flatFocus(button, if (state.grid || !state.showLabels) Color.TRANSPARENT else ui.textSurface(), outline = false)
                button.stateListAnimator = null
                button.setOnLongClickListener { favouriteActions(flat, label) { home() }; true }
                val tile = if (state.grid || !state.showLabels) FrameLayout(this).apply {
                    addView(button, FrameLayout.LayoutParams(-1, -1))
                    // A separate non-interactive image keeps adaptive icons centred through
                    // orientation changes.
                    addView(android.widget.ImageView(this@MainActivity).apply {
                        setImageDrawable(IconPacks.load(this@MainActivity, component) { info.loadIcon(packageManager) })
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(ui.dp(if (slotCount > 8) 36 else 56), ui.dp(if (slotCount > 8) 36 else 56), android.view.Gravity.CENTER))
                } else button
                row.addView(tile, LinearLayout.LayoutParams(0, -1, 1f))
            }
            column.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        drawerGestureAreas.add(shell)
        return shell
    }

    // A small round key centred in its cell, like a typewriter key: the app icon, or + for an
    // empty slot. A ring in the focus colour marks the key a remote or keyboard has selected.
    private fun circleKey(icon: android.graphics.drawable.Drawable?, label: String, slotCount: Int, onClick: () -> Unit, onLongClick: (() -> Unit)?): View {
        val size = ui.dp(if (slotCount > 8) 44 else 60)
        val ePaper = state.theme == "E-paper"
        val fill = if (ePaper) Color.WHITE else if (state.useWallpaper) 0x66000000 else ui.border
        // E-paper keys are white with a thin black rim; other themes use a soft filled disc.
        fun oval(ring: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fill)
            if (ring) setStroke(ui.dp(if (ePaper) 3 else 2), ui.focusAccent)
            else if (ePaper) setStroke(ui.dp(1), Color.BLACK)
        }
        val key = FrameLayout(this).apply {
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), oval(true))
                addState(intArrayOf(android.R.attr.state_pressed), oval(true))
                addState(intArrayOf(), oval(false))
            }
            isFocusable = true; isClickable = true
            contentDescription = label; tooltipText = label
            setOnClickListener { onClick() }
            onLongClick?.let { action -> setOnLongClickListener { action(); true } }
            // Every icon is cut to the circle, so square legacy icons match adaptive ones.
            if (icon != null) addView(android.widget.ImageView(this@MainActivity).apply {
                setImageDrawable(icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) { outline.setOval(0, 0, view.width, view.height) }
                }
                clipToOutline = true
            }, FrameLayout.LayoutParams(size * 4 / 5, size * 4 / 5, android.view.Gravity.CENTER))
            else addView(ui.label("+", 22f).apply {
                setBackgroundColor(Color.TRANSPARENT); gravity = android.view.Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(-1, -1))
        }
        return FrameLayout(this).apply { addView(key, FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER)) }
    }

    // Offers explicit reorder/remove actions without requiring drag gestures or accidental removal.
    private fun favouriteActions(flat: String, label: String, refresh: () -> Unit) {
        val items = state.favourites()
        val index = items.indexOf(flat)
        if (index < 0) return
        val actions = mutableListOf<String>()
        if (index > 0) actions.add("Move earlier")
        if (index < items.lastIndex) actions.add("Move later")
        actions.add("Remove favourite")
        AlertDialog.Builder(this).setTitle(label).setItems(actions.toTypedArray()) { _, selected ->
            when (actions[selected]) {
                "Move earlier" -> state.moveFavourite(flat, -1)
                "Move later" -> state.moveFavourite(flat, 1)
                else -> state.toggleFavourite(flat)
            }
            refresh()
        }.setNegativeButton("Cancel", null).show()
    }

    // Opens the same live favourite editor used by the app drawer.
    private fun manageFavourites() { drawer(editFavourites = true) }

    // Hosts the alphabetical drawer; DrawerPanel owns search, remote focus and favourite editing.
    private fun drawer(editFavourites: Boolean = false) {
        begin("Apps")
        // Settings opened fresh from All Apps starts with an empty search box.
        val panel = DrawerPanel(this, state, ui, { launch(it) }, { settingsQuery = ""; settings() }, { home() }, guidedSetup = guideActive)
        drawerPanel = panel
        root.addView(panel.view, LinearLayout.LayoutParams(-1, 0, 1f))
        if (editFavourites) panel.beginFavouriteEditing()
        drawerGestureAreas.add((panel.view.getChildAt(0) as ViewGroup).getChildAt(0))
    }

    // Launches an installed application without claiming control of its saved session.
    private fun launch(component: ComponentName) {
        runCatching {
            if (state.landscapeApps && root.width > root.height) RemoteActionService.instance?.prepareLandscape(component.packageName)
            startActivity(Intent.makeMainActivity(component).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (choosingPreview || (!state.followMediaApps && state.lastApp() == null)) {
                if (choosingPreview) state.followMediaApps = false
                state.rememberApp(component.flattenToString())
                state.viewerSource = "App"
            }
            choosingPreview = false
            home()
        }.onFailure { message("This app is unavailable.") }
    }

    // Scans the selected folder off the UI thread, ignoring results from screens already left.
    private fun library() {
        begin("Library")
        root.addView(ui.setting("Choose a folder", "${if (state.mode == "Read") "Books and documents" else "Videos"} stored on this device") { pickFolder() })
        if (state.mode == "Watch") root.addView(ui.button("Open video link") { directLink() })
        val folder = state.folder(state.mode) ?: return
        val generation = scanGeneration
        val mode = state.mode
        val list = ui.column()
        val status = ui.label("Loading library…")
        list.addView(status)
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        worker.execute {
            val result = runCatching { LocalLibrary(this).scan(Uri.parse(folder), mode) }
            runOnUiThread {
                if (isDestroyed || generation != scanGeneration) return@runOnUiThread
                list.removeAllViews()
                result.onSuccess { items ->
                    if (mode == "Watch") state.setWatchQueue(items)
                    if (items.isEmpty()) list.addView(ui.label("No supported files in this folder."))
                    items.forEach { item -> list.addView(ui.setting(item.title,
                        if (mode == "Read") ReadingFormats.viewingLabel(item.title, item.mime, state.readInLauncher)
                        else if (state.playVideosInLauncher) "Plays inside launcher" else "Opens in a video app") { openItem(item) }) }
                }.onFailure { list.addView(ui.label("Folder access unavailable. Choose the folder again.")) }
            }
        }
    }

    // Opens a selected item inside the home panel and starts explicitly requested video playback.
    private fun openItem(item: LibraryItem) {
        state.remember(item); state.mode = item.mode; state.viewerSource = "Folder"
        val inline = if (item.mode == "Watch") state.playVideosInLauncher
            else state.readInLauncher && ReadingFormats.canRenderInline(item.title, item.mime)
        if (inline && state.fullscreenMedia) { viewer(item); return }
        home()
        if (item.mode == "Watch" && state.playVideosInLauncher) watch?.load(item, true)
    }

    // Requests read-only access to a user-chosen folder through Android's system picker.
    private fun pickFolder() {
        pickerMode = state.mode
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 100)
    }

    // Saves only the grant actually returned by the system and handles cancellation safely.
    @Deprecated("Legacy Activity result API retained for the minimal native Activity host")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 205 || requestCode == 206) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                if (requestCode == 205) backupWork("Backup saved") { LauncherBackup.export(this, uri) }
                else worker.execute {
                    val result = runCatching { LauncherBackup.load(this, uri) }
                    runOnUiThread { if (!isFinishing && !isDestroyed) result.onSuccess { document ->
                        AlertDialog.Builder(this).setTitle("Restore this backup?")
                            .setMessage("This replaces backed-up settings, all favourite lists, macros and remote assignments. Book/video files and Android permissions are not restored. Your wallpaper and font stay as they are. Export your current setup first if you want to keep it.")
                            .setNegativeButton("Cancel", null).setPositiveButton("Restore") { _, _ ->
                                if (RemoteActionService.instance?.macroBusy == true) message("Stop macro playback or recording before restoring.")
                                else backupWork("Restored. Review Status & repair for permissions and folders.") { LauncherBackup.restore(this, document) }
                            }.show()
                    }.onFailure { message(it.message ?: "This backup could not be read.") } }
                }
            }
            return
        }
        if (requestCode == 204) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                worker.execute {
                    val failure = runCatching { LauncherFont.importFont(this, uri) }.exceptionOrNull()
                    runOnUiThread { if (!isDestroyed && !isFinishing) {
                        if (failure == null) settings("Appearance") else message("Could not load this font. Choose a valid TTF or OTF file under 8 MB.")
                    } }
                }
            }
            return
        }
        if (requestCode == ImageTapAction.REQUEST) {
            ImageTapAction.accept(this, resultCode, data) { name -> macroSettings(); MacroEditor(this) { macroSettings() }.show(name) }
            return
        }
        if (requestCode == ProjectionImageAction.REQUEST) {
            ProjectionImageAction.accept(this, resultCode, data) { macroSettings() }
            return
        }
        if (requestCode == 203) {
            MacroEditor.handleOverlayResult(this, resultCode, data)
            return
        }
        if (requestCode == 202) {
            if (resultCode == RESULT_OK && data?.data != null) {
                runCatching {
                    val uri = data.data!!
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("wallpaperUri", uri.toString()).apply()
                    state.useWallpaper = true; settings()
                }.onFailure { message("This image could not be kept. Choose another wallpaper.") }
            }
            return
        }
        if (requestCode == 201) {
            if (resultCode == RESULT_OK && data != null) {
                state.watchPreviewExpected = true
                startForegroundService(Intent(this, PreviewCaptureService::class.java).putExtra("consent", data))
                root.postDelayed({ if (!isFinishing) { if (screen == "Welcome") introduction(guideStep) else home() } }, 150)
            }
            return
        }
        if (requestCode != 100 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            require(data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "No read grant returned" }
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            state.setFolder(pickerMode, uri.toString()); state.mode = pickerMode; library()
        }.onFailure { message("Folder access could not be saved. Please choose another folder.") }
    }

    // Accepts HTTPS media URLs; web page links need a compatible provider instead.
    private fun directLink() {
        val input = EditText(this).apply { hint = "https://…/video.mp4"; inputType = 17 }
        AlertDialog.Builder(this).setTitle("Open direct video link").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Open") { _, _ ->
                val uri = Uri.parse(input.text.toString().trim())
                if (uri.scheme != "https" || uri.host.isNullOrBlank()) message("Enter a valid HTTPS media link.")
                else openItem(LibraryItem(uri.toString(), uri.lastPathSegment ?: "Video", "video/*", "Watch"))
            }.show()
    }

    // Switches between the two user-selected modes while keeping independent resume positions.
    private fun chooseMode() {
        if (state.readerOnly || state.deviceProfile != "Phone" || BuildConfig.FLAVOR == "tv") return
        val automatic = state.deviceProfile == "Phone" && BuildConfig.FLAVOR != "tv"
        val choices = if (automatic) arrayOf("Follow rotation · portrait Read, landscape Watch", "Watch in either orientation", "Read in either orientation")
            else arrayOf("Watch", "Read")
        AlertDialog.Builder(this).setTitle("Home content").setItems(choices) { _, index ->
            state.automaticPhoneMode = automatic && index == 0
            if (!state.automaticPhoneMode) state.mode = if (index == (if (automatic) 1 else 0)) "Watch" else "Read"
            applyOrientation(); settings("Home & content")
        }.show()
    }

    // Exposes genuine settings; unsupported hardware actions are not presented as working switches.
    private var settingsCategory: String? = null
    private var settingsQuery = ""
    private var settingsScroll: ScrollView? = null

    private fun settings(category: String? = settingsCategory) {
        // A switch rebuilds this page; keep the user's place instead of jumping to the top.
        val keepScroll = if (screen == "Settings" && category == settingsCategory) settingsScroll?.scrollY ?: 0 else 0
        if (category != settingsCategory) settingsQuery = ""
        settingsCategory = category
        begin("Settings")
        val content = ui.column()
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val body = ui.column(wide).apply { tag = "settingsBody" }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val scroll = ScrollView(this).apply { addView(content) }
        settingsScroll = scroll
        body.addView(scroll, if (wide) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, -1))
        content.addView(ui.section("Appearance"))
        if (!state.readerOnly) content.addView(ui.setting("Mode", profileLabel(state.deviceProfile) + " · " + when (state.deviceProfile) {
            "TV" -> "videos in landscape"; "Reader" -> "books in portrait"; else -> "read upright, watch sideways" }) { chooseDeviceMode() })
        content.addView(ui.setting("Colours", if (state.separateThemes) "${state.theme} · ${state.mode} theme" else state.theme) { themeSettings() })
        if (!state.readerOnly && BuildConfig.FLAVOR != "tv" && state.deviceProfile == "Phone") {
            content.addView(ui.setting("Separate Read / Watch themes",
                if (state.separateThemes) "Each mode keeps its own colours and icon style; rotate to edit the other" else "Use the same colours in both modes",
                if (state.separateThemes) "On" else "Off") {
                state.separateThemes = !state.separateThemes; settings()
            })
        }
        content.addView(ui.setting("Font", if (LauncherFont.get(this) == null) "Android default" else "Your imported font") {
            AlertDialog.Builder(this).setTitle("Launcher font").setItems(arrayOf("Import TTF or OTF file", "Use Android default")) { _, index ->
                if (index == 1) { LauncherFont.reset(this); settings() }
                else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 204)
            }.setNegativeButton("Cancel", null).show()
        })
        content.addView(ui.setting("App icon", AppIconTheme.currentLabel(this)) {
            AppIconTheme.showChooser(this) { settings() }
        })
        content.addView(ui.setting("View wallpaper", "Show your background behind Home, apps and settings", if (state.useWallpaper) "On" else "Off") {
            state.useWallpaper = !state.useWallpaper; settings()
        })
        content.addView(ui.row("Choose launcher wallpaper    ›") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 202)
        })
        content.addView(ui.label("Choose an image here for reliable landscape cropping. Android may restrict access to its system wallpaper.", 14f))
        if (getSharedPreferences("launcher", MODE_PRIVATE).contains("wallpaperUri")) content.addView(ui.row("Use system wallpaper    ›") {
            getSharedPreferences("launcher", MODE_PRIVATE).edit().remove("wallpaperUri").apply(); settings()
        })
        content.addView(ui.row("Choose Android wallpaper    ›") {
            runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper")) }
                .onFailure { message("No wallpaper picker is available on this device.") }
        })
        content.addView(ui.setting("Rounded corners", "Angular by default", if (state.roundedCorners) "On" else "Off") { state.roundedCorners = !state.roundedCorners; settings() })
        content.addView(ui.setting("Animations", "Short page transitions", if (state.animations) "On" else "Off") { state.animations = !state.animations; settings() })
        content.addView(ui.setting("Icon pack", IconPacks.selectedLabel(this)) {
            val packs = listOf("" to "System icons") + IconPacks.listInstalled(this)
            AlertDialog.Builder(this).setTitle("Icon pack").setItems(packs.map { it.second }.toTypedArray()) { _, index ->
                getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("iconPack", packs[index].first).apply(); settings()
            }.setNegativeButton("Cancel", null).show()
        })
        content.addView(ui.section("Favourites"))
        content.addView(ui.setting("Icon style", if (state.circles) "Circles · small round keys" else if (state.grid) "Squares · icon blocks" else "List · icon and app name",
            if (state.circles) "Circles" else if (state.grid) "Squares" else "List") {
            val styles = arrayOf("List · icon and app name", "Squares · icon blocks", "Circles · small round keys")
            AlertDialog.Builder(this).setTitle("Icon style").setItems(styles) { _, index ->
                state.circles = index == 2; state.grid = index != 0; settings()
            }.setNegativeButton("Cancel", null).show()
        })
        content.addView(ui.setting("App labels", "Show names beside list icons", if (state.showLabels) "On" else "Off") {
            state.showLabels = !state.showLabels; settings()
        })
        content.addView(ui.setting("Layout & block count", "${state.favouriteCount} blocks · ${if (state.asymmetricBlocks) "asymmetrical" else "symmetrical"}") { favouriteSettings() })
        content.addView(ui.row("Arrange favourites    ›") { manageFavourites() })
        if (!state.readerOnly && BuildConfig.FLAVOR != "tv" && state.deviceProfile == "Phone") {
            content.addView(ui.setting("Separate Read / Watch favourites",
                if (state.separateFavourites) "Editing ${state.mode} favourites. Your shared list is kept." else "Use the same favourites in both modes",
                if (state.separateFavourites) "On" else "Off") {
                state.separateFavourites = !state.separateFavourites; settings()
            })
        }
        content.addView(ui.setting("Favourites heading", "Show the heading above home shortcuts", if (state.showFavouriteHeading) "On" else "Off") {
            state.showFavouriteHeading = !state.showFavouriteHeading; settings()
        })
        content.addView(ui.section("Home viewer"))
        content.addView(ui.setting("Quick layout", viewerLayoutName() + " · each option below can also be changed on its own") { chooseViewerLayout() })
        content.addView(ui.setting("Buttons on the viewer",
            if (state.tapViewer) "Hidden · tap the centre to continue; on pages tap left to go back"
            else "Shown · play, next and page controls on the viewer",
            if (state.tapViewer) "Hidden" else "Shown") {
            state.tapViewer = !state.tapViewer; settings()
        })
        content.addView(ui.setting("Progress bar", "How much of the book or video you have read or watched", if (state.showProgress) "On" else "Off") {
            state.showProgress = !state.showProgress; settings()
        })
        content.addView(ui.setting("Own books and videos",
            if (state.fullscreenMedia) "Open full screen from the Home preview" else "Read and play between your favourites",
            if (state.fullscreenMedia) "Full screen" else "Inside Home") {
            state.fullscreenMedia = !state.fullscreenMedia; settings()
        })
        if (state.mode == "Read") content.addView(ui.setting("Previous / Next buttons",
            if (state.tapViewer) "Hidden while viewer buttons are hidden" else "Page buttons under the reading panel",
            if (state.showReaderControls) "On" else "Off") {
            state.showReaderControls = !state.showReaderControls; settings()
        })
        content.addView(ui.setting("Content sizing", "${if (state.contentScale == "Fill") "Crop to fill" else state.contentScale} · ${if (state.readerOnly) "page previews and documents" else "preview, documents and videos"}") { chooseContentScale() })
        if (state.mode == "Watch" && state.deviceProfile != "Reader") {
        val threshold = ui.label("Next episode after ${state.completion}%", 18f)
        content.addView(threshold)
        content.addView(SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, ui.dp(48))
            progressTintList = android.content.res.ColorStateList.valueOf(ui.focusAccent)
            thumbTintList = android.content.res.ColorStateList.valueOf(ui.focusAccent)
            max = 100; progress = state.completion
            contentDescription = "Episode completion threshold"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                // Updates the threshold preview while dragging or using directional controls.
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    state.completion = value; threshold.text = "Next episode after $value%"
                }
                // The slider needs no special start behavior.
                override fun onStartTrackingTouch(bar: SeekBar) {}
                // Changes are already persisted as the slider moves.
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        content.addView(ui.label("Videos played here prepare the next file when you leave or turn the screen off. For other apps, the next Play request advances after this percentage when the player supplies duration and a Next control. Supported players can use their visible Next button with accessibility enabled. If no next episode is available, the current episode resumes. The other app still owns loading and buffering.", 16f))
        }
        content.addView(ui.section("Home & content"))
        if (!state.readerOnly && state.deviceProfile == "Phone" && BuildConfig.FLAVOR != "tv") content.addView(ui.setting("Home content", if (state.automaticPhoneMode) "Follow rotation: upright Read, sideways Watch" else "${state.mode} in either orientation") { chooseMode() })
        content.addView(ui.setting("Screen rotation", state.orientation, "Change") {
            val choices = arrayOf("Auto", "Landscape", "Portrait")
            AlertDialog.Builder(this).setTitle("Screen rotation").setItems(choices) { _, index ->
                state.orientation = choices[index]; applyOrientation(); settings()
            }.setNegativeButton("Cancel", null).show()
        })
        content.addView(ui.setting("Preview source", if (state.viewerSource == "App") "Resume your last app" else "Files from your chosen folder") { chooseViewerSource() })
        content.addView(ui.setting("Browse local files", "Open ${if (state.mode == "Read") "books and documents" else "videos"} from your selected folder") { library() })
        if (state.mode == "Watch" && state.deviceProfile != "Reader") content.addView(ui.setting("Play local videos here", "For your selected files or direct links; app previews resume their own player", if (state.playVideosInLauncher) "On" else "Off") {
            state.playVideosInLauncher = !state.playVideosInLauncher; settings()
        })
        if (state.mode == "Read") content.addView(ui.setting("Open documents inside launcher", "PDF, EPUB, CBZ and images. Other formats open a reading app.", if (state.readInLauncher) "On" else "Off") {
            state.readInLauncher = !state.readInLauncher; settings()
        })
        content.addView(ui.setting("Keep apps ready", keepReadyStatus()) { keepAppsReady() })
        content.addView(ui.setting("App previews", if (state.readerOnly) "Save your last reading page on this device" else "Follow your last video or book; save a still picture locally") { previewSettings() })
        if (state.deviceProfile != "Reader") content.addView(ui.row("Playback permissions & controls    ›") { externalPlayback() })
        content.addView(ui.section("Display"))
        content.addView(ui.setting("Fullscreen", "Hide system bars; swipe an edge to reveal them", if (state.fullscreen) "On" else "Off") {
            state.fullscreen = !state.fullscreen; settings()
        })
        if (state.mode == "Watch" && state.deviceProfile != "Reader") content.addView(ui.setting("Open apps in landscape", "Match landscape Home; request player fullscreen when available. Requires accessibility support.", if (state.landscapeApps) "On" else "Off") {
            state.landscapeApps = !state.landscapeApps
            if (!state.landscapeApps) RemoteActionService.instance?.cancelLandscape()
            settings()
        })
        if (state.deviceProfile != "Reader") content.addView(ui.setting("Second Screen", "Connected displays and device information") { diagnostics() })
        content.addView(ui.row("Set as default launcher    ›") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) })
        if (!state.readerOnly && state.deviceProfile != "Reader") {
            content.addView(ui.section("Remote & macros"))
            content.addView(ui.setting("Remote buttons", "Choose an action on the remote diagram, then press your physical button") { remoteSettings() })
            content.addView(ui.setting("Macros", "Record a sequence, choose how it runs, then assign a button") { macroSettings() })
        }
        content.addView(ui.section("Setup & help"))
        content.addView(ui.setting("Status & repair", "Check previews, permissions and your selected source") { statusSettings() })
        content.addView(ui.setting("Backup & restore", if (state.readerOnly) "Save reading settings and favourites" else "Save settings, favourites, remote buttons and macros") { backupSettings() })
        content.addView(ui.button("Introduction and setup") { introduction() })
        content.addView(ui.button("Android display settings") { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) })
        if (state.deviceProfile != "Reader") content.addView(ui.label("Mirror / desktop mode is controlled by your phone's projection menu when a compatible display is connected. Screen off locks the phone; an independent desktop display may remain on.", 14f))
        content.addView(ui.button("Device diagnostics") { diagnostics() })
        content.addView(ui.label("Snapplication · Private development\nAll features free. No launcher advertisements or paid feature gates.", 16f))
        organizeSettings(content)
        if (keepScroll > 0) scroll.post { scroll.scrollTo(0, keepScroll) }
    }

    // Provides an explicit portable backup workflow without copying media or pretending to restore
    // Android grants.
    private fun backupSettings() {
        begin("Backup & restore")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.label("Keep a copy of your setup", 22f))
        content.addView(ui.label(if (state.readerOnly) "Includes reading settings and favourites. Book files, saved page pictures, wallpaper and custom fonts are not included. Approve Android permissions and folder access on each device." else "Includes settings, shared and separate favourites, remote assignments, macros and their reference pictures. The file can contain recorded text and screenshots: keep it somewhere private. Media files, saved previews, wallpaper and custom fonts are not included. Android permissions and folder access must be approved on each device.", 16f))
        content.addView(ui.button("Export backup") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "snapplication-backup.json"), 205)
        })
        content.addView(ui.button("Restore backup") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE), 206)
        })
        content.addView(ui.button("Status & repair") { statusSettings() })
    }

    // Keeps file work off the UI thread and reapplies restored mode/theme only after a successful
    // operation.
    private fun backupWork(success: String, operation: () -> Unit) {
        worker.execute {
            val result = runCatching(operation)
            runOnUiThread { if (!isFinishing && !isDestroyed) {
                result.onSuccess {
                    remote.clearHeldButtons()
                    RemoteActionService.instance?.refreshReadingSnapshots()
                    applyOrientation()
                    if (state.mode == "Read") stopService(Intent(this, PreviewCaptureService::class.java))
                    backupSettings(); message(success)
                }.onFailure { message(it.message ?: "Backup operation failed. Your setup was not replaced.") }
            } }
        }
    }

    // Shows the last run without claiming an interrupted process is still executing.
    private fun macroRunSummary(): String {
        val prefs = getSharedPreferences("remoteMacros", MODE_PRIVATE)
        val status = prefs.getString("lastRunStatus", "No macro has run yet.")!!
        return if (prefs.getBoolean("runInProgress", false) && RemoteActionService.instance?.replayActive != true)
            "$status · Interrupted; run it again when ready" else status
    }

    // Consolidates existing setup actions; Reader uses still screenshots and never offers Watch
    // capture.
    private fun statusSettings() {
        begin("Status & repair")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val service = RemoteActionService.instance
        content.addView(ui.label("${state.mode} · ${profileLabel(state.deviceProfile)} mode", 22f))
        val defaultHome = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == packageName
        content.addView(ui.setting("Default launcher", if (defaultHome) "Snapplication is your Home app" else "Another Home app is selected", "Choose") {
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.onFailure { message("Home app settings are unavailable on this device.") }
        })
        content.addView(ui.setting("Accessibility support", if (service == null) "Not connected · enable in Android settings" else "Connected", "Review") { explainAccessibility() })
        val preview = if (state.mode == "Read") when {
            android.os.Build.VERSION.SDK_INT < 30 -> "Still page capture needs Android 11 or newer"
            !state.readPagePreviews -> "Off · enable automatic still pages"
            service == null -> "Needs accessibility support"
            service.serviceInfo.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0 -> "Refresh accessibility permission"
            else -> "Ready for supported reading pages"
        } else if (PreviewCaptureService.active) "Screen capture active" else "Screen capture off · Android approval needed to restart"
        content.addView(ui.setting("Preview capture", preview, "Review") { previewSettings() })
        if (state.mode == "Watch" && !PreviewCaptureService.active) content.addView(ui.button("Repair previews") { repairPreviews() })
        val owner = state.lastApp()?.let(ComponentName::unflattenFromString)?.packageName
        val ownerLabel = owner?.let { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() }.getOrDefault("Selected app unavailable") }
        val time = owner?.let { getSharedPreferences("externalPreview", MODE_PRIVATE).getLong("capturedAt:${state.mode}:$it", 0) } ?: 0L
        content.addView(ui.label(if (time > 0) "Last saved preview: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))}" else "No saved app preview in this mode yet.", 16f))
        content.addView(ui.setting("Preview source", if (state.viewerSource == "App") "${if (state.followMediaApps) "Follow last supported app" else "Fixed app"} · ${ownerLabel ?: "No app selected yet"}" else "Files from your chosen folder", "Change") { chooseViewerSource() })
        val folder = state.folder(state.mode)
        val grant = folder != null && contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == folder }
        content.addView(ui.setting("Local folder", if (grant) "Access granted · browse to check files" else if (folder == null) "Not selected · optional for app previews" else "Access missing · choose the folder again", "Choose") { pickFolder() })
        if (!state.readerOnly && state.deviceProfile != "Reader") {
            val sessions = runCatching { getSystemService(android.media.session.MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(this, MediaAccessService::class.java)) }
            content.addView(ui.setting("Media controls", if (sessions.isSuccess) "Access granted · ${sessions.getOrNull()?.size ?: 0} active sessions" else "Optional media access not enabled", "Review") { externalPlayback() })
            content.addView(ui.label(macroRunSummary(), 16f).apply { tag = "macroRunSummary" })
            content.addView(ui.button("Remote & macros") { settings("Remote & macros") })
        }
        content.addView(ui.button("Refresh status") { statusSettings() })
    }

    // Presents one category at a time; search spans every category without hiding its matching
    // controls.
    private fun organizeSettings(content: LinearLayout) {
        val groups = linkedMapOf<String, MutableList<View>>()
        var group = "Appearance"
        val names = setOf("APPEARANCE", "FAVOURITES", "HOME VIEWER", "HOME & CONTENT", "DISPLAY", "REMOTE & MACROS", "SETUP & HELP")
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child is android.widget.TextView && child.text.toString() in names) group = child.text.toString().lowercase().replaceFirstChar { it.uppercase() }
            groups.getOrPut(group) { mutableListOf() }.add(child)
        }
        val categories = ui.column()
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (wide) {
            val body = root.findViewWithTag<LinearLayout>("settingsBody")
            // Over a wallpaper the rail gets a text backing so names stay readable on bright areas.
            body.addView(ScrollView(this).apply { addView(categories); setBackgroundColor(ui.textSurface()) }, 0, LinearLayout.LayoutParams(ui.dp(192), -1))
        } else content.addView(categories, 0, LinearLayout.LayoutParams(-1, -2))
        val search = EditText(this).apply {
            hint = "Search all settings"; setText(settingsQuery); setTextColor(ui.text); setHintTextColor(ui.muted)
            setSingleLine(true); setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)); setBackgroundColor(ui.surface)
            // Keeps matching settings visible above the keyboard in landscape.
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        }
        root.addView(search, 1, LinearLayout.LayoutParams(-1, ui.dp(52)))
        // Single-line EditText consumes DPAD_DOWN as cursor movement on some Huawei keyboards.
        // Give remote users an explicit route into the visible settings, keeping normal text
        // editing.
        search.setOnKeyListener { _, code, event ->
            if (code != android.view.KeyEvent.KEYCODE_DPAD_DOWN) false
            else {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    content.getFocusables(View.FOCUS_DOWN).firstOrNull { it.isShown && it.isEnabled && it !== content }
                        ?.requestFocus()
                }
                true
            }
        }
        val summaries = mapOf("Appearance" to "Colours, wallpaper, font and icons", "Favourites" to "Icon style, labels and arrangement",
            "Home viewer" to "Buttons, progress bar and full screen",
            "Home & content" to "Read or Watch, sources, files and permissions", "Display" to "Fullscreen, external apps and connected displays",
            "Remote & macros" to "Button assignments and recorded actions", "Setup & help" to "Setup guide, permissions and diagnostics")
        fun words(view: View): String = if (view is android.widget.TextView) view.text.toString() else if (view is ViewGroup)
            (0 until view.childCount).joinToString(" ") { words(view.getChildAt(it)) } else ""
        // Extra search words for settings people describe differently from their labels.
        val synonyms = mapOf("View wallpaper" to "transparent transparency translucent glass see-through background",
            "Colours" to "theme dark light oled black e-paper eink colour color",
            "Buttons on the viewer" to "tap-only no buttons minimal clean tap click touch centre",
            "Progress bar" to "played watched read amount position",
            "Icon style" to "round circles square grid list shape",
            "Mode" to "device setup watch read universal profile",
            "Keep apps ready" to "battery optimisation background killed closed cleared memory speed fast lock",
            "Previous / Next buttons" to "page turn controls arrows",
            "Content sizing" to "fit fill crop stretch zoom scale",
            "Fullscreen" to "immersive status bar navigation bar",
            "App labels" to "names text",
            "Layout & block count" to "grid list blocks layout count")
        fun searchText(view: View): String = words(view).let { text ->
            text + " " + synonyms.filterKeys { text.startsWith(it) }.values.joinToString(" ")
        }
        fun update(query: String) {
            categories.removeAllViews()
            if (wide) {
                groups.keys.forEach { name ->
                    val selected = query.isBlank() && name == (settingsCategory ?: "Home & content")
                    categories.addView(ui.button(name) { settings(name) }.apply {
                        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                        textSize = 15f
                        setPadding(ui.dp(12), 0, ui.dp(12), 0)
                        setTextColor(if (selected) ui.text else ui.muted)
                        isSelected = selected
                        contentDescription = if (selected) "$name, selected" else name
                        ui.flatFocus(this, if (selected) ui.surface else android.graphics.Color.TRANSPARENT)
                    }, LinearLayout.LayoutParams(-1, ui.dp(48)))
                }
            } else if (query.isBlank() && settingsCategory == null) {
                groups.keys.forEach { name -> categories.addView(ui.setting(name, summaries[name].orEmpty()) { settings(name) }) }
            } else if (query.isBlank()) {
                categories.addView(ui.row("← All settings") { settings(null) })
            }
            groups.forEach { (name, rows) -> rows.forEach { row ->
                row.visibility = if (if (query.isBlank()) name == (settingsCategory ?: if (wide) "Home & content" else null) else searchText(row).contains(query, true)) View.VISIBLE else View.GONE
            } }
        }
        search.addTextChangedListener(object : TextWatcher {
            // Filters settings across categories as the user types.
            override fun afterTextChanged(value: Editable?) { settingsQuery = value.toString(); update(settingsQuery.trim()) }
            // No work is needed before an edit.
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            // Filtering runs after the completed edit.
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        update(settingsQuery.trim())
    }

    // Shows the reference's separate theme selection page with immediate persistent selection.
    private fun themeSettings() {
        begin("Theme Mode")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (state.separateThemes) content.addView(ui.label("Choosing the ${state.mode} theme. Rotate the phone to choose the other mode's theme.", 15f))
        val themes = listOf("OLED Black", "Dark", "Light", "Material You", "Custom", "E-paper")
        val backgrounds = listOf(Color.BLACK, 0xff1a1a1a.toInt(), 0xfffafafa.toInt(), 0xff1c1b1f.toInt(), state.background, Color.WHITE)
        val descriptions = listOf("True black OLED surfaces", "Soft dark grey surfaces", "Light grey and white surfaces",
            "Android accent colours", "Choose background and accent", "High contrast reading")
        themes.forEachIndexed { index, theme ->
            val row = ui.setting(theme, descriptions[index], if (state.theme == theme) "●" else "○") {
                if (theme == "Custom") customColours() else { state.theme = theme; themeSettings() }
            }
            row.addView(WireframePreview(this, backgrounds[index], if (index == 2 || index == 5) Color.BLACK else Color.WHITE),
                0, LinearLayout.LayoutParams(ui.dp(36), ui.dp(62)).apply { marginEnd = ui.dp(16) })
            content.addView(row)
        }
    }

    // Groups display style and four/six/eight choices as in the Make reference.
    private fun favouriteSettings() {
        begin("Favourites Style")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.section("Display Style"))
        listOf("List" to "List · icon and app name", "Grid" to "Grid · compact icon blocks", "Circles" to "Circles · small round keys with space between").forEach { (style, text) ->
            val selected = when (style) { "Circles" -> state.circles; "Grid" -> !state.circles && state.grid; else -> !state.circles && !state.grid }
            content.addView(ui.row("${if (selected) "●" else "○"}   $text") {
                state.circles = style == "Circles"; state.grid = style != "List"; favouriteSettings()
            })
        }
        content.addView(ui.setting("Dynamic blocks", "Adapt blocks to the number of favourites", if (state.dynamicBlocks) "On" else "Off") { state.dynamicBlocks = !state.dynamicBlocks; favouriteSettings() })
        content.addView(ui.setting("Dynamic layout", if (state.asymmetricBlocks) "Asymmetrical · merge unused space" else "Symmetrical · equal blocks") { state.asymmetricBlocks = !state.asymmetricBlocks; favouriteSettings() })
        content.addView(ui.setting("Experimental block limit", "Up to 24; small targets on phones", if (state.experimentalBlocks) "24" else "8") { state.experimentalBlocks = !state.experimentalBlocks; favouriteSettings() })
        content.addView(ui.section("Maximum blocks"))
        val counts = ui.column(true)
        (if (state.experimentalBlocks) listOf(8, 12, 16, 24) else listOf(4, 6, 8)).forEach { count ->
            ui.addWeighted(counts, ui.button("${if (state.favouriteCount == count) "● " else ""}$count blocks") {
                state.favouriteCount = count; favouriteSettings()
            })
        }
        content.addView(counts)
        content.addView(ui.label("Portrait: ${state.favouriteCount / 2} columns × 2 rows. Landscape: ${state.favouriteCount / 2} favourites per side.", 14f))
    }

    // Opens optional external media controls without loading providers or taking ownership of their
    // playback.
    private fun externalPlayback() {
        begin("Other app playback")
        val panel = ExternalPlaybackPanel(this, ui)
        root.addView(ScrollView(this).apply { addView(panel.view) }, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.refresh()
    }

    // Shows a refreshable hardware snapshot without registering background monitors or requiring
    // root.
    private fun diagnostics() {
        begin("Device diagnostics")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val values = ui.label("", 17f)
        val reader = DeviceDiagnostics(this)
        val refresh = ui.button("Refresh device information") { values.text = reader.snapshot() }
        content.addView(refresh)
        values.text = reader.snapshot()
        content.addView(values)
        content.addView(ui.label("These are Android-reported values at the last refresh. Independent display power and e-paper refresh depend on the connected hardware.", 16f))
    }

    // Presents the visual remote and individual key assignments using RemoteMappingPanel.
    private fun remoteSettings() {
        if (state.readerOnly) { settings(); return }
        begin("Remote buttons")
        val panel = RemoteMappingPanel(this, ui, remote).build(
            onAccessibility = { explainAccessibility() },
            onRecordAction = { recordMacro() },
            onManageActions = { macroSettings() },
            onChanged = { remoteSettings() },
            onHome = { home() })
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    // Keeps the mode/colour chooser first, then teaches on the actual launcher screens.
    private fun introduction(step: Int = 0) {
        if (!state.profileSetupComplete) {
            guideActive = false
            begin("Welcome")
            root.addView(DeviceProfileSetup(this, state).build(
                { applyOrientation(); introduction(0) }, { applyOrientation(); introduction(0) }),
                LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        guideActive = true
        state.tutorialSeen = true
        guideStep = step.coerceIn(0, 4)
        saveGuide()
        showGuideStep()
    }

    // Saves progress before Android permission screens or activity recreation can interrupt setup.
    private fun saveGuide() {
        getSharedPreferences("launcherGuide", MODE_PRIVATE).edit()
            .putBoolean("active", guideActive).putInt("step", guideStep).apply()
    }

    // Opens real interactive destinations; the guide never substitutes a demonstration for a saved
    // choice.
    private fun showGuideStep() {
        saveGuide()
        when (guideStep) {
            1 -> drawer(editFavourites = true)
            3 -> if (state.mode == "Read") readingPreviewSettings() else previewSettings()
            4 -> settings()
            else -> home()
        }
    }

    // Docks the coach beside the working screen in landscape, preserving real app selection and
    // preview actions.
    private fun attachGuide() {
        guidePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        guidePanel = null
        if (!guideActive || screen == "Welcome") return
        val read = state.mode == "Read"
        val access = RemoteActionService.instance != null
        val title: String
        val detail: String
        val action: String
        val perform: () -> Unit
        when (guideStep) {
            0 -> {
                title = "This is your Home"
                detail = if (read) "Your last reading page appears in the centre. Select its picture to return to your book. The surrounding blocks open your apps."
                    else "Your last video appears in the centre. Select its picture to return to the app. The surrounding blocks open your favourites."
                action = "Show Home"; perform = { home() }
            }
            1 -> {
                title = "Choose your favourite apps"
                detail = "Select apps to add or remove them. Your selection appears in the live preview. Drag icons to reorder; with a remote, select an icon to move it."
                action = "Choose apps"; perform = { drawer(editFavourites = true) }
            }
            2 -> {
                title = "Let Home recognise your place"
                detail = if (access) "Support is connected. Next, choose how to save your preview. You can change these permissions later."
                    else "Open Android settings, switch on Snapplication support, then come back here. This optional permission recognises reading or video screens."
                action = if (access) "Review support" else "Enable support"
                perform = { explainAccessibility() }
            }
            3 -> {
                title = if (read) "Keep your last page" else "Keep your last video frame"
                detail = if (read) "Use Page previews for occasional still pictures, not recording. Android 11 or newer is needed. Read a page, then return Home to see it."
                    else if (PreviewCaptureService.active) "Screen capture is running. Watch a video, then return Home to see its last eligible frame. Protected video may appear black."
                    else "Choose Enable previews and approve Android's screen-sharing prompt. Only the latest eligible frame is kept locally. You can stop sharing anytime."
                action = if (read) "Page settings" else if (PreviewCaptureService.active) "Show Home" else "Enable previews"
                perform = { if (read) readingPreviewSettings() else if (!access) explainAccessibility()
                    else if (PreviewCaptureService.active) home() else requestPreviewCapture() }
            }
            else -> {
                title = "Make yourself at home"
                detail = "These are your real settings. Choose Snapplication as your default Home if you wish. You can restart this tour from Introduction and setup."
                action = "Default Home"
                perform = { runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                    .onFailure { message("Open Android settings and choose Default apps, then Home app.") } }
            }
        }
        guidePanel = LauncherWalkthrough(this, ui).build(guideStep, 5, title, detail, action, perform,
            onBack = { guideStep = (guideStep - 1).coerceAtLeast(0); showGuideStep() },
            onNext = { if (guideStep == 4) finishGuide() else { guideStep++; showGuideStep() } },
            onSkip = { finishGuide() })
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val currentParent = root.parent as? ViewGroup ?: return
        val shell = if (currentParent.tag == "launcherGuideShell") currentParent as LinearLayout else {
            val index = currentParent.indexOfChild(root)
            val oldParams = root.layoutParams
            currentParent.removeView(root)
            ui.column(wide).apply {
                tag = "launcherGuideShell"
                addView(root, if (wide) LinearLayout.LayoutParams(0, -1, 1f)
                    else LinearLayout.LayoutParams(-1, 0, 1f))
                currentParent.addView(this, index, oldParams)
            }
        }
        shell.addView(guidePanel, if (wide) LinearLayout.LayoutParams(ui.dp(280), -1)
            else LinearLayout.LayoutParams(-1, -2))
    }

    // Ends the optional tour without undoing the user's apps, theme or permission choices.
    private fun finishGuide() {
        guideActive = false; state.tutorialSeen = true; saveGuide(); home()
    }

    // Explains accessibility access before sending the user to Android's own opt-in control.
    private fun explainAccessibility() {
        AlertDialog.Builder(this).setTitle(if (state.readerOnly) "Reading page support" else "Remote and preview support")
            .setMessage(if (state.readerOnly) "This optional Android permission lets Snapplication identify reading pages and take occasional still screenshots when page previews are enabled. Pictures stay on this device. The E-reader edition does not remap buttons, record gestures or run screen recording. After updating, turn Snapplication E-reader support off and on if Android has not refreshed its screenshot permission. You can skip setup and still use the launcher." else "This optional Android permission can read on-screen text and controls and perform actions in other apps. Snapplication uses it to identify reading/video screens, take still reading-page previews, intercept your mapped remote keys, and record or replay actions only when you start them. Recorded steps and preview images stay on this device and are not uploaded. Watch previews require separate screen-sharing consent. You can skip this permission and still use the launcher, or disable access in Android settings at any time.")
            .setNegativeButton("Cancel", null).setPositiveButton("Open Android settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.show()
    }

    // Uses occasional accessibility screenshots for books; never starts projection as a fallback.
    private fun readingPreviewSettings() {
        begin("Page previews")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val supported = android.os.Build.VERSION.SDK_INT >= 30
        val service = RemoteActionService.instance
        val permitted = supported && service != null && service.serviceInfo.capabilities and
            android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        content.addView(ui.label("Still page previews", 22f))
        content.addView(ui.label("Save still pictures of your reading page without screen recording. Supported readers update after page-change events. Some readers need a periodic still-page check because they do not announce page turns. Pictures stay on this device.", 16f))
        if (!supported) content.addView(ui.label("Automatic still previews require Android 11 or newer. You can still open reading apps and use local PDF, CBZ and image pages.", 16f))
        else {
            content.addView(ui.setting("Page previews", "Occasional still screenshots", if (state.readPagePreviews && permitted) "On" else "Off") {
                if (state.readPagePreviews && permitted) state.readPagePreviews = false
                else {
                    state.readPagePreviews = true
                    if (!permitted) explainAccessibility()
                }
                RemoteActionService.instance?.refreshReadingSnapshots()
                readingPreviewSettings()
            })
            if (!permitted) content.addView(ui.button("Enable reading page support") { explainAccessibility() })
            if (android.os.Build.VERSION.SDK_INT >= 30) ReadingSnapshots.lastError?.let { content.addView(ui.label(it, 14f)) }
        }
        content.addView(ui.setting("Follow last reading app", "Switch previews as you read in different supported apps", if (state.followMediaApps) "On" else "Off") {
            state.followMediaApps = !state.followMediaApps; readingPreviewSettings()
        })
        if (!state.followMediaApps) content.addView(ui.button("Choose fixed preview app") { choosingPreview = true; drawer() })
        content.addView(ui.button("Picture sizing: ${if (state.contentScale == "Fill") "Crop" else state.contentScale}") { chooseContentScale() })
        content.addView(ui.label("Page previews can lag a few seconds. Readers that do not report changes, or block screenshots, may keep an older preview. Continuous recording is never started automatically.", 14f))
        content.addView(ui.button("Clear saved pictures") {
            PreviewCaptureService.clearSaved(this); readingPreviewSettings()
        })
    }

    // Offers Watch capture, automatic app following and optional manual selection.
    private fun previewSettings() {
        if (state.mode == "Read") { readingPreviewSettings(); return }
        begin("Home preview")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val enabled = RemoteActionService.instance != null
        val running = PreviewCaptureService.active
        content.addView(ui.label(if (running) "Previews are on" else "Previews are off", 22f))
        content.addView(ui.setting("Preview warnings", "Show a Repair previews button on Home when capture or accessibility stops", if (state.previewWarnings) "On" else "Off") {
            state.previewWarnings = !state.previewWarnings; previewSettings()
        })
        content.addView(ui.button("Repair previews") { repairPreviews() })
        content.addView(ui.label(if (state.readerOnly) "Save your last reading page on this device. Tap its picture on Home to return to the reading app." else "Keep a still picture of your last video or book. Tap it to return to that app. Pictures stay on this device. Protected video may be black.", 16f))
        content.addView(ui.setting(if (state.readerOnly) "Follow last reading app" else "Follow last media app", if (state.readerOnly) "Update the preview when you read in another supported app" else "Switch between supported players and readers as you use them", if (state.followMediaApps) "On" else "Off") {
            state.followMediaApps = !state.followMediaApps; previewSettings()
        })
        if (!state.followMediaApps) content.addView(ui.button("Choose fixed preview app") { choosingPreview = true; drawer() })
        content.addView(ui.button(if (running) "Turn previews off" else if (!enabled) "Set up previews" else "Turn previews on") {
            if (running) {
                state.watchPreviewExpected = false
                stopService(Intent(this, PreviewCaptureService::class.java))
                root.postDelayed({ if (!isFinishing) previewSettings() }, 150)
            } else if (!enabled) explainAccessibility() else requestPreviewCapture()
        })
        if (!running) content.addView(ui.label("Saved pictures remain available. Android may require screen-sharing approval again after a restart or update; use Turn previews on to resume updates.", 14f))
        if (!enabled) content.addView(ui.label("Setup: enable Snapplication in Android accessibility, return here, then turn previews on. Android will ask for screen-sharing permission.", 14f))
        content.addView(ui.button("Picture sizing: ${if (state.contentScale == "Fill") "Crop" else state.contentScale}") { chooseContentScale() })
        if (state.mode == "Watch") content.addView(ui.button("Enable episode controls") { externalPlayback() })
        content.addView(ui.button("Clear saved pictures") {
            PreviewCaptureService.clearSaved(this)
            root.postDelayed({ if (!isFinishing) previewSettings() }, 150)
        })
    }

    // Repairs the missing prerequisite first; screen sharing always uses fresh Android consent.
    private fun repairPreviews() {
        if (state.mode == "Read") { readingPreviewSettings(); return }
        if (RemoteActionService.instance == null) { explainAccessibility(); return }
        stopService(Intent(this, PreviewCaptureService::class.java))
        requestPreviewCapture()
    }

    // Requests Android's screen-sharing consent; capture accepts only the active media target.
    private fun requestPreviewCapture() {
        if (state.mode == "Read") { readingPreviewSettings(); return }
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        val request = if (android.os.Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        startActivityForResult(request, 201)
    }

    // Starts a named recording from either remote setup or the recorded-actions manager.
    private fun recordMacro(coordinates: Boolean? = null) {
            if (coordinates == null) {
                AlertDialog.Builder(this).setTitle("How should this macro run?")
                    .setItems(arrayOf("Match recorded screens · check the app before each touch", "Run on current screen · repeat touch positions without checking the app")) { _, index -> recordMacro(index == 1) }
                    .setNegativeButton("Cancel", null).show()
                return
            }
            val service = RemoteActionService.instance
            if (service == null) explainAccessibility()
            else {
                val name = EditText(this).apply { hint = "Action name"; setSingleLine(true) }
                val touch = android.widget.CheckBox(this).apply { text = "Record taps and swipes"; isChecked = getSharedPreferences("remoteMacros", MODE_PRIVATE).getBoolean("touchRecording", true) }
                if (coordinates) { touch.isChecked = true; touch.isEnabled = false }
                val form = ui.column().apply {
                    addView(ui.label(if (coordinates) "Run on current screen" else "Match recorded screens", 18f))
                    addView(name); addView(touch)
                }
                val dialog = AlertDialog.Builder(this).setTitle("Record an action").setView(form)
                    .setMessage("Name the macro, record your steps, then tap Stop to save. You can assign a remote button afterward. Touches replay after you lift your finger. Text is stored locally; passwords and protected screens are excluded. Current-screen mode repeats touch positions once without checking the app; text and image actions still need their targets.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Record", null).create()
                // Preserve the form on invalid names so the user can correct them without starting
                // over.
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val title = name.text.toString().trim()
                        // Accessibility can reconnect while this preserved form remains open.
                        val activeService = RemoteActionService.instance
                        if (title.isBlank() || title.length > 40 || title in RemoteActionService.savedMacroNames(this)) {
                            name.error = "Use a unique action name of 1–40 characters."
                        } else if (activeService == null) {
                            explainAccessibility()
                        } else if (touch.isChecked && !activeService.canRecordTouches()) {
                            AlertDialog.Builder(this).setTitle("Refresh gesture permission")
                                .setMessage("Android is using the old accessibility permission. Turn Snapplication support off and on in Accessibility settings to enable recorded taps and swipes.")
                                .setPositiveButton("Open accessibility") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                .setNegativeButton("Later", null).show()
                        } else {
                            getSharedPreferences("remoteMacros", MODE_PRIVATE).edit().putBoolean("touchRecording", touch.isChecked).apply()
                            dialog.dismiss()
                            home()
                            root.postDelayed({
                                if (!isFinishing && RemoteActionService.instance?.beginRecording(title, touch = touch.isChecked, coordinateReplay = coordinates) != true)
                                    message("Recording could not start. Check accessibility support.")
                            }, 450)
                        }
                    }
                }
                dialog.show()
            }
    }

    // Records cross-app routines and offers explicit playback, remote assignment and deletion.
    private fun macroSettings() {
        begin("Recorded actions")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.label("1. Create a macro   2. Test it   3. Assign a remote button", 18f))
        content.addView(ui.primaryButton("Record a macro") { recordMacro() })
        content.addView(ui.setting("Create from an image", "Choose a screenshot or video frame to identify a tap target") { ImageTapAction.choose(this) })
        content.addView(ui.button("Stop & save recording") { RemoteActionService.instance?.stopRecording(); macroSettings() })
        content.addView(ui.label(macroRunSummary(), 16f).apply { tag = "macroRunSummary" })
        content.addView(ui.section("Saved macros"))
        val saved = runCatching { org.json.JSONObject(getSharedPreferences("remoteMacros", MODE_PRIVATE).getString("macros", "{}") ?: "{}") }.getOrDefault(org.json.JSONObject())
        RemoteActionService.savedMacroNames(this).forEach { name ->
            val macro = saved.optJSONObject(name)
            val behaviour = if (macro?.optBoolean("coordinateReplay") == true) "Run on current screen" else "Match recorded screens"
            content.addView(ui.setting(name, "$behaviour · ${macro?.optJSONArray("steps")?.length() ?: 0} steps", "Manage") {
                AlertDialog.Builder(this).setTitle(name).setItems(arrayOf("Edit steps & timing", "Run macro", "Assign remote button", "Delete macro")) { _, index ->
                    when (index) {
                        0 -> MacroEditor(this) { macroSettings() }.show(name)
                        1 -> RemoteActionService.instance?.openForReplay(name) ?: explainAccessibility()
                        2 -> remote.learn("Macro: $name") { macroSettings() }
                        3 -> MacroEditor(this) { macroSettings() }.confirmDelete(name)
                    }
                }.setNegativeButton("Cancel", null).show()
            })
        }
        content.addView(ui.section("Playback options"))
        val macroPrefs = getSharedPreferences("remoteMacros", MODE_PRIVATE)
        content.addView(ui.setting("Show taps", "Display touch markers while a macro runs", if (macroPrefs.getBoolean("showPlaybackTaps", false)) "On" else "Off") {
            macroPrefs.edit().putBoolean("showPlaybackTaps", !macroPrefs.getBoolean("showPlaybackTaps", false)).apply(); macroSettings()
        })
        content.addView(ui.setting("Ask before running", "A mapped button asks for confirmation before replay", if (macroPrefs.getBoolean("confirmReplay", false)) "On" else "Off") {
            macroPrefs.edit().putBoolean("confirmReplay", !macroPrefs.getBoolean("confirmReplay", false)).apply(); macroSettings()
        })
        content.addView(ui.setting("Record from a remote button", "Press once to start, again to stop and save") { remote.learn("Record/stop macro") { macroSettings() } })
        macroPrefs.getString("lastTouchPause", null)?.let { reason ->
            content.addView(ui.setting("Last playback or recording issue", "Show details") { message(reason) })
        }
        content.addView(ui.button("Return Home") { home() })
    }

    private var drawerSwipeY = 0f
    private var drawerSwipeX = 0f
    private var drawerSwipeEligible = false

    // Opens from Home shortcuts or closes from the drawer handle using actual inset-aware view
    // bounds.
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (screen == "Home" || screen == "Apps") {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    drawerSwipeY = event.rawY; drawerSwipeX = event.rawX
                    drawerSwipeEligible = drawerGestureAreas.any { area ->
                        val bounds = android.graphics.Rect()
                        area.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL, android.view.MotionEvent.ACTION_POINTER_DOWN -> drawerSwipeEligible = false
                android.view.MotionEvent.ACTION_UP -> {
                    val distance = if (screen == "Home") drawerSwipeY - event.rawY else event.rawY - drawerSwipeY
                    val navigate = drawerSwipeEligible && distance > ui.dp(48) && kotlin.math.abs(event.rawX - drawerSwipeX) < ui.dp(80)
                    drawerSwipeEligible = false
                    if (navigate) {
                        val cancel = android.view.MotionEvent.obtain(event).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel); cancel.recycle()
                        if (screen == "Home") drawer() else home()
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // Applies saved remote keys only in this activity, preserving text entry and paired key events.
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        // Page Up/Down always turn the open book on Home, wherever keyboard focus happens to be.
        if (screen == "Home" && (event.keyCode == android.view.KeyEvent.KEYCODE_PAGE_DOWN || event.keyCode == android.view.KeyEvent.KEYCODE_PAGE_UP)
            && (reader != null || epub != null)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val delta = PageTurn.key(event.keyCode); reader?.move(delta); epub?.move(delta)
            }
            return true
        }
        if (::state.isInitialized && state.readerOnly) return super.dispatchKeyEvent(event)
        if (!::remote.isInitialized || RemoteControls.learning) return super.dispatchKeyEvent(event)
        RemoteControls.observeKey(event)
        // On the mapping page non-navigation keys are diagnostic: do not lock, leave or run a
        // macro.
        if (screen == "Remote buttons" && event.device?.isVirtual == false) {
            val assigned = remote.action(event)
            val navigation = if (assigned != null) assigned in setOf("Up", "Down", "Left", "Right", "Select")
                else event.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER)
            if (!navigation) return true
        }
        // A long Back press provides recovery when another navigation key has been reassigned.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN && event.isLongPress) {
            remote.reset(); remote.clearHeldButtons()
            message("Remote buttons reset. Standard controls restored.")
            return true
        }
        val action = remote.assignment(event) { currentFocus is EditText } ?: return super.dispatchKeyEvent(event)
        if (action == "Screen off") {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                RemoteActionService.instance?.performMappedAction(action) ?: explainAccessibility()
            return true
        }
        remote.navigationEvent(event, action)?.let { return super.dispatchKeyEvent(it) }
        if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) {
            when (action) {
                "Home" -> { RemoteActionService.instance?.recordLauncherNavigation("home"); home() }
                "Apps" -> drawer()
                "Screen off", "Record/stop macro", "Stop action" -> RemoteActionService.instance?.performMappedAction(action) ?: explainAccessibility()
                "Back" -> navigateBack()
                "Play/pause" -> {
                    val audio = getSystemService(android.media.AudioManager::class.java)
                    audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                }
                else -> if (action.startsWith("Macro: ")) {
                    RemoteActionService.instance?.openForReplay(action.removePrefix("Macro: ")) ?: explainAccessibility()
                }
            }
        }
        return true
    }

    // Prevents a release from an earlier window from triggering a launcher shortcut.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::remote.isInitialized) remote.clearHeldButtons()
        if (hasFocus && ::state.isInitialized && state.fullscreen) {
            // External player/PiP windows can reveal the bars after Home was already laid out.
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    // Opens a staged visual picker; cancelling preserves the current theme and colours.
    private fun customColours() { ColourPicker(this, state, ui).show { settings() } }

    // Shows concise recoverable errors without exposing private source URLs or credentials.
    private fun message(text: String) { if (!isFinishing) AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show() }

    // Returns from secondary screens to Home without trapping users in the launcher.
    @Deprecated("Legacy back callback for the native Activity host")
    override fun onBackPressed() { if (screen != "Home") navigateBack() else super.onBackPressed() }

    // Keeps touch, Android Back and mapped remote Back on the same screen hierarchy.
    private fun navigateBack() {
        RemoteActionService.instance?.recordLauncherNavigation("back")
        when (screen) {
            "Settings" -> if (settingsQuery.isNotEmpty()) { settingsQuery = ""; settings() } else if (settingsCategory != null) settings(null) else drawer()
            "Apps", "Library", "Viewer" -> home()
            "Remote buttons", "Recorded actions" -> settings("Remote & macros")
            "Home" -> Unit
            else -> settings()
        }
    }

    // Updates the existing notice without rebuilding Home or interrupting playback and reading.
    private fun updateThermalBanner() {
        thermalBanner?.apply {
            text = thermalText.orEmpty()
            visibility = if (thermalText == null) View.GONE else View.VISIBLE
        }
    }

    // Physical orientation changes apply only while this launcher is the active screen.
    override fun onPause() { phoneRotation.stop(); super.onPause() }

    // Notices recordings saved while this activity is already visible.
    private val macroSavedListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "lastRunStatus") root.post {
            if (!isFinishing) root.findViewWithTag<android.widget.TextView>("macroRunSummary")?.text = macroRunSummary()
        }
        if (key == "pendingAssignment") root.post { if (!isFinishing && hasWindowFocus()) offerSavedMacro() }
    }

    // Limits thermal polling and recording notifications to this visible activity.
    override fun onStart() {
        super.onStart(); thermal.start()
        getSharedPreferences("remoteMacros", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(macroSavedListener)
        getSystemService(android.content.pm.LauncherApps::class.java).registerCallback(appsCallback, android.os.Handler(mainLooper))
    }

    // Reloads the newest captured external frame after the source app returns to Home.
    override fun onResume() {
        super.onResume()
        phoneRotation.start()
        ExternalWatchPlayback.cancelResume()
        if (!::state.isInitialized) return
        if (state.mode == "Read") stopService(Intent(this, PreviewCaptureService::class.java))
        RemoteActionService.instance?.onLauncherResume(!state.automaticPhoneMode && (state.orientation == "Landscape" ||
            (state.orientation == "Mode default" && state.mode == "Watch" && state.theme != "E-paper")))
        if (screen == "Status & repair") statusSettings()
        else if (screen == "Welcome") introduction(guideStep)
        else if (screen == "Home" && state.viewerSource == "App") home()
        attachGuide()
        offerSavedMacro()
    }

    // Offers remote assignment once when a recording finishes or the user returns from another app.
    private fun offerSavedMacro() {
        if (state.readerOnly) return
        val macros = getSharedPreferences("remoteMacros", MODE_PRIVATE)
        val saved = macros.getString("pendingAssignment", null)
        if (saved != null) {
            macros.edit().remove("pendingAssignment").apply()
            AlertDialog.Builder(this).setTitle("Action saved: $saved")
                .setMessage("Assign this recording to a remote button, or review its steps and timing.")
                .setPositiveButton("Assign button") { _, _ -> remote.learn("Macro: $saved") { remoteSettings() } }
                .setNeutralButton("Edit steps") { _, _ -> MacroEditor(this) { macroSettings() }.show(saved) }
                .setNegativeButton("Later", null).show()
        }
    }

    // Saves and pauses playback whenever another app or the lock screen takes over.
    override fun onStop() {
        getSharedPreferences("remoteMacros", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(macroSavedListener)
        getSystemService(android.content.pm.LauncherApps::class.java).unregisterCallback(appsCallback)
        appsHandler.removeCallbacks(refreshApps)
        thermal.stop(); watch?.pause(); super.onStop()
    }

    // Releases media resources and pending folder work when the activity is destroyed.
    override fun onDestroy() {
        phoneRotation.stop()
        remote.closeLearning()
        thermal.stop()
        unregisterReceiver(screenReceiver)
        scanGeneration++; watch?.close(); reader?.close(); epub?.close(); worker.shutdownNow(); super.onDestroy()
    }
}
