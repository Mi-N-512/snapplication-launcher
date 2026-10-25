package app.snapplication.launcher

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/*
Measures a shortcut as a square inside a larger layout slot. This older wrapper is currently unused.

SquareShortcut is a child of Android's FrameLayout. FrameLayout holds the shortcut view and supplies
the measurement callbacks.

shortcut is the child view placed inside this container. Android calls onMeasure with the available
space; the function uses the smaller dimension to make that child square. The current MainActivity
does not instantiate this older wrapper. If it is used again, selection and favourite ordering still
belong to LauncherState and DrawerPanel.
*/
class SquareShortcut(context: Context, shortcut: View) : FrameLayout(context) {
    init {
        addView(shortcut, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
    }

    // Measures the tile against the smaller slot dimension without stretching its containing grid
    // cell.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = minOf(measuredWidth, measuredHeight)
        getChildAt(0).measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
    }
}
