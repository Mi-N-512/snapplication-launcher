package app.snapplication.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/*
Fixed description of a selected local or linked item: its URI, display title, MIME type and
Read/Watch mode. The object identifies content; it does not load or play it. Used by or used with:
LocalLibrary discovers items; MainActivity selects/routes them; LauncherState serializes recent
items and Watch queues; WatchPlayer and ReaderPanel consume supported items. UI controls: none;
title/mode inform the host's visible UI. Add metadata here only when its callers need it, and update
LocalLibrary construction plus LauncherState serialization/restoration together so older saved items
still load. Planned, not implemented: a future Gaming mode may require a distinct game descriptor
with launch identity and its own progress semantics rather than treating games as videos or books.
Coordinate that model with MainActivity and LauncherState before extending this.
*/
data class LibraryItem(val uri: String, val title: String, val mime: String, val mode: String)

/*
Saves launcher configuration, per-mode source selection, recent content, local watch queues, ordered
favourite components and per-URI progress in the launcher preferences. Used by or used with:
MainActivity and DrawerPanel edit settings/favourites; DeviceProfileSetup applies device defaults;
LauncherUi reads appearance; WatchPlayer and ReaderPanel save progress; ExternalPreview reads the
selected external app and preview sizing. UI controls: none are created here. This is the stored
state behind mode/theme choices, profile presets, favourite limits, preview options, orientation and
playback settings. Add a persistent setting beside the relevant property with an explicit default
and setter. Keep existing preference keys compatible; change applyDeviceProfile() for initial device
presets and initializeReaderProfile() only for the documented older-reader migration. Modify
remember()/last() for item serialization, saveProgress()/progress() for positions, and
favourites()/toggleFavourite()/moveFavourite() for ordered favourite persistence. Planned, not
implemented: Gaming mode would need its own recent game/source state and documented progress model,
coordinated with MainActivity's mode picker and game UI. Existing mode-scoped keys can separate
future state, but do not themselves implement gaming, save-state capture, or suspension. Watch
queues remain explicitly video-only.

This class is a helper and does not inherit from another launcher class.

prefs is the saved settings store. deviceProfile chooses Phone, TV or Reader defaults; mode selects
Read or Watch; automaticPhoneMode allows rotation to select that mode. separateFavourites selects a
shared favourite list or one list per mode. Most property getters read one saved value, and setters
write it back. MainActivity changes these choices; DrawerPanel, WatchPlayer, ReaderPanel and preview
helpers read them. These are users of the state class, not child classes.
*/
class LauncherState(private val context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    var previewWarnings: Boolean
        get() = prefs.getBoolean("previewWarnings", true)
        set(value) { prefs.edit().putBoolean("previewWarnings", value).apply() }

    var watchPreviewExpected: Boolean
        get() = prefs.getBoolean("watchPreviewExpected", true)
        set(value) { prefs.edit().putBoolean("watchPreviewExpected", value).apply() }
    // Enforces the reading edition even when older preferences contain a TV or Watch choice.
    val readerOnly: Boolean get() = BuildConfig.FLAVOR == "ereader"
    // Start recognised e-paper hardware in a legible reader layout even with the universal APK.
    private val readerDevice = BuildConfig.FLAVOR == "ereader" ||
        listOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND, android.os.Build.MODEL)
            .any { it.contains("meebook", true) || it.contains("boyue", true) }
    var deviceProfile: String
        get() = if (readerOnly) "Reader" else prefs.getString("deviceProfile", if (readerDevice) "Reader" else if (BuildConfig.FLAVOR == "tv") "TV" else "Phone")!!
        private set(value) { prefs.edit().putString("deviceProfile", value).apply() }
    var profileSetupComplete: Boolean
        get() = prefs.getBoolean("profileSetupComplete", false)
        set(value) { prefs.edit().putBoolean("profileSetupComplete", value).apply() }

    // Initializes device editions once; existing saved profiles and custom choices are preserved.
    fun initializeReaderProfile() {
        if (!prefs.contains("deviceProfile")) {
            if (readerDevice) applyDeviceProfile("Reader")
            else if (BuildConfig.FLAVOR == "tv") applyDeviceProfile("TV")
        }
    }

    // Applies the chosen device's initial defaults; later settings remain individually editable.
    fun applyDeviceProfile(profile: String) {
        require(profile in listOf("Phone", "TV", "Reader"))
        if (readerOnly && profile != "Reader") return
        deviceProfile = profile
        val reader = profile == "Reader"
        prefs.edit().putString("mode", if (reader) "Read" else "Watch")
            .putString("theme", if (reader) "E-paper" else "Dark")
            .putString("orientation:Read", if (profile == "Phone") "Auto" else "Portrait")
            .putString("orientation:Watch", if (profile == "Phone") "Auto" else "Landscape")
            .putBoolean("automaticPhoneMode", profile == "Phone")
            .putString("phoneOrientation", "Auto")
            .putBoolean("animations", !reader).putBoolean("useWallpaper", !reader)
            .putBoolean("landscapeApps", !reader).putBoolean("playVideosInLauncher", false)
            .putBoolean("roundedCorners", false).apply()
        if (profile == "TV") applyTvDefaults()
    }

    // Applies television layout defaults without personal content, bindings or permission grants.
    private fun applyTvDefaults() {
        prefs.edit().putString("theme", "Dark")
            .putBoolean("fullscreen", true).putBoolean("showLabels", true)
            .putBoolean("grid", true).putBoolean("dynamicBlocks", true).putBoolean("asymmetricBlocks", true)
            .putBoolean("experimentalBlocks", false).putInt("favouriteCount", 8)
            .putBoolean("showFavouriteHeading", false).putBoolean("readInLauncher", false)
            .putBoolean("showReaderControls", false).putBoolean("previewWarnings", true)
            .putString("viewerSource:Watch", "App").putBoolean("followMediaApps:Watch", true)
            .putString("contentScale:Watch", "Fill").putInt("completion", 90)
            .putString("iconPack", "").apply()
    }

    var mode: String
        get() = if (readerOnly) "Read" else prefs.getString("mode", if (readerDevice) "Read" else "Watch")!!
        set(value) { prefs.edit().putString("mode", if (readerOnly) "Read" else value).apply() }
    var theme: String
        get() = prefs.getString("theme", if (readerDevice) "E-paper" else "Dark")!!
        set(value) {
            val change = prefs.edit().putString("theme", value)
            // Choosing E-paper is also a safe rendering preset, not just a colour change.
            if (value == "E-paper") change.putBoolean("animations", false)
                .putBoolean("useWallpaper", false).putBoolean("landscapeApps", false)
            change.apply()
        }
    // Phone rotation selects the matching content mode; dedicated editions keep their own layout.
    var automaticPhoneMode: Boolean
        get() = !readerOnly && BuildConfig.FLAVOR != "tv" && deviceProfile == "Phone" &&
            prefs.getBoolean("automaticPhoneMode", true)
        set(value) { prefs.edit().putBoolean("automaticPhoneMode", value).apply() }

    var orientation: String
        get() = if (deviceProfile == "Phone" && BuildConfig.FLAVOR != "tv") prefs.getString("phoneOrientation", "Auto")!! else prefs.getString("orientation:$mode", null)
            ?: if (mode == "Read") {
                // Only the reading-only edition can safely inherit an old global reader choice.
                if (readerOnly) prefs.getString("orientation", "Portrait")!! else "Portrait"
            } else prefs.getString("orientation", "Landscape")!!
        set(value) { prefs.edit().putString(if (deviceProfile == "Phone" && BuildConfig.FLAVOR != "tv") "phoneOrientation" else "orientation:$mode", value).apply() }
    var showLabels: Boolean
        get() = prefs.getBoolean("showLabels", true)
        set(value) { prefs.edit().putBoolean("showLabels", value).apply() }
    var showFavouriteHeading: Boolean
        get() = prefs.getBoolean("showFavouriteHeading", false)
        set(value) { prefs.edit().putBoolean("showFavouriteHeading", value).apply() }
    var playVideosInLauncher: Boolean
        get() = !readerOnly && prefs.getBoolean("playVideosInLauncher", false)
        set(value) { prefs.edit().putBoolean("playVideosInLauncher", value).apply() }
    var readInLauncher: Boolean
        get() = prefs.getBoolean("readInLauncher", false)
        set(value) { prefs.edit().putBoolean("readInLauncher", value).apply() }
    var readPagePreviews: Boolean
        get() = prefs.getBoolean("readPagePreviews", false)
        set(value) { prefs.edit().putBoolean("readPagePreviews", value).apply() }
    var showReaderControls: Boolean
        get() = prefs.getBoolean("showReaderControls", false)
        set(value) { prefs.edit().putBoolean("showReaderControls", value).apply() }
    // Tap-only viewer hides page/player controls; taps on the picture turn pages or play/pause.
    var tapViewer: Boolean
        get() = prefs.getBoolean("tapViewer", false)
        set(value) { prefs.edit().putBoolean("tapViewer", value).apply() }
    var viewerSource: String
        get() = prefs.getString("viewerSource:$mode", "App")!!
        set(value) { prefs.edit().putString("viewerSource:$mode", value).apply() }
    var followMediaApps: Boolean
        get() = prefs.getBoolean("followMediaApps:$mode", true)
        set(value) { prefs.edit().putBoolean("followMediaApps:$mode", value).apply() }
    var contentScale: String
        get() = prefs.getString("contentScale:$mode", if (viewerSource == "App") "Fill" else "Fit")!!
        set(value) { require(value in listOf("Fit", "Fill", "Stretch")); prefs.edit().putString("contentScale:$mode", value).apply() }

    // Saves the external task entry per mode for MainActivity's Continue app panel.
    fun rememberApp(component: String) { prefs.edit().putString("lastApp:$mode", component).apply() }

    // Returns the last app launched through this launcher in the current mode.
    fun lastApp(): String? = prefs.getString("lastApp:$mode", null)

    var useWallpaper: Boolean
        get() = prefs.getBoolean("useWallpaper", !readerDevice && theme != "E-paper")
        set(value) { prefs.edit().putBoolean("useWallpaper", value).apply() }
    var dynamicBlocks: Boolean
        get() = prefs.getBoolean("dynamicBlocks", true)
        set(value) { prefs.edit().putBoolean("dynamicBlocks", value).apply() }
    var asymmetricBlocks: Boolean
        get() = prefs.getBoolean("asymmetricBlocks", false)
        set(value) { prefs.edit().putBoolean("asymmetricBlocks", value).apply() }
    var experimentalBlocks: Boolean
        get() = prefs.getBoolean("experimentalBlocks", false)
        set(value) { prefs.edit().putBoolean("experimentalBlocks", value).apply() }
    var roundedCorners: Boolean
        get() = prefs.getBoolean("roundedCorners", false)
        set(value) { prefs.edit().putBoolean("roundedCorners", value).apply() }
    var animations: Boolean
        get() = prefs.getBoolean("animations", !readerDevice && theme != "E-paper")
        set(value) { prefs.edit().putBoolean("animations", value).apply() }
    var tutorialSeen: Boolean
        get() = prefs.getBoolean("tutorialSeen", false)
        set(value) { prefs.edit().putBoolean("tutorialSeen", value).apply() }
    // Keeps apps launched from landscape Home wide, through the enabled accessibility bridge.
    var landscapeApps: Boolean
        get() = mode == "Watch" && !readerOnly && prefs.getBoolean("landscapeApps", !readerDevice && theme != "E-paper")
        set(value) { prefs.edit().putBoolean("landscapeApps", value).apply() }

    var fullscreen: Boolean
        get() = prefs.getBoolean("fullscreen", true)
        set(value) { prefs.edit().putBoolean("fullscreen", value).apply() }
    var grid: Boolean
        get() = prefs.getBoolean("grid", false)
        set(value) { prefs.edit().putBoolean("grid", value).apply() }
    var favouriteCount: Int
        get() = prefs.getInt("favouriteCount", 8).coerceIn(4, if (experimentalBlocks) 24 else 8)
        set(value) { require(value in 4..if (experimentalBlocks) 24 else 8); prefs.edit().putInt("favouriteCount", value).apply() }
    var accent: Int
        get() = prefs.getInt("accent", 0xffd0bcff.toInt())
        set(value) { prefs.edit().putInt("accent", value).apply() }
    var background: Int
        get() = prefs.getInt("background", 0xff181818.toInt())
        set(value) { prefs.edit().putInt("background", value).apply() }
    var completion: Int
        get() = prefs.getInt("completion", 95)
        set(value) { prefs.edit().putInt("completion", value.coerceIn(0, 100)).apply() }

    // Saves durable progress for one URI; synchronous writes are used at lifecycle boundaries.
    fun saveProgress(uri: String, position: Long) {
        prefs.edit().putLong("progress:$uri", position.coerceAtLeast(0)).commit()
    }

    // Returns saved milliseconds for video or page index for a document.
    fun progress(uri: String): Long = prefs.getLong("progress:$uri", 0)

    // Records the last item independently for each mode without overwriting the other mode.
    fun remember(item: LibraryItem) {
        val json = JSONObject().put("uri", item.uri).put("title", item.title)
            .put("mime", item.mime).put("mode", item.mode)
        prefs.edit().putString("last:${item.mode}", json.toString()).apply()
    }

    // Restores the most recent selection; malformed stored data becomes an empty state.
    fun last(mode: String): LibraryItem? = runCatching {
        val json = JSONObject(prefs.getString("last:$mode", null) ?: return null)
        LibraryItem(json.getString("uri"), json.getString("title"), json.getString("mime"), mode)
    }.getOrNull()

    // Stores the ordered local video queue used by WatchPlayer's previous/next controls.
    fun setWatchQueue(items: List<LibraryItem>) {
        val array = JSONArray()
        items.filter { it.mode == "Watch" }.forEach { item ->
            array.put(JSONObject().put("uri", item.uri).put("title", item.title).put("mime", item.mime))
        }
        prefs.edit().putString("watchQueue", array.toString()).apply()
    }

    // Restores the local queue after process death; invalid entries cannot crash the launcher.
    fun watchQueue(): List<LibraryItem> = runCatching {
        val array = JSONArray(prefs.getString("watchQueue", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            LibraryItem(item.getString("uri"), item.getString("title"), item.getString("mime"), "Watch")
        }
    }.getOrDefault(emptyList())

    // Returns the folder permission URI saved for the current library mode.
    fun folder(mode: String): String? = prefs.getString("folder:$mode", null)

    // Remembers a user-approved folder for reopening after a process restart.
    fun setFolder(mode: String, uri: String) { prefs.edit().putString("folder:$mode", uri).apply() }

    // Keeps the original shared pool and seeds mode lists once, without discarding either list.
    var separateFavourites: Boolean
        get() = !readerOnly && prefs.getBoolean("separateFavourites", false)
        set(value) {
            val edit = prefs.edit().putBoolean("separateFavourites", value)
            if (value) for (contentMode in listOf("Read", "Watch")) {
                if (!prefs.contains("favourites:$contentMode"))
                    edit.putString("favourites:$contentMode", prefs.getString("favourites", "[]"))
            }
            edit.apply()
        }

    private fun favouritesKey() = if (separateFavourites) "favourites:$mode" else "favourites"

    // Reads the active ordered pool; disabling separation restores the original shared list.
    fun favourites(): List<String> = runCatching {
        val array = JSONArray(prefs.getString(favouritesKey(), "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    // Toggles a favourite; new shortcuts go first so they remain visible even when Home is full.
    fun toggleFavourite(component: String) {
        val items = favourites().toMutableList()
        if (!items.remove(component)) items.add(0, component)
        prefs.edit().putString(favouritesKey(), JSONArray(items).toString()).apply()
    }

    // Moves an existing shortcut one place without deleting favourites hidden by the display limit.
    fun moveFavourite(component: String, offset: Int) {
        val items = favourites().toMutableList()
        val from = items.indexOf(component)
        val to = from + offset
        if (from < 0 || to !in items.indices) return
        items.add(to, items.removeAt(from))
        prefs.edit().putString(favouritesKey(), JSONArray(items).toString()).apply()
    }
}
