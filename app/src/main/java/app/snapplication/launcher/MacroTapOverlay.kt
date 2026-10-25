package app.snapplication.launcher

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/*
Shows brief, non-interactive tap rings during macro playback. It never sends input.
RemoteActionService and ProjectionImageRunner call flash only after validation; cancellation clears
it. Screen controls: High-contrast ring and centre dot. Settings owns the global visibility
preference. Keep this overlay non-focusable and non-touchable so it cannot consume or duplicate a
tap.

This class is a helper and does not inherit from another launcher class.

service gives access to the accessibility window manager. view holds the temporary marker and
handler removes it after its brief display. flash draws a ring at the supplied screen coordinates;
clear removes it. RemoteActionService and ProjectionImageRunner use this helper to show an already
chosen tap position. The helper does not dispatch the tap.
*/
internal class MacroTapOverlay(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    fun clear() {
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { service.getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
    }
    // Draws once at screen coordinates and removes itself without affecting gesture completion.
    fun flash(x: Float, y: Float) {
        clear()
        val density = service.resources.displayMetrics.density
        val size = (44 * density).toInt()
        val marker = object : View(service) {
            private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                ink.style = Paint.Style.STROKE; ink.strokeWidth = 5 * density; ink.color = Color.BLACK
                canvas.drawCircle(width / 2f, height / 2f, 15 * density, ink)
                ink.strokeWidth = 3 * density; ink.color = Color.CYAN
                canvas.drawCircle(width / 2f, height / 2f, 15 * density, ink)
                ink.style = Paint.Style.FILL; canvas.drawCircle(width / 2f, height / 2f, 3 * density, ink)
            }
        }
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; this.x = (x - size / 2).toInt(); this.y = (y - size / 2).toInt()
        }
        if (runCatching { service.getSystemService(WindowManager::class.java).addView(marker, params) }.isSuccess) {
            view = marker; handler.postDelayed({ clear() }, 450)
        }
    }
}
