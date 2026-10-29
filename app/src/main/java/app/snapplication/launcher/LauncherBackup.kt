package app.snapplication.launcher

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/*
Saves portable launcher choices, favourite pools, remote assignments and macros in a versioned local
JSON file. MainActivity owns the document picker and restore confirmation. Screen controls: none
here; Backup & restore explains the contents and asks before replacing choices. Android grants,
book/video files, preview frames, fonts and wallpaper files are excluded. Macro reference pictures
are included so image actions remain editable after restoration. Extend the explicit preference
schema when adding settings. Validate the entire document before modifying preferences;
invalid/newer backups must leave the current setup intact.

This object is a shared helper and does not inherit from another launcher class.

booleans, integers and strings list the settings that may be backed up. LIMIT bounds the file size,
and restoring prevents a new macro from starting while saved data is being replaced. snapshot
gathers settings and macro pictures, validate checks their structure, and restore prepares new
picture files before saving preferences. MainActivity owns the picker and confirmation;
LauncherState and RemoteControls read the restored values.
*/
object LauncherBackup {
    @Volatile var restoring = false
        private set
    private const val LIMIT = 32 * 1024 * 1024
    private val booleans = "previewWarnings watchPreviewExpected profileSetupComplete automaticPhoneMode showLabels showFavouriteHeading playVideosInLauncher readInLauncher readPagePreviews showReaderControls tapViewer useWallpaper dynamicBlocks asymmetricBlocks experimentalBlocks roundedCorners animations tutorialSeen landscapeApps fullscreen grid separateFavourites followMediaApps:Read followMediaApps:Watch".split(" ").toSet()
    private val integers = setOf("favouriteCount", "accent", "background", "completion")
    private val strings = setOf("deviceProfile", "mode", "theme", "phoneOrientation", "orientation", "orientation:Read", "orientation:Watch", "viewerSource:Read", "viewerSource:Watch", "contentScale:Read", "contentScale:Watch", "iconPack", "favourites", "favourites:Read", "favourites:Watch")
    private val macroFlags = setOf("showPlaybackTaps", "confirmReplay", "touchRecording")

