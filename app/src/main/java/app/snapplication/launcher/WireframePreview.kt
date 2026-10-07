package app.snapplication.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/*
 * Draws miniature theme and favourite layouts for MainActivity's settings, using LauncherUi colours.
 *
 * Related classes: MainActivity displays layout/theme samples using LauncherUi colours.
 * UI: Small painted layout previews; these are illustrations, not interactive app tiles.
 * Edit or extend: Edit the sample geometry and colours here when available layout choices change.
 */
class WireframePreview(context: Context, private val backgroundColour: Int, private val foreground: Int,
    private val columns: Int = 0, private val count: Int = 3) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Renders a small phone/list or two-row grid without adding redundant accessibility nodes.
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = backgroundColour
        canvas.drawRoundRect(1f, 1f, w - 1, h - 1, 8f, 8f, paint)
        paint.color = foreground; paint.alpha = 100
        if (columns == 0) {
            canvas.drawRect(w * .1f, h * .12f, w * .9f, h * .30f, paint)
            repeat(3) { index ->
                val y = h * (.45f + index * .14f)
                canvas.drawRect(w * .18f, y, w * .82f, y + h * .04f, paint)
            }
        } else {
            val cellW = w / columns; val cellH = h / 2
            repeat(count) { index ->
                val x = (index % columns) * cellW; val y = (index / columns) * cellH
                canvas.drawRoundRect(x + 4, y + 4, x + cellW - 4, y + cellH - 4, 4f, 4f, paint)
            }
        }
        paint.alpha = 255; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        paint.color = Color.GRAY
        canvas.drawRoundRect(1f, 1f, w - 1, h - 1, 8f, 8f, paint)
        paint.style = Paint.Style.FILL
    }
}
