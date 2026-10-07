package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale
import kotlin.math.hypot

/** Safe blank-screen rehearsal opened by MacroEditor. Draws recorded gestures and timing only;
 * it never calls RemoteActionService, opens another app, or injects a touch. */
class MacroPreviewView private constructor(context: Context, private val sequence: List<PreviewStep>,
    private val status: (String) -> Unit) : View(context) {
    private data class PreviewStep(val source: JSONObject, val title: String, val begins: Long, val ends: Long)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val duration = sequence.lastOrNull()?.ends ?: 0L
    private var elapsed = 0L
    private var started = 0L
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            elapsed = (SystemClock.uptimeMillis() - started).coerceAtMost(duration)
            updateStatus(); invalidate()
            if (elapsed < duration) postDelayed(this, 32) else running = false
        }
    }

    init { setBackgroundColor(Color.WHITE); contentDescription = "Blank screen showing recorded touch positions" }

    /** Rehearses once from zero; a second press cancels the previous visual timeline. */
    fun restart() {
        stop(); elapsed = 0; started = SystemClock.uptimeMillis(); running = true; post(tick)
    }

    /** Cancels every scheduled frame, including when the dialog closes or its activity leaves. */
    fun stop() { running = false; removeCallbacks(tick) }

    /** Stops visual playback when Android removes the preview, including activity recreation. */
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    /** Reports exact programmed waits/gesture durations; app-loading delays are explicitly excluded. */
    private fun updateStatus() {
        val next = sequence.firstOrNull { it.ends >= elapsed }
        val action = if (elapsed >= duration) "Finished" else if (next != null && elapsed < next.begins) "Waiting · ${next.title}" else next?.title.orEmpty()
        status("${seconds(elapsed)} / ${seconds(duration)} s · $action")
    }

    /** Fits the recorded display in the blank canvas without distorting touch coordinates. */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val step = sequence.firstOrNull { it.ends >= elapsed } ?: sequence.lastOrNull() ?: return
        val source = step.source
        val sw = source.optInt("width", 1080).coerceAtLeast(1).toFloat()
        val sh = source.optInt("height", 1920).coerceAtLeast(1).toFloat()
        val scale = minOf((width - 24 * density).coerceAtLeast(1f) / sw, (height - 24 * density).coerceAtLeast(1f) / sh)
        val left = (width - sw * scale) / 2; val top = (height - sh * scale) / 2
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

        /** Matches replay speed, bounded waits, gesture limits and initial-launch skipping. */
        fun show(activity: Activity, name: String, macro: JSONObject, label: (JSONObject) -> String) {
            val steps = macro.optJSONArray("steps") ?: return
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
            val density = activity.resources.displayMetrics.density
            val body = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            }
            val status = TextView(activity).apply { text = "Programmed duration: ${seconds(time)} s at ${speed}×"; textSize = 16f }
            body.addView(status)
            val canvas = MacroPreviewView(activity, sequence) { status.text = it }
            body.addView(canvas, LinearLayout.LayoutParams(-1, (180 * density).toInt()))
            body.addView(TextView(activity).apply {
                text = "Visual preview only. Real playback may take longer while apps open. Touch positions are shown on a blank screen; named actions appear in the timeline."
                textSize = 12f
            })
            body.addView(TextView(activity).apply {
                text = sequence.mapIndexed { i, step -> "${i + 1}. ${seconds(step.begins)} s · ${step.title}${if (step.ends > step.begins) " · ${seconds(step.ends - step.begins)} s" else ""}" }.joinToString("\n")
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            })
            val scroll = ScrollView(activity).apply { addView(body) }
            val dialog = AlertDialog.Builder(activity).setTitle("Preview · $name").setView(scroll)
                .setPositiveButton("Play preview", null).setNeutralButton("Stop", null).setNegativeButton("Close", null).create()
            dialog.setOnDismissListener { canvas.stop() }
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { canvas.restart() }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { canvas.stop() }
            }
            dialog.show()
        }
    }
}
