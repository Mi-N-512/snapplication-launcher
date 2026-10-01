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
            else -> home()
        }
    }

    /** Preserves picker intent across rotation so a Watch folder cannot become a Read folder accidentally. */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pickerMode", pickerMode); outState.putString("screen", screen); super.onSaveInstanceState(outState)
    }

    /** Returns HOME intents to the familiar home screen. */
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); home() }

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
        ui = LauncherUi(this, state, title == "Home" && state.useWallpaper)
        // Keep real Android status/navigation icons readable on light and e-paper surfaces.
        window.decorView.systemUiVisibility = if (ui.text == Color.BLACK)
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val showWallpaper = title == "Home" && state.useWallpaper
        if (showWallpaper) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (showWallpaper) Color.TRANSPARENT else ui.background))
        root = ui.column()
        root.setBackgroundColor(if (showWallpaper) (ui.background and 0xffffff) or 0x30000000 else ui.background)
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
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
        setContentView(root)
        return root
    }

    /** Uses the Make layout: a 57 percent portrait viewer and a 70 percent landscape viewer. */
    private fun home() {
        begin("Home")
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val body = ui.column(landscape)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val viewer = ui.column().apply { background = ui.homeSurface() }
        val panel = FrameLayout(this)
        viewer.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        val favourites = state.favourites().filter { flat ->
            val component = ComponentName.unflattenFromString(flat)
            component != null && runCatching { packageManager.getActivityInfo(component, 0) }.isSuccess
        }.take(if (!state.grid && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) 4 else state.favouriteCount)
        if (landscape) {
            val half = state.favouriteCount / 2
            body.addView(favouriteColumn(favourites.take(half), slotCount = half), LinearLayout.LayoutParams(0, -1, .15f))
            body.addView(viewer, LinearLayout.LayoutParams(0, -1, .70f))
            body.addView(favouriteColumn(favourites.drop(half), slotCount = half), LinearLayout.LayoutParams(0, -1, .15f))
        } else {
            body.addView(viewer, LinearLayout.LayoutParams(-1, 0, .57f))
            body.addView(favouriteColumn(favourites, portrait = true, slotCount = if (state.grid) state.favouriteCount else 4), LinearLayout.LayoutParams(-1, 0, .43f))
        }
        val last = state.last(state.mode)
        if (state.viewerSource == "App") {
            val content = ui.column().apply { gravity = android.view.Gravity.CENTER }
            val component = state.lastApp()?.let { ComponentName.unflattenFromString(it) }
            val info = component?.let { runCatching { packageManager.getActivityInfo(it, 0) }.getOrNull() }
            content.addView(ui.label("Snapplication", 16f))
            if (component != null && info != null) content.addView(ui.button("Continue ${info.loadLabel(packageManager)}") { launch(component) })
            content.addView(ui.button(if (info == null) "Choose app" else "Choose another app") { drawer() })
            if (state.mode == "Watch") content.addView(ui.button("Playback controls") { externalPlayback() })
            panel.addView(ScrollView(this).apply { addView(content) }, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
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
        } else {
            reader = ReaderPanel(this, state, ui).also { panel.addView(it.view); it.load(last) }
        }
        root.addView(ui.row("⌃   ALL APPS") { drawer() }.apply {
            ui.flatFocus(this, ui.textSurface(), outline = false)
            gravity = android.view.Gravity.CENTER; textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            drawerGestureAreas.add(this)
        }, LinearLayout.LayoutParams(-1, ui.dp(48)))
    }

    /** Lets the viewer switch between an app resume entry and user-selected folder media. */
    private fun chooseViewerSource() {
        AlertDialog.Builder(this).setTitle("Main viewer").setItems(arrayOf("Continue app", "Folder content")) { _, index ->
            state.viewerSource = if (index == 0) "App" else "Folder"; settings()
        }.setNegativeButton("Cancel", null).show()
    }

    /** Persists aspect handling for local reader/video content without changing another app's display. */
    private fun chooseContentScale() {
        val values = arrayOf("Fit", "Fill", "Stretch")
        AlertDialog.Builder(this).setTitle("Content sizing")
            .setItems(arrayOf("Fit to block · show everything", "Fill block · crop edges", "Stretch · use full block")) { _, index ->
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
        val columns = if (portrait && state.grid) state.favouriteCount / 2 else 1
        val slots = List(slotCount) { components.getOrNull(it) }
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
                val icon = info.loadIcon(packageManager).apply { setBounds(0, 0, ui.dp(24), ui.dp(24)) }
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
                        setImageDrawable(info.loadIcon(packageManager))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(ui.dp(32), ui.dp(32), android.view.Gravity.CENTER))
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

    /** Lists every saved favourite, including hidden and unavailable apps, for touch or remote editing. */
    private fun manageFavourites() {
        begin("Favourites")
        val column = ui.column()
        root.addView(ScrollView(this).apply { addView(column) }, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(ui.label("Choose an app to move or remove it. Home shows the first ${state.favouriteCount} available apps.", 17f))
        state.favourites().forEachIndexed { index, flat ->
            val component = ComponentName.unflattenFromString(flat)
            val label = component?.let { runCatching { packageManager.getActivityInfo(it, 0).loadLabel(packageManager).toString() }.getOrNull() }
                ?: "Unavailable app · ${component?.packageName ?: flat}"
            column.addView(ui.button("${index + 1}. $label") { favouriteActions(flat, label) { manageFavourites() } })
        }
        column.addView(ui.button("Add favourites from Apps") { drawer() })
    }

    /** Lists installed apps with explicit add/remove favourite controls for touch and remote users. */
    private fun drawer() {
        begin("Apps")
        val search = EditText(this).apply {
            hint = "Search apps…"; textSize = 16f; setSingleLine()
            setTextColor(ui.text); setHintTextColor(ui.muted)
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
            setBackgroundColor(ui.surface); minimumHeight = ui.dp(52)
        }
        root.addView(ui.row("⌄   APP DRAWER") { home() }.apply {
            ui.flatFocus(this, ui.textSurface(), outline = false)
            gravity = android.view.Gravity.CENTER; textSize = 11f
            drawerGestureAreas.add(this)
        })
        root.addView(search)
        root.addView(ui.label("Tap ☆ to add an app to Home. Tap ★ to remove it.", 12f))
        val list = ui.column()
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val phoneApps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val tvApps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
        val apps = (phoneApps + tvApps).distinctBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            .filter { it.activityInfo.packageName != packageName }.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        // Rebuild only the filtered app rows; package discovery is shared across keystrokes.
        fun populate(query: String) {
            list.removeAllViews()
            apps.filter { it.loadLabel(packageManager).contains(query, true) }.forEach { info ->
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                val button = ui.button(info.loadLabel(packageManager).toString()) { launch(component) }
                val icon = info.loadIcon(packageManager).apply { setBounds(0, 0, ui.dp(22), ui.dp(22)) }
                button.setCompoundDrawables(icon, null, null, null)
                button.compoundDrawablePadding = ui.dp(24)

                button.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
                ui.flatFocus(button)
                val row = ui.column(true).apply { gravity = android.view.Gravity.CENTER }
                row.addView(button, LinearLayout.LayoutParams(0, ui.dp(64), 1f))
                val favourite = ui.button("") {}
                // Update in place so remote focus and the current search survive a change.
                fun refreshFavourite() {
                    val added = component.flattenToString() in state.favourites()
                    favourite.text = if (added) "★" else "☆"
                    favourite.contentDescription = "${if (added) "Remove" else "Add"} ${info.loadLabel(packageManager)} ${if (added) "from" else "to"} favourites"
                }
                refreshFavourite()
                favourite.setOnClickListener {
                    state.toggleFavourite(component.flattenToString()); refreshFavourite()
                    android.widget.Toast.makeText(this, "Home favourites updated", android.widget.Toast.LENGTH_SHORT).show()
                }
                button.setOnLongClickListener { favourite.performClick(); true }
                row.addView(favourite, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
                list.addView(row)
                list.addView(View(this).apply { setBackgroundColor(ui.border) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
            }
        }
        populate("")
        search.addTextChangedListener(object : TextWatcher {
            /** Updates the visible app list as the query changes. */
            override fun afterTextChanged(s: Editable?) { populate(s.toString()) }
            /** No pre-edit work is required; filtering occurs after edits. */
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            /** Defers filtering until the text change has completed. */
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        root.addView(ui.row("⚙   Launcher Settings") { settings() }.apply {
            gravity = android.view.Gravity.CENTER
        })
    }

    /** Launches an installed application without claiming control of its saved session. */
    private fun launch(component: ComponentName) {
        runCatching {
            startActivity(Intent.makeMainActivity(component))
            state.rememberApp(component.flattenToString())
            state.viewerSource = "App"
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
        if (item.mode == "Watch") watch?.load(item, true)
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
    private fun settings() {
        begin("Settings")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.section("Appearance"))
        content.addView(ui.setting("Theme Mode", state.theme) { themeSettings() })
        content.addView(ui.setting("Show App Labels", "Display names beside favourites", if (state.showLabels) "On" else "Off") {
            state.showLabels = !state.showLabels; settings()
        })
        content.addView(ui.setting("Home wallpaper", "Use your Android wallpaper", if (state.useWallpaper) "On" else "Off") {
            state.useWallpaper = !state.useWallpaper; settings()
        })
        content.addView(ui.row("Choose Android wallpaper    ›") {
            runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper")) }
                .onFailure { message("No wallpaper picker is available on this device.") }
        })
        content.addView(ui.section("Favourites"))
        content.addView(ui.setting("Favourites heading", "Show the heading above home shortcuts", if (state.showFavouriteHeading) "On" else "Off") {
            state.showFavouriteHeading = !state.showFavouriteHeading; settings()
        })
        content.addView(ui.setting("Favourites Style", "${if (state.grid) "Grid" else "List"} · ${state.favouriteCount} blocks") { favouriteSettings() })
        content.addView(ui.row("Arrange favourites    ›") { manageFavourites() })
        content.addView(ui.section("Read & Watch"))
        content.addView(ui.setting("Main viewer", if (state.viewerSource == "App") "Continue app" else "Folder content") { chooseViewerSource() })
        content.addView(ui.setting("Content sizing", "${state.contentScale} · folder documents and videos") { chooseContentScale() })
        content.addView(ui.setting("Previous / Next buttons", "Show page controls in the reading panel", if (state.showReaderControls) "On" else "Off") {
            state.showReaderControls = !state.showReaderControls; settings()
        })
        content.addView(ui.row("Mode    ${state.mode}  ›") { chooseMode() })
        content.addView(ui.row("Library    ›") { library() })
        content.addView(ui.row("Choose folder    ›") { pickFolder() })
        content.addView(ui.row("Other app playback    ›") { externalPlayback() })
        content.addView(ui.section("Display & Input"))
        content.addView(ui.setting("Orientation", state.orientation) {
            val choices = arrayOf("Mode default", "Auto", "Landscape", "Portrait")
            AlertDialog.Builder(this).setTitle("Orientation").setItems(choices) { _, index ->
                state.orientation = choices[index]; applyOrientation(); settings()
            }.setNegativeButton("Cancel", null).show()
        })
        content.addView(ui.setting("Wireless Remote", "Remap a connected remote control", "Calibrate ›") { remoteSettings() })
        content.addView(ui.setting("Second Screen", "Connected displays and device information") { diagnostics() })
        content.addView(ui.row("Set as default launcher    ›") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) })
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
        content.addView(ui.label("On screen-off, stage the next local episode when this percentage is reached. Playback stays paused until you return.", 16f))
        content.addView(ui.button("Device diagnostics") { diagnostics() })
        content.addView(ui.label("CEC, charging bypass and e-paper refresh need device-specific support. No hardware capability has been assumed.", 16f))
        content.addView(ui.label("Snapplication · Private development\nAll features free. No launcher advertisements or paid feature gates.", 16f))
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
        content.addView(ui.section("Block Count"))
        val counts = ui.column(true)
        listOf(4, 6, 8).forEach { count ->
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
        content.addView(ui.label("These are Android-reported values at the last refresh. They do not verify CEC, charging bypass, independent display power or e-paper refresh support.", 16f))
    }

    /** Shows real learned keys and controls for mapping or resetting launcher-only remote actions. */
    private fun remoteSettings() {
        begin("Remote buttons")
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.label("Connect your remote through Android's Bluetooth settings or USB, then choose an action below.", 17f))
        content.addView(ui.label("Mappings apply to launcher screens. Text fields, dialogs and other apps keep normal controls. Back, power, volume and system Home cannot be reassigned. Hold Back on any launcher screen to reset all mappings if navigation becomes difficult. Assigning an action again replaces its previous button on that remote.", 16f))
        content.addView(ui.label(remote.summary(), 16f))
        remote.actions.forEach { action ->
            content.addView(ui.button("Map $action") { remote.learn(action) { remoteSettings() } })
        }
        content.addView(ui.button("Reset all remote buttons") {
            AlertDialog.Builder(this).setTitle("Reset remote buttons?").setMessage("Remove all custom assignments and restore normal controls.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reset") { _, _ -> remote.reset(); remoteSettings() }.show()
        })
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
        if (!::remote.isInitialized) return super.dispatchKeyEvent(event)
        // Physical Back is never remapped and offers recovery even when Select has been reassigned.
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_DOWN && event.isLongPress) {
            remote.reset(); remote.clearHeldButtons()
            message("Remote buttons reset. Standard controls restored.")
            return true
        }
        val action = remote.assignment(event, currentFocus is EditText) ?: return super.dispatchKeyEvent(event)
        remote.navigationEvent(event, action)?.let { return super.dispatchKeyEvent(it) }
        if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) {
            when (action) {
                "Home" -> home()
                "Apps" -> drawer()
                "Back" -> navigateBack()
            }
        }
        return true
    }

    /** Prevents a release from an earlier window from triggering a launcher shortcut. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::remote.isInitialized) remote.clearHeldButtons()
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
        when (screen) {
            "Settings" -> drawer()
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

    /** Limits thermal polling to the launcher's visible lifecycle. */
    override fun onStart() { super.onStart(); thermal.start() }

    /** Saves and pauses playback whenever another app or the lock screen takes over. */
    override fun onStop() { thermal.stop(); watch?.pause(); super.onStop() }

    /** Releases media resources and pending folder work when the activity is destroyed. */
    override fun onDestroy() {
        thermal.stop()
        unregisterReceiver(screenReceiver)
        scanGeneration++; watch?.close(); reader?.close(); worker.shutdownNow(); super.onDestroy()
    }
}
