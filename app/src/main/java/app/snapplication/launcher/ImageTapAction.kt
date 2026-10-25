package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/*
Imports a screenshot or a chosen video frame as a single image-matched tap macro. MainActivity owns
the picker result; MacroEditor supplies position, timing and remote assignment.
ProjectionImageRunner compares the saved picture with live capture before sending the chosen tap.
Screen controls: Source picker, frame-time entry and macro name. No app-control IDs are required.
Extend multi-frame workflows here rather than inferring actions automatically from a video.

This object is a shared helper and does not inherit from another launcher class.

REQUEST identifies the image picker result and KIND identifies this type of macro step. choose opens
the picker; accept loads the selected picture or video frame; name saves the new macro and opens its
editor. MainActivity calls these functions, MacroEditor sets the tap position, and
ProjectionImageRunner compares the picture when the macro runs.
*/
internal object ImageTapAction {
    const val REQUEST = 205
    const val KIND = "imageTap"

    // Android's picker grants access only to the image or recording chosen by the user.
    fun choose(activity: Activity) {
        activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, REQUEST)
    }

    // A recording supplies a frame at an explicit time, never guessed taps or automatic playback.
    fun accept(activity: Activity, result: Int, data: Intent?, saved: (String) -> Unit) {
        if (result != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        if (activity.contentResolver.getType(uri)?.startsWith("video/") == true) {
            val time = EditText(activity).apply {
                hint = "Frame time in seconds"; setText("0")
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            val dialog = AlertDialog.Builder(activity).setTitle("Choose a frame from the recording")
                .setView(time).setNegativeButton("Cancel", null).setPositiveButton("Load frame", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds = time.text.toString().toDoubleOrNull()
                if (seconds == null || !seconds.isFinite() || seconds !in 0.0..86400.0) time.error = "Enter 0–86400 seconds"
                else { dialog.dismiss(); load(activity, uri, (seconds * 1000000).toLong(), saved) }
            } }; dialog.show()
        } else load(activity, uri, null, saved)
    }

    // Decodes off-thread with limited dimensions, then asks for a unique saved-action name.
    private fun load(activity: Activity, uri: Uri, micros: Long?, saved: (String) -> Unit) {
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            val image = runCatching {
                if (micros != null) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(activity, uri)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                        require(micros / 1000 <= duration)
                        if (android.os.Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST, 1280, 1280)
                                ?: error("Frame unavailable")
                        } else {
                            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 0
                            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 0
                            require(width > 0 && height > 0 && width * height <= 8_000_000)
                            val frame = retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("Frame unavailable")
                            val scale = minOf(1.0, 1280.0 / maxOf(frame.width, frame.height))
                            val resized = Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1),
                                (frame.height * scale).toInt().coerceAtLeast(1), true)
                            if (resized !== frame) frame.recycle()
                            resized
                        }
                    } finally { retriever.release() }
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    activity.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                    require(options.outWidth > 0 && options.outHeight > 0)
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
                    options.inJustDecodeBounds = false
                    activity.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                        ?: error("Image unavailable")
                }
            }.getOrNull()
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) image?.recycle()
                else if (image == null) AlertDialog.Builder(activity).setMessage("Could not load that image or frame. Choose a full-screen image or a time inside the recording.").setPositiveButton("OK", null).show()
                else name(activity, image, saved)
            }
            worker.shutdown()
        }
    }

    // Keeps each reference private and creates no active tap until its position has been confirmed.
    private fun name(activity: Activity, image: Bitmap, saved: (String) -> Unit) {
        val input = EditText(activity).apply { hint = "Action name" }
        val dialog = AlertDialog.Builder(activity).setTitle("Name image tap")
            .setMessage("Next: place the tap on the picture, then assign a remote button. Use a full-screen image matching the live screen. Watch capture is required for matching.")
            .setView(input).setNegativeButton("Cancel", null).setPositiveButton("Create", null).create()
        dialog.setOnDismissListener { image.recycle() }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            val prefs = activity.getSharedPreferences("remoteMacros", 0)
            val macros = JSONObject(prefs.getString("macros", "{}")!!)
            if (name.isBlank() || macros.has(name)) { input.error = "Choose a new, non-empty name"; return@setOnClickListener }
            val file = File(activity.filesDir, "macro-image-${java.util.UUID.randomUUID()}.png")
            val written = runCatching { file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }.isSuccess
            if (!written) { input.error = "Could not save the reference"; return@setOnClickListener }
            val step = JSONObject().put("kind", KIND).put("width", image.width).put("height", image.height)
                .put("delay", 350).put("duration", 80).put("positionConfirmed", false)
                .put("points", JSONArray().put(JSONArray().put(.5).put(.5)))
            val macro = JSONObject().put("version", 2).put("package", activity.packageName).put("currentScreen", true)
                .put("overlayUri", Uri.fromFile(file).toString()).put("steps", JSONArray().put(step))
            prefs.edit().putString("macros", macros.put(name, macro).toString()).apply()
            dialog.dismiss(); saved(name)
        } }; dialog.show()
    }

    // A small normalized grid ignores system chrome; both inputs must have identical screen
    // proportions.
    fun sample(bitmap: Bitmap): IntArray {
        val crop = Bitmap.createBitmap(bitmap, bitmap.width / 20, bitmap.height * 15 / 100,
            bitmap.width * 9 / 10, bitmap.height * 8 / 10)
        val small = Bitmap.createScaledBitmap(crop, 96, 64, true)
        return IntArray(96 * 64).also {
            small.getPixels(it, 0, 96, 0, 0, 96, 64)
            if (small !== crop) small.recycle()
            if (crop !== bitmap) crop.recycle()
        }
    }
}