    // Limits document and image reads before allocating or parsing untrusted backup contents.
    private fun read(context: Context, uri: Uri, limit: Int): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size() + count <= limit) { "Backup or reference image is too large." }
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }

    // Exports only documented settings; private preview/history and temporary run state stay local.
    fun snapshot(context: Context): JSONObject {
        val launcher = JSONObject()
        context.getSharedPreferences("launcher", 0).all.forEach { (key, value) ->
            if (key in booleans || key in integers || key in strings) launcher.put(key, value)
        }
        val macros = JSONObject()
        val assets = JSONObject()
        var imageBytes = 0
        if (!LauncherState(context).readerOnly) {
            val prefs = context.getSharedPreferences("remoteMacros", 0)
            macroFlags.forEach { if (prefs.contains(it)) macros.put(it, prefs.getBoolean(it, false)) }
            val actions = JSONObject(prefs.getString("macros", "{}")!!)
            actions.keys().forEach { name ->
                val action = actions.getJSONObject(name)
                val reference = action.optString("overlayUri")
                if (reference.isNotBlank()) {
                    val bytes = read(context, Uri.parse(reference), 8 * 1024 * 1024)
                    imageBytes += bytes.size
                    require(imageBytes <= 20 * 1024 * 1024) { "Macro pictures exceed the backup limit." }
                    assets.put(name, Base64.encodeToString(bytes, Base64.NO_WRAP))
                    action.remove("overlayUri")
                }
            }
            macros.put("macros", actions.toString())
        }
        return JSONObject().put("format", "snapplication-backup").put("version", 1)
            .put("sourcePackage", context.packageName).put("launcher", launcher).put("remoteMacros", macros)
            .put("remoteControls", JSONObject().apply {
                if (!LauncherState(context).readerOnly) put("mappings", context.getSharedPreferences("remoteControls", 0).getString("mappings", "{}"))
            }).put("images", assets)
    }

    // Writes through Android's chosen destination, never to a guessed external storage path.
    fun export(context: Context, uri: Uri) {
        val bytes = snapshot(context).toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= LIMIT) { "Backup exceeds 32 MB. Remove unused macro reference pictures." }
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
    }

    // Parses and validates before showing the replacement confirmation to the user.
    fun load(context: Context, uri: Uri): JSONObject = validate(JSONObject(String(read(context, uri, LIMIT), Charsets.UTF_8)))

    // Rejects type mismatches and malformed collections instead of allowing later preference
    // crashes.
    fun validate(root: JSONObject): JSONObject {
        require(root.getString("format") == "snapplication-backup" && root.getInt("version") == 1) { "Unsupported backup format or version." }
        val launcher = root.getJSONObject("launcher")
        launcher.keys().forEach { key ->
            val value = launcher.get(key)
            require(when (key) { in booleans -> value is Boolean; in integers -> value is Int; in strings -> value is String; else -> false }) { "Invalid setting: $key" }
            if (key.startsWith("favourites")) {
                val pool = JSONArray(value as String)
                require(pool.length() <= 500)
                for (i in 0 until pool.length()) require(pool.get(i) is String && pool.getString(i).length < 512)
            }
        }
        val choices = mapOf("deviceProfile" to setOf("Phone", "TV", "Reader"), "mode" to setOf("Read", "Watch"),
            "theme" to setOf("Light", "Dark", "OLED Black", "Material You", "E-paper", "Custom"))
        choices.forEach { (key, values) -> if (launcher.has(key)) require(launcher.getString(key) in values) { "Invalid $key" } }
        for (key in listOf("phoneOrientation", "orientation", "orientation:Read", "orientation:Watch"))
            if (launcher.has(key)) require(launcher.getString(key) in setOf("Auto", "Landscape", "Portrait", "Mode default"))
        for (mode in listOf("Read", "Watch")) {
            if (launcher.has("viewerSource:$mode")) require(launcher.getString("viewerSource:$mode") in setOf("App", "Folder"))
            if (launcher.has("contentScale:$mode")) require(launcher.getString("contentScale:$mode") in setOf("Fit", "Fill", "Stretch"))
        }
        if (launcher.has("completion")) require(launcher.getInt("completion") in 0..100)
        if (launcher.has("favouriteCount")) require(launcher.getInt("favouriteCount") in 4..24)
        val macros = root.getJSONObject("remoteMacros")
        macros.keys().forEach { require(if (it == "macros") macros.get(it) is String else it in macroFlags && macros.get(it) is Boolean) }
        val actions = JSONObject(macros.optString("macros", "{}"))
        require(actions.length() <= 200)
        actions.keys().forEach { name ->
            require(name.isNotBlank() && name.length <= 80)
            val steps = actions.getJSONObject(name).getJSONArray("steps")
            require(steps.length() in 1..20)
            for (i in 0 until steps.length()) steps.getJSONObject(i)
        }
        val remote = root.getJSONObject("remoteControls")
        require(remote.keys().asSequence().all { it == "mappings" && remote.get(it) is String })
        val mappings = JSONObject(remote.optString("mappings", "{}"))
        mappings.keys().forEach { descriptor ->
            val keys = mappings.getJSONObject(descriptor)
            keys.keys().forEach { key -> require(key.toIntOrNull() != null); keys.getJSONObject(key).getString("action") }
        }
        val images = root.getJSONObject("images")
        images.keys().forEach { name ->
            require(actions.has(name))
            val bytes = Base64.decode(images.getString(name), Base64.NO_WRAP)
            require(bytes.size <= 8 * 1024 * 1024)
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "Invalid macro picture." }
        }
        return root
    }

    // Replaces only backed-up choices. New private image paths are prepared before preferences
    // change.
    @Synchronized fun restore(context: Context, document: JSONObject) {
        val root = validate(JSONObject(document.toString()))
        val reader = LauncherState(context).readerOnly
        val names = if (reader) listOf("launcher") else listOf("launcher", "remoteMacros", "remoteControls")
        val old = names.associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        val created = mutableListOf<File>()
        var preferencesChanged = false
        restoring = true
        try {
            check(RemoteActionService.instance?.macroBusy != true) { "Stop macro playback or recording before restoring." }
            if (!reader) {
                val actions = JSONObject(root.getJSONObject("remoteMacros").optString("macros", "{}"))
                val source = root.optString("sourcePackage")
                val ownPackages = setOf("app.snapplication.launcher", "app.snapplication.launcher.modern", "app.snapplication.launcher.ereader", "app.snapplication.launcher.tv")
                actions.keys().forEach {
                    val action = actions.getJSONObject(it)
                    action.remove("overlayUri")
                    if (source in ownPackages && source != context.packageName) {
                        if (action.optString("package") == source) action.put("package", context.packageName)
                        val steps = action.getJSONArray("steps")
                        for (i in 0 until steps.length()) {
                            val step = steps.getJSONObject(i)
                            if (step.optString("package") == source) step.put("package", context.packageName)
                        }
                    }
                }
                val images = root.getJSONObject("images")
                images.keys().forEach { name ->
                    val file = File(context.filesDir, "macro-image-${java.util.UUID.randomUUID()}.png")
                    created.add(file); file.writeBytes(Base64.decode(images.getString(name), Base64.NO_WRAP))
                    actions.getJSONObject(name).put("overlayUri", Uri.fromFile(file).toString())
                }
                root.getJSONObject("remoteMacros").put("macros", actions.toString())
            }
            for (name in names) {
                val prefs = context.getSharedPreferences(name, 0)
                val edit = prefs.edit()
                val keys = if (name == "launcher") booleans + integers + strings else if (name == "remoteMacros") macroFlags + "macros" else setOf("mappings")
                keys.forEach { edit.remove(it) }
                if (name == "remoteMacros") edit.remove("pendingAssignment").remove("lastRunStatus").remove("runInProgress").remove("lastTouchPause").remove("lastReplayResult")
                val values = root.getJSONObject(name)
                values.keys().forEach { key -> put(edit, key, values.get(key)) }
                preferencesChanged = true
                check(edit.commit()) { "Could not save restored settings." }
            }
            // Edition constraints override only incompatible choices, not the restored reading
            // appearance.
            if (reader) {
                val imported = root.getJSONObject("launcher")
                context.getSharedPreferences("launcher", 0).edit().putString("deviceProfile", "Reader")
                    .putString("mode", "Read").putBoolean("automaticPhoneMode", false)
                    .putString("favourites", if (imported.optBoolean("separateFavourites")) imported.optString("favourites:Read", imported.optString("favourites", "[]")) else imported.optString("favourites", "[]"))
                    .putBoolean("separateFavourites", false).commit()
            } else if (BuildConfig.FLAVOR == "tv") {
                context.getSharedPreferences("launcher", 0).edit().putString("deviceProfile", "TV")
                    .putString("mode", "Watch").putBoolean("automaticPhoneMode", false).commit()
            }
        } catch (failure: Exception) {
            if (preferencesChanged) old.forEach { (name, values) ->
                val edit = context.getSharedPreferences(name, 0).edit().clear()
                values.forEach { (key, value) -> if (value != null) put(edit, key, value) }
                edit.commit()
            }
            created.forEach { it.delete() }
            throw failure
        } finally { restoring = false }
    }

    // Preserves SharedPreferences scalar types during restore and rollback.
    private fun put(edit: android.content.SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is String -> edit.putString(key, value)
        }
    }
}
