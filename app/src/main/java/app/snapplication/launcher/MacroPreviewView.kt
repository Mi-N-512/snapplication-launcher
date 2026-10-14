package app.snapplication.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.view.MotionEvent
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import java.util.Locale
import kotlin.math.hypot

/*
 * Fitted screenshot and touch rehearsal embedded in MacroEditor's full-screen workspace. It never calls
 * RemoteActionService, opens another app, or injects a touch.
 *
 * Related classes: MacroEditor opens this view using locally saved macro JSON.
 * UI: Fitted display, optional reference screenshot, touch markers and editable tap position.
 * Edit or extend: Edit rehearsal drawing and timing here; keep it visual only and in step with the real replay
 * rules.
 */
class MacroPreviewView private constructor(context: Context, private val sequence: List<PreviewStep>,
    private val status: (String) -> Unit) : View(context) {
    /*
     * One scheduled item in MacroPreviewView's visual rehearsal. Source contains saved action data, title
     * labels its timeline, and begins/ends define drawing time. No independent controls. Extend timing/display
     * metadata here without injecting real input.
     */
    private data class PreviewStep(val source: JSONObject, val title: String, val begins: Long, val ends: Long)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val duration = sequence.lastOrNull()?.ends ?: 0L
    private var elapsed = 0L
    private var started = 0L
    private var running = false
    private var selected: PreviewStep? = null
    private var overlay: Bitmap? = null
    private var closed = false
    private val imageWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val displayRect = RectF()
    var onPosition: ((Double, Double) -> Unit)? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            elapsed = (SystemClock.uptimeMillis() - started).coerceAtMost(duration)
            updateStatus(); invalidate()
            if (elapsed < duration) postDelayed(this, 32) else running = false
        }
    }

    init { setBackgroundColor(Color.WHITE); contentDescription = "Blank screen showing recorded touch positions" }

    // Rehearses once from zero; a second press cancels the previous visual timeline.
    fun restart() {
        stop(); selected = null; elapsed = 0; started = SystemClock.uptimeMillis(); running = true; post(tick)
    }

    // Cancels every scheduled frame, including when the dialog closes or its activity leaves.
    fun stop() { running = false; removeCallbacks(tick) }

    // Stops visual playback when Android removes the preview, including activity recreation.
    override fun onDetachedFromWindow() {
        stop(); closed = true; imageWorker.shutdownNow(); overlay?.recycle(); overlay = null
        super.onDetachedFromWindow()
    }

    // Selects a saved step without injecting touches; semantic steps keep their text description.
    fun select(source: JSONObject) {
        stop(); selected = sequence.firstOrNull { it.source === source }
        elapsed = selected?.ends ?: 0L; updateStatus(); invalidate()
    }

    // Decodes a bounded, user-chosen screenshot off the UI thread; it never becomes an automation target.
    fun loadOverlay(uri: String) {
        if (uri.isBlank()) return
        imageWorker.execute {
            val result = runCatching {
                val target = Uri.parse(uri)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(target).use { BitmapFactory.decodeStream(it, null, options) }
                require(options.outWidth > 0 && options.outHeight > 0)
                val recorded = sequence.firstOrNull { it.source.has("width") }?.source
                if (recorded != null) {
                    val expected = recorded.optDouble("width") / recorded.optDouble("height")
                    val actual = options.outWidth.toDouble() / options.outHeight
                    require(expected.isFinite() && kotlin.math.abs(actual / expected - 1.0) <= .01)
                }
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                context.contentResolver.openInputStream(target).use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
            (context as Activity).runOnUiThread {
                if (closed) result?.recycle() else {
                    overlay = result; invalidate()
                    if (result == null) status("Use a full-screen screenshot with the recorded orientation and proportions. Image unavailable or incompatible; macro unchanged.")
                }
            }
        }
    }

    // Allows explicit tap editing inside the fitted display; side-panel coordinates offer remote access.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onPosition == null) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP && displayRect.contains(event.x, event.y)) {
            performClick()
            onPosition?.invoke(((event.x - displayRect.left) / displayRect.width()).toDouble(),
                ((event.y - displayRect.top) / displayRect.height()).toDouble())
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    // Reports exact programmed waits/gesture durations; app-loading delays are explicitly excluded.
    private fun updateStatus() {
        val next = selected ?: sequence.firstOrNull { it.ends >= elapsed }
        val action = if (selected != null) "Selected · ${selected?.title}" else if (elapsed >= duration) "Finished" else if (next != null && elapsed < next.begins) "Waiting · ${next.title}" else next?.title.orEmpty()
        status("${seconds(elapsed)} / ${seconds(duration)} s · $action")
    }

    // Fits the recorded display in the blank canvas without distorting touch coordinates.
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val step = selected ?: sequence.firstOrNull { it.ends >= elapsed } ?: sequence.lastOrNull() ?: return
        val source = step.source
        val recorded = sequence.firstOrNull { it.source.has("width") }?.source
        val sw = source.optInt("width", recorded?.optInt("width", 1080) ?: 1080).coerceAtLeast(1).toFloat()
        val sh = source.optInt("height", recorded?.optInt("height", 1920) ?: 1920).coerceAtLeast(1).toFloat()
        val scale = minOf((width - 24 * density).coerceAtLeast(1f) / sw, (height - 24 * density).coerceAtLeast(1f) / sh)
        val left = (width - sw * scale) / 2; val top = (height - sh * scale) / 2
        displayRect.set(left, top, left + sw * scale, top + sh * scale)
        overlay?.let { bitmap ->
            val fit = minOf(displayRect.width() / bitmap.width, displayRect.height() / bitmap.height)
            val w = bitmap.width * fit; val h = bitmap.height * fit
            val x = displayRect.centerX() - w / 2; val y = displayRect.centerY() - h / 2
            ink.style = Paint.Style.FILL
            canvas.drawBitmap(bitmap, null, RectF(x, y, x + w, y + h), ink)
        }
        ink.color = Color.LTGRAY; ink.style = Paint.Style.STROKE; ink.strokeWidth = density
        canvas.drawRect(left, top, left + sw * scale, top + sh * scale, ink)
        if (elapsed < step.begins) return
        val points = source.optJSONArray("points") ?: return
        val coordinates = (0 until points.length()).mapNotNull { index ->
            points.optJSONArray(index)?.let { p ->
                val x = p.optDouble(0); val y = p.optDouble(1)
                if (x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0)
                    Pair((left + x * sw * scale).toFloat(), (top + y * sh * scale).toFloat()) else null
            }
        }
        if (coordinates.isEmpty()) return
        // Android follows the path by distance; mirror that visual progression rather than point count.
        val lengths = coordinates.zipWithNext { a, b -> hypot(b.first - a.first, b.second - a.second) }
        val progress = ((elapsed - step.begins).toFloat() / (step.ends - step.begins).coerceAtLeast(1)).coerceIn(0f, 1f)
        var remaining = lengths.sum() * progress
        var marker = coordinates.first()
        val path = Path().apply { moveTo(marker.first, marker.second) }
        for (i in lengths.indices) {
            val a = coordinates[i]; val b = coordinates[i + 1]; val length = lengths[i]
            val fraction = if (length == 0f) 1f else (remaining / length).coerceIn(0f, 1f)
            marker = Pair(a.first + (b.first - a.first) * fraction, a.second + (b.second - a.second) * fraction)
            path.lineTo(marker.first, marker.second); remaining -= length
            if (remaining <= 0f) break
        }
        ink.color = Color.rgb(30, 96, 210); ink.strokeWidth = 3 * density; canvas.drawPath(path, ink)
        canvas.drawCircle(marker.first, marker.second, 12 * density, ink)
        ink.style = Paint.Style.FILL; canvas.drawCircle(marker.first, marker.second, 3 * density, ink)
    }

    companion object {
        private fun seconds(value: Long) = String.format(Locale.getDefault(), "%.3f", value / 1000.0)

        // Matches replay speed, bounded waits, gesture limits and initial-launch skipping.
        fun create(activity: Activity, macro: JSONObject, label: (JSONObject) -> String, status: (String) -> Unit): MacroPreviewView {
            val steps = macro.optJSONArray("steps") ?: org.json.JSONArray()
            val speed = macro.optDouble("speed", 1.0).let { if (it.isFinite()) it.coerceIn(.25, 4.0) else 1.0 }
            var skipLaunch = macro.optBoolean("currentScreen", false)
            var time = 0L
            val sequence = mutableListOf<PreviewStep>()
            for (index in 0 until steps.length()) {
                val source = steps.optJSONObject(index) ?: continue
                val kind = source.optString("kind", "click")
                if (skipLaunch && kind in setOf("home", "launch")) continue
                skipLaunch = false
                time += (source.optLong("delay", 350).coerceIn(0, 10000) / speed).toLong().coerceIn(40, 10000)
                val starts = time
                if (kind == "gesture") time += (source.optLong("duration", 40) / speed).toLong().coerceIn(40, 3000)
                sequence.add(PreviewStep(source, label(source), starts, time))
            }
            return MacroPreviewView(activity, sequence, status).also {
                it.updateStatus(); it.loadOverlay(macro.optString("overlayUri"))
            }
        }

    }
}
