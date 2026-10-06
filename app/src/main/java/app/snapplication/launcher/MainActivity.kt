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

/** Hosts Home, App Drawer, Library and Settings; delegates media to WatchPlayer/ReaderPanel, state to LauncherState and input mapping to RemoteControls. */
class MainActivity : Activity() {
    private lateinit var thermal: ThermalMonitor
    private var thermalText: String? = null
    private var thermalBanner: android.widget.TextView? = null
    private lateinit var remote: RemoteControls
    private lateinit var state: LauncherState
    private lateinit var ui: LauncherUi
    private lateinit var root: LinearLayout
    private var watch: WatchPlayer? = null
    private var reader: ReaderPanel? = null
    private val drawerGestureAreas = mutableListOf<View>()
    private var screen = "Home"
    private var pickerMode = "Watch"
    private var choosingPreview = false
    private var guideStep = 0
    private val worker = Executors.newSingleThreadExecutor()
    private var scanGeneration = 0
    /** Receives system screen-off events to stage the next episode through WatchPlayer. */
    private val screenReceiver = object : BroadcastReceiver() {
        /** Applies completion behavior only when the device screen actually turns off. */
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) watch?.screenOff()
        }
    }

    /** Restores launcher preferences and builds the appropriate mode without requesting root privileges. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = LauncherState(this)
        state.initializeReaderProfile()
        remote = RemoteControls(this)
        thermal = ThermalMonitor(this) { text -> thermalText = text; updateThermalBanner() }
        pickerMode = savedInstanceState?.getString("pickerMode") ?: state.mode
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        applyOrientation()
        when (savedInstanceState?.getString("screen")) {
            "Settings" -> settings()
            "Theme Mode" -> themeSettings()
            "Favourites Style" -> favouriteSettings()
            "Apps" -> drawer()
            "Welcome" -> introduction(savedInstanceState?.getInt("guideStep") ?: 0)
            else -> home()
        }
        if (!state.tutorialSeen && screen != "Welcome") introduction()
    }

    /** Preserves picker intent across rotation so a Watch folder cannot become a Read folder accidentally. */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("guideStep", guideStep); outState.putString("pickerMode", pickerMode); outState.putString("screen", screen); super.onSaveInstanceState(outState)
    }

    /** Returns HOME intents to the familiar home screen. */
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (intent.getBooleanExtra("openApps", false)) drawer() else home() }

    /** Keeps Watch landscape while allowing reading in the device's preferred orientation. */
    private fun applyOrientation() {
        requestedOrientation = when (state.orientation) {
            "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            "Auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> if (state.mode == "Watch") ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** Creates a page shell with persistent visible navigation and a readable title. */
    private fun begin(title: String): LinearLayout {
        scanGeneration++
        watch?.pause()
        reader?.close(); reader = null
        screen = title
        drawerGestureAreas.clear(); drawerSwipeEligible = false
        ui = LauncherUi(this, state, state.useWallpaper && title != "Welcome")
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
        val showWallpaper = state.useWallpaper && title != "Welcome"
        if (showWallpaper) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (showWallpaper) Color.TRANSPARENT else ui.background))
        root = ui.column()
        root.setBackgroundColor(if (showWallpaper) (ui.background and 0xffffff) or 0x30000000 else ui.background)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (immersiveHome) view.setPadding(0, 0, 0, 0)
            else view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        if (title != "Home" && title != "Apps") {
            val header = ui.column(true).apply { setBackgroundColor(ui.surface) }
            header.addView(ui.button("←") { navigateBack() }.apply {
                contentDescription = "Back"
            }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
            header.addView(ui.label(if (title == "Settings") "Launcher Settings" else title, 19f),
                LinearLayout.LayoutParams(0, ui.dp(56), 1f))
            root.addView(header)
        }
        thermalBanner = ui.label("", 16f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setBackgroundColor(ui.surface)
        }
        root.addView(thermalBanner)
        updateThermalBanner()
        setContentView(if (showWallpaper) LauncherWallpaper.wrap(this, root) else root)
        if (title != "Welcome" && state.animations && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            root.alpha = 0f; root.animate().alpha(1f).setDuration(140).start()
        }
        return root
    }

    /** Uses the Make layout: a 57 percent portrait viewer and a 70 percent landscape viewer. */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    private fun home(measuredLandscape: Boolean? = null) {
        begin("Home")
        val landscape = measuredLandscape ?: (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
        val homeRoot = root
        homeRoot.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            if (right > left && bottom > top && ((right - left > bottom - top) != landscape)) {
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
            body.addView(viewer, LinearLayout.LayoutParams(-1, 0, .57f))
            body.addView(favouriteColumn(favourites, portrait = true, slotCount = if (state.dynamicBlocks) favourites.size.coerceAtLeast(2) else state.favouriteCount), LinearLayout.LayoutParams(-1, 0, .43f))
        }
        val last = state.last(state.mode)
        if (state.viewerSource == "App") {
            panel.addView(ExternalPreview(this, state).build(onResumeCapture = { previewSettings() }) {
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
            if (!state.playVideosInLauncher) {
                player.view.hideController()
                addOpenContentOverlay(panel, last, "▶")
            } else player.view.showController()
        } else if (last.title.endsWith(".epub", true) || last.mime == "application/epub+zip") {
            panel.addView(ui.label(last.title, 24f))
            addOpenContentOverlay(panel, last, "Open book in reader")
        } else {
            reader = ReaderPanel(this, state, ui).also { panel.addView(it.view); it.load(last) }
            if (!state.readInLauncher) addOpenContentOverlay(panel, last, "Open book")
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

    /** Makes the preview an accessible launch target while keeping its image visible underneath. */
    private fun addOpenContentOverlay(panel: FrameLayout, item: LibraryItem, label: String) {
        panel.addView(ui.button(label) { openContentInApp(item) }.apply {
            contentDescription = "Open ${item.title} in another app"
            textSize = if (item.mode == "Watch") 36f else 18f
            ui.flatFocus(this, Color.TRANSPARENT, outline = false)
        }, FrameLayout.LayoutParams(-1, -1))
    }

    /** Hands the exact selected document/link to Android's viewer with a temporary read grant. */
    private fun openContentInApp(item: LibraryItem) {
        watch?.pause()
        val uri = Uri.parse(item.uri)
        val extension = item.title.substringAfterLast('.', "").lowercase()
        val mime = if (extension == "cbz") "application/vnd.comicbook+zip"
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

    /** Lets the viewer switch between an app resume entry and user-selected folder media. */
    private fun chooseViewerSource() {
        AlertDialog.Builder(this).setTitle("Main viewer").setItems(arrayOf("Continue app", "Folder content")) { _, index ->
            state.viewerSource = if (index == 0) "App" else "Folder"; settings()
        }.setNegativeButton("Cancel", null).show()
    }

    /** Persists preview and local-media sizing without changing another app's display. */
    private fun chooseContentScale() {
        val values = arrayOf("Fit", "Fill", "Stretch")
        val labels = arrayOf("Fit · show the whole image", "Crop · fill without distortion", "Stretch · fill all dimensions")
        AlertDialog.Builder(this).setTitle("Preview sizing")
            .setItems(labels) { _, index ->
                state.contentScale = values[index]
                reader?.applyContentScale(); watch?.applyContentScale()
                settings()
            }.setNegativeButton("Cancel", null).show()
    }

    /** Preserves the configured Make block count; unassigned cells open the app picker. */
    private fun favouriteColumn(components: List<String>, portrait: Boolean = false, slotCount: Int): LinearLayout {
        val shell = ui.column().apply { background = ui.homeSurface() }
        if (state.showFavouriteHeading) shell.addView(ui.section("Favourites"))
        val column = ui.column().apply {
            gravity = android.view.Gravity.CENTER
            dividerDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(ui.border); setSize(ui.dp(1), ui.dp(1)) }
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
        shell.addView(column, LinearLayout.LayoutParams(-1, 0, 1f))
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (portrait && (state.grid || state.dynamicBlocks)) minOf(4, (slotCount + 1) / 2).coerceAtLeast(1) else if (!portrait && slotCount > 6) 2 else 1
        val displayedSlots = if (portrait && state.dynamicBlocks && !state.asymmetricBlocks) ((slotCount + columns - 1) / columns) * columns else slotCount
        val slots = List(displayedSlots) { components.getOrNull(it) }
        slots.chunked(columns).forEach { group ->
            val row = ui.column(true).apply {
                gravity = android.view.Gravity.CENTER
                dividerDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(ui.border); setSize(ui.dp(1), ui.dp(1)) }
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            }
            group.forEach { flat ->
                if (flat == null) {
                    val add = ui.button("+") { drawer() }.apply { contentDescription = "Add app to Home" }
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
                    if (portrait) button.setCompoundDrawables(icon, null, null, null)
                    else button.setCompoundDrawables(null, icon, null, null)
                }
                button.compoundDrawablePadding = ui.dp(12)
                button.textSize = if (landscape) 12f else 18f
                button.setSingleLine(true)
                button.ellipsize = android.text.TextUtils.TruncateAt.END
                button.gravity = android.view.Gravity.CENTER
                button.contentDescription = label; button.tooltipText = label
                ui.flatFocus(button, if (state.grid || !state.showLabels) Color.TRANSPARENT else ui.textSurface(), outline = false)
                button.stateListAnimator = null
                button.setOnLongClickListener { favouriteActions(flat, label) { home() }; true }
                val tile = if (state.grid || !state.showLabels) FrameLayout(this).apply {
                    addView(button, FrameLayout.LayoutParams(-1, -1))
                    // A separate non-interactive image keeps adaptive icons centred through orientation changes.
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

    /** Offers explicit reorder/remove actions without requiring drag gestures or accidental removal. */
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

    /** Opens the same live favourite editor used by the app drawer. */
    private fun manageFavourites() { drawer(editFavourites = true) }

    /** Hosts the alphabetical drawer; DrawerPanel owns search, remote focus and favourite editing. */
    private fun drawer(editFavourites: Boolean = false) {
        begin("Apps")
        val panel = DrawerPanel(this, state, ui, { launch(it) }, { settings() }, { home() })
        root.addView(panel.view, LinearLayout.LayoutParams(-1, 0, 1f))
        if (editFavourites) panel.beginFavouriteEditing()
        drawerGestureAreas.add((panel.view.getChildAt(0) as ViewGroup).getChildAt(0))
    }

    /** Launches an installed application without claiming control of its saved session. */
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

    /** Scans the selected folder off the UI thread, ignoring results from screens already left. */
    private fun library() {
        begin("Library")
        root.addView(ui.button("Choose ${state.mode.lowercase()} folder") { pickFolder() })
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
                    items.forEach { item -> list.addView(ui.button(item.title) { openItem(item) }) }
                }.onFailure { list.addView(ui.label("Folder access unavailable. Choose the folder again.")) }
            }
        }
    }

    /** Opens a selected item inside the home panel and starts explicitly requested video playback. */
    private fun openItem(item: LibraryItem) {
        state.remember(item); state.mode = item.mode; state.viewerSource = "Folder"; home()
        if (item.mode == "Watch" && state.playVideosInLauncher) watch?.load(item, true)
    }

    /** Requests read-only access to a user-chosen folder through Android's system picker. */
    private fun pickFolder() {
        pickerMode = state.mode
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 100)
    }

    /** Persists only the grant actually returned by the system and handles cancellation safely. */
    @Deprecated("Legacy Activity result API retained for the minimal native Activity host")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
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

    /** Accepts HTTPS media URLs; web page links need a compatible provider instead. */
    private fun directLink() {
        val input = EditText(this).apply { hint = "https://…/video.mp4"; inputType = 17 }
        AlertDialog.Builder(this).setTitle("Open direct video link").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Open") { _, _ ->
                val uri = Uri.parse(input.text.toString().trim())
                if (uri.scheme != "https" || uri.host.isNullOrBlank()) message("Enter a valid HTTPS media link.")
                else openItem(LibraryItem(uri.toString(), uri.lastPathSegment ?: "Video", "video/*", "Watch"))
            }.show()
    }

    /** Switches between the two user-selected modes while keeping independent resume positions. */
    private fun chooseMode() {
        AlertDialog.Builder(this).setTitle("Mode").setItems(arrayOf("Watch", "Read")) { _, index ->
            state.mode = if (index == 0) "Watch" else "Read"; applyOrientation(); home()
        }.show()
    }

    /** Exposes genuine settings; unsupported hardware actions are not presented as working switches. */
    private var settingsCategory: String? = null

    private fun settings(category: String? = settingsCategory) {
        settingsCategory = category
        begin("Settings")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.section("Appearance"))
        content.addView(ui.setting("Device setup", state.deviceProfile) {
            state.profileSetupComplete = false; introduction()
        })
        content.addView(ui.setting("Theme Mode", state.theme) { themeSettings() })
        content.addView(ui.setting("App icon", AppIconTheme.currentLabel(this)) {
            AppIconTheme.showChooser(this) { settings() }
        })
        content.addView(ui.setting("Show App Labels", "Display names beside favourites", if (state.showLabels) "On" else "Off") {
            state.showLabels = !state.showLabels; settings()
        })
        content.addView(ui.setting("Transparency", "Wallpaper through Home, app drawer and Settings", if (state.useWallpaper) "On" else "Off") {
            state.useWallpaper = !state.useWallpaper; settings()
        })
        content.addView(ui.row("Choose launcher wallpaper    ›") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 202)
        })
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
        content.addView(ui.setting("Favourites heading", "Show the heading above home shortcuts", if (state.showFavouriteHeading) "On" else "Off") {
            state.showFavouriteHeading = !state.showFavouriteHeading; settings()
        })
        content.addView(ui.setting("Favourites Style", "${if (state.grid) "Grid" else "List"} · ${state.favouriteCount} blocks") { favouriteSettings() })
        content.addView(ui.row("Arrange favourites    ›") { manageFavourites() })
        content.addView(ui.section(if (state.deviceProfile == "Reader") "Reading" else "Read & Watch"))
        content.addView(ui.setting("Home preview", "Follow your last video or book; save a still picture locally") { previewSettings() })
        if (state.deviceProfile != "Reader") content.addView(ui.setting("Play videos in launcher", "Off: tap the preview to open a video app", if (state.playVideosInLauncher) "On" else "Off") {
            state.playVideosInLauncher = !state.playVideosInLauncher; settings()
        })
        content.addView(ui.setting("Read in launcher", "Off: tap the preview to open a reading app", if (state.readInLauncher) "On" else "Off") {
            state.readInLauncher = !state.readInLauncher; settings()
        })
        content.addView(ui.setting("Main viewer", if (state.viewerSource == "App") "Continue app" else "Folder content") { chooseViewerSource() })
        content.addView(ui.setting("Content sizing", "${if (state.contentScale == "Fill") "Crop to fill" else state.contentScale} · preview, documents and videos") { chooseContentScale() })
        content.addView(ui.setting("Previous / Next buttons", "Show page controls in the reading panel", if (state.showReaderControls) "On" else "Off") {
            state.showReaderControls = !state.showReaderControls; settings()
        })
        content.addView(ui.row("Mode    ${state.mode}  ›") { chooseMode() })
        content.addView(ui.row("Library    ›") { library() })
        content.addView(ui.row("Choose folder    ›") { pickFolder() })
        if (state.deviceProfile != "Reader") content.addView(ui.row("Other app playback    ›") { externalPlayback() })
        content.addView(ui.section("Display & Input"))
        content.addView(ui.setting("Fullscreen", "Hide system bars; swipe an edge to reveal them", if (state.fullscreen) "On" else "Off") {
            state.fullscreen = !state.fullscreen; settings()
        })
        content.addView(ui.setting("Orientation", state.orientation) {
            val choices = arrayOf("Mode default", "Auto", "Landscape", "Portrait")
            AlertDialog.Builder(this).setTitle("Orientation").setItems(choices) { _, index ->
                state.orientation = choices[index]; applyOrientation(); settings()
            }.setNegativeButton("Cancel", null).show()
        })
        if (state.deviceProfile != "Reader") content.addView(ui.setting("Open apps in landscape", "Match landscape Home; request player fullscreen when available. Requires accessibility support.", if (state.landscapeApps) "On" else "Off") {
            state.landscapeApps = !state.landscapeApps
            if (!state.landscapeApps) RemoteActionService.instance?.cancelLandscape()
            settings()
        })
        content.addView(ui.setting("Wireless Remote", "Remap a connected remote control", "Calibrate ›") { remoteSettings() })
        if (state.deviceProfile != "Reader") content.addView(ui.setting("Second Screen", "Connected displays and device information") { diagnostics() })
        content.addView(ui.row("Set as default launcher    ›") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) })
        if (state.deviceProfile != "Reader") {
        content.addView(ui.section("Playback"))
        val threshold = ui.label("Next episode after ${state.completion}%", 18f)
        content.addView(threshold)
        content.addView(SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, ui.dp(48))
            max = 100; progress = state.completion
            contentDescription = "Episode completion threshold"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                /** Updates the threshold preview while dragging or using directional controls. */
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    state.completion = value; threshold.text = "Next episode after $value%"
                }
                /** The slider needs no special start behavior. */
                override fun onStartTrackingTouch(bar: SeekBar) {}
                /** Changes are already persisted as the slider moves. */
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        })
        content.addView(ui.label("For videos played here: when you leave or turn the screen off after this point, prepare the next file and show its name while paused. Other apps control their own episode loading; a ready next episode cannot be guaranteed unless that app exposes it.", 16f))
        }
        if (state.deviceProfile == "Reader") content.addView(ui.section("About"))
        content.addView(ui.button("Introduction and setup") { introduction() })
        content.addView(ui.button("Android display settings") { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) })
        if (state.deviceProfile != "Reader") content.addView(ui.label("Mirror / desktop mode is controlled by your phone's projection menu when a compatible display is connected. Screen off locks the phone; an independent desktop display may remain on.", 14f))
        content.addView(ui.button("Device diagnostics") { diagnostics() })
        content.addView(ui.label("Snapplication · Private development\nAll features free. No launcher advertisements or paid feature gates.", 16f))
        organizeSettings(content)

    }

    /** Presents one category at a time; search spans every category without hiding its matching controls. */
    private fun organizeSettings(content: LinearLayout) {
        val groups = linkedMapOf<String, MutableList<View>>()
        var group = "Appearance"
        val names = setOf("APPEARANCE", "FAVOURITES", "READ & WATCH", "READING", "DISPLAY & INPUT", "PLAYBACK", "ABOUT")
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child is android.widget.TextView && child.text.toString() in names) group = child.text.toString().lowercase().replaceFirstChar { it.uppercase() }
            groups.getOrPut(group) { mutableListOf() }.add(child)
        }
        val categories = ui.column()
        content.addView(categories, 0, LinearLayout.LayoutParams(-1, -2))
        val search = EditText(this).apply {
            hint = "Search all settings"; setTextColor(ui.text); setHintTextColor(ui.muted)
            setSingleLine(true); setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)); setBackgroundColor(ui.surface)
        }
        root.addView(search, 1, LinearLayout.LayoutParams(-1, ui.dp(52)))
        // Single-line EditText consumes DPAD_DOWN as cursor movement on some Huawei keyboards.
        // Give remote users an explicit route into the visible settings, keeping normal text editing.
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
        val summaries = mapOf("Appearance" to "Theme, transparency, corners and icon packs", "Favourites" to "Add apps, arrange blocks and choose your layout",
            "Read & watch" to "Last-frame preview, content and playback", "Reading" to "Page previews, folders and reading apps", "About" to "Setup guide and device information", "Display & input" to "Fullscreen, orientation and remote buttons",
            "Playback" to "Episode progress, setup guide and device information")
        fun words(view: View): String = if (view is android.widget.TextView) view.text.toString() else if (view is ViewGroup)
            (0 until view.childCount).joinToString(" ") { words(view.getChildAt(it)) } else ""
        fun update(query: String) {
            categories.removeAllViews()
            if (query.isBlank() && settingsCategory == null) {
                groups.keys.forEach { name -> categories.addView(ui.setting(name, summaries[name].orEmpty()) { settings(name) }) }
            } else if (query.isBlank()) {
                categories.addView(ui.row("← All settings") { settings(null) })
            }
            groups.forEach { (name, rows) -> rows.forEach { row ->
                row.visibility = if (if (query.isBlank()) name == settingsCategory else words(row).contains(query, true)) View.VISIBLE else View.GONE
            } }
        }
        search.addTextChangedListener(object : TextWatcher {
            /** Filters settings across categories as the user types. */
            override fun afterTextChanged(value: Editable?) { update(value.toString().trim()) }
            /** No work is needed before an edit. */
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            /** Filtering runs after the completed edit. */
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        update("")
    }

    /** Shows the reference's separate theme selection page with immediate persistent selection. */
    private fun themeSettings() {
        begin("Theme Mode")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
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

    /** Groups display style and four/six/eight choices as in the Make reference. */
    private fun favouriteSettings() {
        begin("Favourites Style")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.section("Display Style"))
        listOf(false, true).forEach { grid ->
            content.addView(ui.row("${if (state.grid == grid) "●" else "○"}   ${if (grid) "Grid · compact icon blocks" else "List · icon and app name"}") {
                state.grid = grid; favouriteSettings()
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

    /** Opens optional external media controls without loading providers or taking ownership of their playback. */
    private fun externalPlayback() {
        begin("Other app playback")
        val panel = ExternalPlaybackPanel(this, ui)
        root.addView(ScrollView(this).apply { addView(panel.view) }, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.refresh()
    }

    /** Shows a refreshable hardware snapshot without registering background monitors or requiring root. */
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

    /** Presents the visual remote and individual key assignments using RemoteMappingPanel. */
    private fun remoteSettings() {
        begin("Remote buttons")
        val panel = RemoteMappingPanel(this, ui, remote).build(
            onAccessibility = { explainAccessibility() },
            onRecordAction = { recordMacro() },
            onManageActions = { macroSettings() },
            onChanged = { remoteSettings() })
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** Presents one clear setup task per page, refreshing permission status when Android returns. */
    private fun introduction(step: Int = 0) {
        begin("Welcome")
        if (!state.profileSetupComplete) {
            root.addView(DeviceProfileSetup(this, state).build(
                { applyOrientation(); introduction(0) }, { applyOrientation(); introduction(0) }),
                LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }
        guideStep = step.coerceIn(0, 6)
        val access = RemoteActionService.instance != null
        val media = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
        val pages = listOf(
            SetupGuide.Page(if (state.mode == "Read") "Return to your last page" else "Pick up where you left off", if (state.mode == "Read") "Your last captured page lives in the centre. Select it to reopen your reading app. Choose PDF, EPUB or comics from a folder, or read in your preferred app." else "Your last video lives in the centre. Select its picture to return to the app; your favourite apps sit around it.",
                "Try Home", "You can revisit this guide from Settings at any time.") { state.tutorialSeen = true; home() },
            SetupGuide.Page("Put your apps within reach", "Open Favourites mode in All Apps. Tap apps to add them, then drag the preview icons into order. With a remote, select an icon to move it.",
                "Choose favourites", "Tap apps to select them; drag the preview to change their order.") { state.tutorialSeen = true; drawer(editFavourites = true) },
            SetupGuide.Page("Connect your remote and previews", "Accessibility support identifies the app on screen and runs the buttons and actions you choose. Enable Snapplication in Android, then return here.",
                if (access) "Review accessibility" else "Enable accessibility", if (access) "✓ Accessibility is connected" else "Needed for remote actions and app previews") { explainAccessibility() },
            SetupGuide.Page("Keep your last moment", "Android asks to share the screen. Snapplication saves only the latest eligible media frame locally. Protected video can appear black. Stop sharing from the capture notification whenever you want.",
                if (PreviewCaptureService.active) "Previews are on" else if (access) "Enable screen capture" else "Enable accessibility first",
                if (PreviewCaptureService.active) "✓ Screen capture is running" else "Android may ask again after a restart or an update") {
                if (!access) explainAccessibility() else if (!PreviewCaptureService.active) requestPreviewCapture()
            },
            SetupGuide.Page("Let the app handle playback", "Optional notification access lets Snapplication use an app's media controls. Play, previous and next work only when that app offers them. No notification messages are saved or sent.",
                if (media) "Review media access" else "Enable media controls", if (media) "✓ Media access is enabled" else "Optional — previews can open apps without this") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            SetupGuide.Page("One button for your routine", "Map Home, Back or Screen off. Map Record/stop macro to record actions across apps, then assign the saved routine to another button. Recording text and protected screens is excluded.",
                "Set up remote buttons", "Use a key Android delivers. The remote's physical Power key may be handled by the device itself.") { state.tutorialSeen = true; remoteSettings() },
            SetupGuide.Page("Make this your Home", "Choose Snapplication as your default Home app if you want Android's Home button to return here. Your current launcher stays installed, and you can change back in Android settings.",
                "Choose default Home", "Optional — all setup choices remain available in Settings") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        )
        val relevantPages = if (state.deviceProfile == "Reader") pages.filterIndexed { index, _ -> index !in listOf(4, 5) } else pages
        guideStep = guideStep.coerceIn(0, relevantPages.lastIndex)
        root.addView(SetupGuide(this, ui, state.mode == "Read").build(guideStep, relevantPages, { introduction(it) }, {
            state.tutorialSeen = true; home()
        }), LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /** Explains accessibility access before sending the user to Android's own opt-in control. */
    private fun explainAccessibility() {
        AlertDialog.Builder(this).setTitle("Remote and preview support")
            .setMessage("Optional accessibility access identifies the foreground app, maps remote keys and replays actions you record. Screen images require separate screen-sharing consent. Nothing is uploaded. You can disable access in Android settings at any time.")
            .setNegativeButton("Cancel", null).setPositiveButton("Open Android settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.show()
    }

    /** Offers one preview switch, automatic app following and optional manual selection. */
    private fun previewSettings() {
        begin("Home preview")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val enabled = RemoteActionService.instance != null
        val running = PreviewCaptureService.active
        content.addView(ui.label(if (running) "Previews are on" else "Previews are off", 22f))
        content.addView(ui.label("Keep a still picture of your last video or book. Tap it to return to that app. Pictures stay on this phone. Protected video may be black.", 16f))
        content.addView(ui.setting("Follow last media app", "Switch between supported players and readers as you use them", if (state.followMediaApps) "On" else "Off") {
            state.followMediaApps = !state.followMediaApps; previewSettings()
        })
        if (!state.followMediaApps) content.addView(ui.button("Choose fixed preview app") { choosingPreview = true; drawer() })
        content.addView(ui.button(if (running) "Turn previews off" else if (!enabled) "Set up previews" else "Turn previews on") {
            if (running) {
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

    /** Requests Android's screen-sharing consent; capture accepts only the active media target. */
    private fun requestPreviewCapture() {
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        val request = if (android.os.Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        startActivityForResult(request, 201)
    }

    /** Starts a named recording from either remote setup or the recorded-actions manager. */
    private fun recordMacro() {
            val service = RemoteActionService.instance
            if (service == null) explainAccessibility()
            else {
                val name = EditText(this).apply { hint = "Action name"; setSingleLine(true) }
                val touch = android.widget.CheckBox(this).apply { text = "Record taps and swipes"; isChecked = getSharedPreferences("remoteMacros", MODE_PRIVATE).getBoolean("touchRecording", true) }
                val form = ui.column().apply { addView(name); addView(touch) }
                AlertDialog.Builder(this).setTitle("Record an action").setView(form)
                    .setMessage("Start at Home and perform your steps. Each touch is repeated after you lift your finger. Stop saves the action; return here to assign a remote button. Turn off taps and swipes to record accessible buttons instead.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Record") { _, _ ->
                        val title = name.text.toString().trim()
                        if (title.isBlank() || title.length > 40 || title in RemoteActionService.savedMacroNames(this)) {
                            message("Use a unique action name of 1–40 characters.")
                        } else if (touch.isChecked && !service.canRecordTouches()) {
                            AlertDialog.Builder(this).setTitle("Refresh gesture permission")
                                .setMessage("Android is using the old accessibility permission. Turn Snapplication support off and on in Accessibility settings to enable recorded taps and swipes.")
                                .setPositiveButton("Open accessibility") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                                .setNegativeButton("Later", null).show()
                        } else {
                            getSharedPreferences("remoteMacros", MODE_PRIVATE).edit().putBoolean("touchRecording", touch.isChecked).apply()
                            home()
                            root.postDelayed({ if (!isFinishing && !service.beginRecording(title, touch = touch.isChecked)) message("Recording could not start. Check accessibility support.") }, 450)
                        }
                    }.show()
            }
    }

    /** Records cross-app routines and offers explicit playback, remote assignment and deletion. */
    private fun macroSettings() {
        begin("Recorded actions")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.label("Record up to 20 actions across apps, then assign the routine to a remote button. App changes count as steps. Use Record/stop from your remote at any time. Text entry and protected screens are excluded; replay stops when a target is missing or ambiguous.", 17f))
        val macroPrefs = getSharedPreferences("remoteMacros", MODE_PRIVATE)
        content.addView(ui.setting("Confirm before playback", "Off: a mapped button runs the saved action immediately", if (macroPrefs.getBoolean("confirmReplay", false)) "On" else "Off") {
            macroPrefs.edit().putBoolean("confirmReplay", !macroPrefs.getBoolean("confirmReplay", false)).apply(); macroSettings()
        })
        content.addView(ui.button("Record new action") { recordMacro() })
        content.addView(ui.button("Map a Record/stop button") { remote.learn("Record/stop macro") { macroSettings() } })
        content.addView(ui.button("Stop recording") { RemoteActionService.instance?.stopRecording(); macroSettings() })
        RemoteActionService.savedMacroNames(this).forEach { name ->
            content.addView(ui.section(name))
            content.addView(ui.button("Edit action: $name") { MacroEditor(this) { macroSettings() }.show(name) })
            content.addView(ui.button("Map button: $name") { remote.learn("Macro: $name") { macroSettings() } })
            content.addView(ui.button("Run: $name") { RemoteActionService.instance?.openForReplay(name) ?: explainAccessibility() })
            content.addView(ui.button("Delete: $name") { RemoteActionService.instance?.deleteMacro(name); macroSettings() })
        }
    }

    private var drawerSwipeY = 0f
    private var drawerSwipeX = 0f
    private var drawerSwipeEligible = false

    /** Opens from Home shortcuts or closes from the drawer handle using actual inset-aware view bounds. */
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

    /** Applies saved remote keys only in this activity, preserving text entry and paired key events. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!::remote.isInitialized || RemoteControls.learning) return super.dispatchKeyEvent(event)
        // A long Back press provides recovery when another navigation key has been reassigned.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN && event.isLongPress) {
            remote.reset(); remote.clearHeldButtons()
            message("Remote buttons reset. Standard controls restored.")
            return true
        }
        val action = remote.assignment(event, currentFocus is EditText) ?: return super.dispatchKeyEvent(event)
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

    /** Prevents a release from an earlier window from triggering a launcher shortcut. */
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

    /** Opens a staged visual picker; cancelling preserves the current theme and colours. */
    private fun customColours() { ColourPicker(this, state, ui).show { settings() } }

    /** Shows concise recoverable errors without exposing private source URLs or credentials. */
    private fun message(text: String) { if (!isFinishing) AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show() }

    /** Returns from secondary screens to Home without trapping users in the launcher. */
    @Deprecated("Legacy back callback for the native Activity host")
    override fun onBackPressed() { if (screen != "Home") navigateBack() else super.onBackPressed() }

    /** Keeps touch, Android Back and mapped remote Back on the same screen hierarchy. */
    private fun navigateBack() {
        RemoteActionService.instance?.recordLauncherNavigation("back")
        when (screen) {
            "Settings" -> if (settingsCategory != null) settings(null) else drawer()
            "Apps", "Library" -> home()
            "Home" -> Unit
            else -> settings()
        }
    }

    /** Updates the existing notice without rebuilding Home or interrupting playback and reading. */
    private fun updateThermalBanner() {
        thermalBanner?.apply {
            text = thermalText.orEmpty()
            visibility = if (thermalText == null) View.GONE else View.VISIBLE
        }
    }

    /** Notices recordings saved while this activity is already visible. */
    private val macroSavedListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "pendingAssignment") root.post { if (!isFinishing && hasWindowFocus()) offerSavedMacro() }
    }

    /** Limits thermal polling and recording notifications to this visible activity. */
    override fun onStart() {
        super.onStart(); thermal.start()
        getSharedPreferences("remoteMacros", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(macroSavedListener)
    }

    /** Reloads the newest captured external frame after the source app returns to Home. */
    override fun onResume() {
        super.onResume()
        RemoteActionService.instance?.cancelLandscape()
        if (!::state.isInitialized) return
        if (screen == "Welcome") introduction(guideStep)
        else if (screen == "Home" && state.viewerSource == "App") home()
        offerSavedMacro()
    }

    /** Offers remote assignment once when a recording finishes or the user returns from another app. */
    private fun offerSavedMacro() {
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

    /** Saves and pauses playback whenever another app or the lock screen takes over. */
    override fun onStop() {
        getSharedPreferences("remoteMacros", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(macroSavedListener)
        thermal.stop(); watch?.pause(); super.onStop()
    }

    /** Releases media resources and pending folder work when the activity is destroyed. */
    override fun onDestroy() {
        thermal.stop()
        unregisterReceiver(screenReceiver)
        scanGeneration++; watch?.close(); reader?.close(); worker.shutdownNow(); super.onDestroy()
    }
}
