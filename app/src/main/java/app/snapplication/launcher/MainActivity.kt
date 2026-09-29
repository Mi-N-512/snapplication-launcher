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
    private lateinit var remote: RemoteControls
    private lateinit var state: LauncherState
    private lateinit var ui: LauncherUi
    private lateinit var root: LinearLayout
    private var watch: WatchPlayer? = null
    private var reader: ReaderPanel? = null
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
        pickerMode = savedInstanceState?.getString("pickerMode") ?: state.mode
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        applyOrientation(); home()
    }

    /** Preserves picker intent across rotation so a Watch folder cannot become a Read folder accidentally. */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pickerMode", pickerMode); super.onSaveInstanceState(outState)
    }

    /** Returns HOME intents to the familiar home screen. */
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); home() }

    /** Keeps Watch landscape while allowing reading in the device's preferred orientation. */
    private fun applyOrientation() {
        requestedOrientation = if (state.mode == "Watch") ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Creates a page shell with persistent visible navigation and a readable title. */
    private fun begin(title: String): LinearLayout {
        scanGeneration++
        watch?.pause()
        reader?.close(); reader = null
        screen = title
        ui = LauncherUi(this, state)
        root = ui.column()
        root.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft + ui.dp(8), insets.systemWindowInsetTop + ui.dp(4),
                insets.systemWindowInsetRight + ui.dp(8), insets.systemWindowInsetBottom + ui.dp(4))
            insets
        }
        val nav = ui.column(true)
        ui.addWeighted(nav, ui.button("Home") { home() })
        ui.addWeighted(nav, ui.button("Apps") { drawer() })
        ui.addWeighted(nav, ui.button("Settings") { settings() })
        root.addView(nav)
        setContentView(root)
        return root
    }

    /** Builds the Make reference's central content area with side favourites in landscape. */
    private fun home() {
        begin("Home")
        val top = ui.column(true)
        ui.addWeighted(top, ui.button("${state.mode} ▾") { chooseMode() })
        ui.addWeighted(top, ui.button("Library") { library() })
        ui.addWeighted(top, ui.button("Folder") { pickFolder() })
        root.addView(top)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val body = ui.column(landscape)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = FrameLayout(this).apply { setBackgroundColor(ui.surface) }
        val favourites = state.favourites().filter { flat ->
            val component = ComponentName.unflattenFromString(flat)
            component != null && runCatching { packageManager.getActivityInfo(component, 0) }.isSuccess
        }.take(state.favouriteCount)
        if (landscape) {
            body.addView(favouriteColumn(favourites.take(state.favouriteCount / 2)), LinearLayout.LayoutParams(0, -1, 0.15f))
            body.addView(panel, LinearLayout.LayoutParams(0, -1, 0.70f))
            body.addView(favouriteColumn(favourites.drop(state.favouriteCount / 2)), LinearLayout.LayoutParams(0, -1, 0.15f))
        } else {
            body.addView(panel, LinearLayout.LayoutParams(-1, 0, 0.60f))
            body.addView(favouriteColumn(favourites, portrait = true), LinearLayout.LayoutParams(-1, 0, 0.40f))
        }
        val last = state.last(state.mode)
        if (last == null) {
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
        root.addView(ui.button("⌃ All apps") { drawer() })
    }

    /** Builds the reference's portrait grid or landscape strip with accessible icon labels. */
    private fun favouriteColumn(components: List<String>, portrait: Boolean = false): ScrollView {
        val column = ui.column()
        if (components.isEmpty()) column.addView(ui.label("Add favourites from Apps", 14f))
        val columns = if (state.grid && portrait) state.favouriteCount / 2 else 1
        components.chunked(columns).forEach { group ->
            val row = ui.column(true)
            group.forEach { flat ->
                val component = ComponentName.unflattenFromString(flat) ?: return@forEach
                val info = runCatching { packageManager.getActivityInfo(component, 0) }.getOrNull() ?: return@forEach
                val label = info.loadLabel(packageManager).toString()
                val button = ui.button(if (state.grid) "" else label) { launch(component) }
                val icon = info.loadIcon(packageManager).apply { setBounds(0, 0, ui.dp(28), ui.dp(28)) }
                button.setCompoundDrawables(null, icon, null, null)
                button.gravity = android.view.Gravity.CENTER
                button.contentDescription = label
                button.tooltipText = label
                button.setOnLongClickListener { favouriteActions(flat, label) { home() }; true }
                row.addView(button, LinearLayout.LayoutParams(0, ui.dp(if (state.grid) 64 else 80), 1f)
                    .apply { setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2)) })
            }
            repeat(columns - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            column.addView(row)
        }
        return ScrollView(this).apply { addView(column) }
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

    /** Lists actual launchable apps and filters names; long-press toggles favourites. */
    private fun drawer() {
        begin("Apps")
        val search = EditText(this).apply { hint = "Search apps"; setTextColor(ui.text); setHintTextColor(ui.text) }
        root.addView(search)
        val list = ui.column()
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val apps = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        // Rebuild only the filtered app rows; package discovery is shared across keystrokes.
        fun populate(query: String) {
            list.removeAllViews()
            apps.filter { it.loadLabel(packageManager).contains(query, true) }.forEach { info ->
                val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
                val button = ui.button(info.loadLabel(packageManager).toString()) { launch(component) }
                val icon = info.loadIcon(packageManager).apply { setBounds(0, 0, ui.dp(22), ui.dp(22)) }
                button.setCompoundDrawables(icon, null, null, null)
                button.setOnLongClickListener {
                    state.toggleFavourite(component.flattenToString()); message("Favourites updated"); true
                }
                list.addView(button, LinearLayout.LayoutParams(-1, ui.dp(56)))
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
        root.addView(ui.button("Launcher settings") { settings() })
    }

    /** Launches an installed application without claiming control of its saved session. */
    private fun launch(component: ComponentName) {
        runCatching { startActivity(Intent.makeMainActivity(component)) }.onFailure { message("This app is unavailable.") }
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
        state.remember(item); state.mode = item.mode; home()
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
        content.addView(ui.button("Theme · ${state.theme}") {
            val themes = arrayOf("Dark", "Light", "OLED Black", "Material You", "Custom", "E-paper")
            AlertDialog.Builder(this).setTitle("Theme").setItems(themes) { _, index ->
                state.theme = themes[index]
                if (state.theme == "Custom") customColours() else settings()
            }.show()
        })
        content.addView(ui.button("Favourites · ${if (state.grid) "Grid" else "List"}") { state.grid = !state.grid; settings() })
        content.addView(ui.button("Visible favourites · ${state.favouriteCount}") {
            AlertDialog.Builder(this).setTitle("Visible favourites").setItems(arrayOf("4", "6", "8")) { _, index ->
                state.favouriteCount = listOf(4, 6, 8)[index]; settings()
            }.show()
        })
        content.addView(ui.button("Remote buttons") { remoteSettings() })
        content.addView(ui.button("Arrange favourites") { manageFavourites() })
        content.addView(ui.button("Set as default launcher") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) })
        content.addView(ui.button("Mode · ${state.mode}") { chooseMode() })
        val threshold = ui.label("Next episode after ${state.completion}%", 18f)
        content.addView(threshold)
        content.addView(SeekBar(this).apply {
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
        content.addView(ui.label("Display and hardware", 22f))
        val display = windowManager.defaultDisplay
        val mode = display.mode
        content.addView(ui.label("${display.name}\n${mode.physicalWidth} × ${mode.physicalHeight} · ${mode.refreshRate.toInt()} Hz", 17f))
        content.addView(ui.label("CEC, charging bypass and e-paper refresh need device-specific support. No hardware capability has been assumed.", 16f))
        content.addView(ui.label("Snapplication · Private development\nAll features free. No launcher advertisements or paid feature gates.", 16f))
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
                "Back" -> if (screen != "Home") home()
            }
        }
        return true
    }

    /** Prevents a release from an earlier window from triggering a launcher shortcut. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::remote.isInitialized) remote.clearHeldButtons()
    }

    /** Stores user-selected custom colours after validating both values. */
    private fun customColours() {
        val fields = ui.column()
        val bg = EditText(this).apply { hint = "Background #181818"; setText("#%06X".format(state.background and 0xffffff)) }
        val accent = EditText(this).apply { hint = "Accent #D0BCFF"; setText("#%06X".format(state.accent and 0xffffff)) }
        fields.addView(bg); fields.addView(accent)
        AlertDialog.Builder(this).setTitle("Custom colours").setView(fields).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                runCatching { Color.parseColor(bg.text.toString()) to Color.parseColor(accent.text.toString()) }
                    .onSuccess { state.background = it.first; state.accent = it.second; settings() }
                    .onFailure { message("Use colours in #RRGGBB format.") }
            }.show()
    }

    /** Shows concise recoverable errors without exposing private source URLs or credentials. */
    private fun message(text: String) { if (!isFinishing) AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show() }

    /** Returns from secondary screens to Home without trapping users in the launcher. */
    @Deprecated("Legacy back callback for the native Activity host")
    override fun onBackPressed() { if (screen != "Home") home() else super.onBackPressed() }

    /** Saves and pauses playback whenever another app or the lock screen takes over. */
    override fun onStop() { watch?.pause(); super.onStop() }

    /** Releases media resources and pending folder work when the activity is destroyed. */
    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        scanGeneration++; watch?.close(); reader?.close(); worker.shutdownNow(); super.onDestroy()
    }
}
