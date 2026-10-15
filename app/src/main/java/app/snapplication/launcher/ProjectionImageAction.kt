package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/*
 * Configures one local, image-matched Wired Projection action from the user's own screenshot.
 * MainActivity opens the image picker; MacroEditor shows this compact preset editor instead of
 * unrelated recorded-step controls. RemoteActionService and ProjectionImageRunner execute it.
 * UI: Reference picture, match-only test, run, remote assignment and deletion. The fixed interior
 * and Agree position match Huawei's landscape dialog; other layouts require a separate adapter.
 * Reader never exposes this setup and never starts video capture for it. Images stay on the device.
 */
internal object ProjectionImageAction {
    const val REQUEST = 204
    const val KIND = "projectionImage"
    const val NAME = "Wired Projection - Agree"
    const val TAP_X = .635f
    const val TAP_Y = .818f

    // Explains the narrowly scoped screenshot format before opening Android's own file picker.
    fun choose(activity: Activity) {
        AlertDialog.Builder(activity).setTitle("Image-matched Wired Projection")
            .setMessage("Choose a full, uncropped landscape screenshot of Huawei's Wired Projection dialog. This action compares the dialog image, then taps Agree once. It needs Watch screen capture and a connected external display. Other dialogs, themes or layouts may not match.")
            .setNegativeButton("Cancel", null).setPositiveButton("Choose screenshot") { _, _ ->
                activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, REQUEST)
            }.show()
    }

    // Decodes a bounded reference off the UI thread and saves only this named preset, preserving other macros.
    fun accept(activity: Activity, result: Int, data: Intent?, saved: () -> Unit) {
        if (result != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val valid = runCatching {
                activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val bitmap = load(activity, uri)
                try { val grid = sample(bitmap); check(ProjectionImageMatch.matches(grid, grid)) } finally { bitmap.recycle() }
            }.isSuccess
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    if (valid) {
                        val prefs = activity.getSharedPreferences("remoteMacros", Context.MODE_PRIVATE)
                        val macros = runCatching { JSONObject(prefs.getString("macros", "{}")!!) }.getOrElse { JSONObject() }
                        macros.put(NAME, JSONObject().put("version", 2).put("type", KIND).put("package", activity.packageName)
                            .put("currentScreen", true).put("overlayUri", uri.toString())
                            .put("steps", JSONArray().put(JSONObject().put("kind", KIND).put("delay", 0))))
                        prefs.edit().putString("macros", macros.toString()).apply(); saved()
                    } else AlertDialog.Builder(activity).setMessage("Choose a clear, uncropped 2:1 landscape screenshot of the full Wired Projection dialog.")
                        .setPositiveButton("OK", null).show()
                }
            }
            worker.shutdown()
        }
    }

    // Reads a local URI with bounded decode size; the full-frame aspect is part of the match contract.
    fun load(context: Context, uri: Uri): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0 &&
            kotlin.math.abs(options.outWidth.toDouble() / options.outHeight - 2.0) < .02)
        options.inJustDecodeBounds = false; options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > 2160) options.inSampleSize *= 2
        return context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Image unavailable")
    }

    // Excludes wallpaper, status/navigation bars and the rounded corners from matching.
    fun sample(bitmap: Bitmap): IntArray {
        require(kotlin.math.abs(bitmap.width.toDouble() / bitmap.height - 2.0) < .02)
        // Match the projection's full-frame scaling before cropping; direct downsampling aliases small text differently.
        val normalized = Bitmap.createScaledBitmap(bitmap, 960, 480, true)
        val crop = Bitmap.createBitmap(normalized, 144, 96, 624, 316)
        val scaled = Bitmap.createScaledBitmap(crop, ProjectionImageMatch.WIDTH, ProjectionImageMatch.HEIGHT, true)
        return IntArray(ProjectionImageMatch.WIDTH * ProjectionImageMatch.HEIGHT).also {
            scaled.getPixels(it, 0, ProjectionImageMatch.WIDTH, 0, 0, ProjectionImageMatch.WIDTH, ProjectionImageMatch.HEIGHT)
            if (scaled !== crop) scaled.recycle()
            crop.recycle()
            if (normalized !== bitmap) normalized.recycle()
        }
    }

    // Keeps image actions editable and assignable without presenting unsupported timing/coordinate controls.
    fun show(activity: Activity, name: String, macro: JSONObject, delete: () -> Unit) {
        val ui = LauncherUi(activity, LauncherState(activity), false)
        val panel = ui.column()
        panel.addView(ui.label("Matches the Wired Projection dialog twice, then taps Agree once. Requires active Watch capture and an external display. No match means no tap.", 16f))
        panel.addView(ui.button("View reference screenshot") {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(macro.optString("overlayUri")), "image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        })
        panel.addView(ui.button("Test reference image · no tap") { rehearse(activity, name, macro) })
        panel.addView(ui.button("Run on current screen") { RemoteActionService.instance?.openForReplay(name) })
        panel.addView(ui.button("Assign remote button") { RemoteControls(activity).learn("Macro: $name") {} })
        panel.addView(ui.button("Delete action", delete))
        val prefs = activity.getSharedPreferences("remoteMacros", Context.MODE_PRIVATE)
        panel.addView(ui.label(prefs.getString("projectionImageResult", "Not tested yet")!!, 14f))
        AlertDialog.Builder(activity).setTitle(name).setView(android.widget.ScrollView(activity).apply { addView(panel) })
            .setPositiveButton("Close", null).show()
    }

    // Shows the reference full-screen for a capture/matcher rehearsal; this path can never dispatch a tap.
    private fun rehearse(activity: Activity, name: String, macro: JSONObject) {
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val bitmap = runCatching { load(activity, Uri.parse(macro.optString("overlayUri"))) }.getOrNull()
            activity.runOnUiThread {
                if (bitmap == null || activity.isFinishing || activity.isDestroyed) { bitmap?.recycle(); return@runOnUiThread }
                val dialog = android.app.Dialog(activity)
                val picture = ImageView(activity).apply {
                    setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Reference image rehearsal. No tap will be sent. Press Back to close."
                }
                dialog.setContentView(picture)
                dialog.setOnDismissListener {
                    RemoteActionService.instance?.performMappedAction("Stop action")
                    picture.setImageDrawable(null); bitmap.recycle()
                }
                dialog.show()
                dialog.window?.apply {
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                    setLayout(-1, -1); addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                    decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                        android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
                picture.postDelayed({ if (dialog.isShowing) RemoteActionService.instance?.testProjectionImage(name) }, 800)
            }
            worker.shutdown()
        }
    }
}
