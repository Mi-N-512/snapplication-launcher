package app.snapplication.launcher

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/*
 * Centres a square favourite tile inside MainActivity's equal-width, equal-height portrait grid slots.
 *
 * Related classes: MainActivity provides the favourite shortcut view inside each portrait grid slot.
 * UI: A square wrapper that centres its child within available space.
 * Edit or extend: Edit tile measurement/alignment here; favourite selection and ordering belong to MainActivity and
 * DrawerPanel.
 */
class SquareShortcut(context: Context, shortcut: View) : FrameLayout(context) {
    init {
        addView(shortcut, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
    }

    // Measures the tile against the smaller slot dimension without stretching its containing grid cell.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = minOf(measuredWidth, measuredHeight)
        getChildAt(0).measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
    }
}
