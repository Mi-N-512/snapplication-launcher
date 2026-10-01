package app.snapplication.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Describes a playable or readable item shared by LocalLibrary, MainActivity and LauncherState. */
data class LibraryItem(val uri: String, val title: String, val mime: String, val mode: String)

/** Persists launcher preferences and per-item progress for MainActivity, WatchPlayer and ReaderPanel. */
class LauncherState(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    var mode: String
        get() = prefs.getString("mode", if (BuildConfig.FLAVOR == "ereader") "Read" else "Watch")!!
        set(value) { prefs.edit().putString("mode", value).apply() }
    var theme: String
        get() = prefs.getString("theme", if (BuildConfig.FLAVOR == "ereader") "E-paper" else "Dark")!!
        set(value) { prefs.edit().putString("theme", value).apply() }
    var orientation: String
        get() = prefs.getString("orientation", if (BuildConfig.FLAVOR == "tv") "Landscape" else "Mode default")!!
        set(value) { prefs.edit().putString("orientation", value).apply() }
    var showLabels: Boolean
        get() = prefs.getBoolean("showLabels", true)
        set(value) { prefs.edit().putBoolean("showLabels", value).apply() }
    var showReaderControls: Boolean
        get() = prefs.getBoolean("showReaderControls", false)
        set(value) { prefs.edit().putBoolean("showReaderControls", value).apply() }
    var viewerSource: String
        get() = prefs.getString("viewerSource:$mode", "App")!!
        set(value) { prefs.edit().putString("viewerSource:$mode", value).apply() }
    var contentScale: String
        get() = prefs.getString("contentScale:$mode", "Fit")!!
        set(value) { require(value in listOf("Fit", "Fill", "Stretch")); prefs.edit().putString("contentScale:$mode", value).apply() }

    /** Saves the external task entry per mode for MainActivity's Continue app panel. */
    fun rememberApp(component: String) { prefs.edit().putString("lastApp:$mode", component).apply() }

    /** Returns the last app launched through this launcher in the current mode. */
    fun lastApp(): String? = prefs.getString("lastApp:$mode", null)

    var useWallpaper: Boolean
        get() = prefs.getBoolean("useWallpaper", false)
        set(value) { prefs.edit().putBoolean("useWallpaper", value).apply() }
    var grid: Boolean
        get() = prefs.getBoolean("grid", false)
        set(value) { prefs.edit().putBoolean("grid", value).apply() }
    var favouriteCount: Int
        get() = prefs.getInt("favouriteCount", 4).takeIf { it in listOf(4, 6, 8) } ?: 4
        set(value) { require(value in listOf(4, 6, 8)); prefs.edit().putInt("favouriteCount", value).apply() }
    var accent: Int
        get() = prefs.getInt("accent", 0xffd0bcff.toInt())
        set(value) { prefs.edit().putInt("accent", value).apply() }
    var background: Int
        get() = prefs.getInt("background", 0xff181818.toInt())
        set(value) { prefs.edit().putInt("background", value).apply() }
    var completion: Int
        get() = prefs.getInt("completion", 95)
        set(value) { prefs.edit().putInt("completion", value.coerceIn(0, 100)).apply() }

    /** Saves durable progress for one URI; synchronous writes are used at lifecycle boundaries. */
    fun saveProgress(uri: String, position: Long) {
        prefs.edit().putLong("progress:$uri", position.coerceAtLeast(0)).commit()
    }

    /** Returns saved milliseconds for video or page index for a document. */
    fun progress(uri: String): Long = prefs.getLong("progress:$uri", 0)

    /** Records the last item independently for each mode without overwriting the other mode. */
    fun remember(item: LibraryItem) {
        val json = JSONObject().put("uri", item.uri).put("title", item.title)
            .put("mime", item.mime).put("mode", item.mode)
        prefs.edit().putString("last:${item.mode}", json.toString()).apply()
    }

    /** Restores the most recent selection; malformed stored data becomes an empty state. */
    fun last(mode: String): LibraryItem? = runCatching {
        val json = JSONObject(prefs.getString("last:$mode", null) ?: return null)
        LibraryItem(json.getString("uri"), json.getString("title"), json.getString("mime"), mode)
    }.getOrNull()

    /** Stores the ordered local video queue used by WatchPlayer's previous/next controls. */
    fun setWatchQueue(items: List<LibraryItem>) {
        val array = JSONArray()
        items.filter { it.mode == "Watch" }.forEach { item ->
            array.put(JSONObject().put("uri", item.uri).put("title", item.title).put("mime", item.mime))
        }
        prefs.edit().putString("watchQueue", array.toString()).apply()
    }

    /** Restores the local queue after process death; invalid entries cannot crash the launcher. */
    fun watchQueue(): List<LibraryItem> = runCatching {
        val array = JSONArray(prefs.getString("watchQueue", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            LibraryItem(item.getString("uri"), item.getString("title"), item.getString("mime"), "Watch")
        }
    }.getOrDefault(emptyList())

    /** Returns the folder permission URI saved for the current library mode. */
    fun folder(mode: String): String? = prefs.getString("folder:$mode", null)

    /** Remembers a user-approved folder for reopening after a process restart. */
    fun setFolder(mode: String, uri: String) { prefs.edit().putString("folder:$mode", uri).apply() }

    /** Reads ordered favourite component names for the home screen. */
    fun favourites(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("favourites", "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    /** Toggles a favourite; new shortcuts go first so they remain visible even when Home is full. */
    fun toggleFavourite(component: String) {
        val items = favourites().toMutableList()
        if (!items.remove(component)) items.add(0, component)
        prefs.edit().putString("favourites", JSONArray(items).toString()).apply()
    }

    /** Moves an existing shortcut one place without deleting favourites hidden by the display limit. */
    fun moveFavourite(component: String, offset: Int) {
        val items = favourites().toMutableList()
        val from = items.indexOf(component)
        val to = from + offset
        if (from < 0 || to !in items.indices) return
        items.add(to, items.removeAt(from))
        prefs.edit().putString("favourites", JSONArray(items).toString()).apply()
    }
}
