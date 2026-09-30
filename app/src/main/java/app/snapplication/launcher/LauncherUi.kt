package app.snapplication.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Builds consistent accessible native controls for MainActivity and ReaderPanel using LauncherState colours. */
class LauncherUi(private val context: Context, state: LauncherState) {
    val background: Int = when (state.theme) {
        "OLED Black" -> Color.BLACK
        "Light", "E-paper" -> Color.WHITE
        "Custom" -> state.background
        else -> 0xff1a1a1a.toInt()
    }
    val text: Int = if (Color.luminance(background) > 0.45) Color.BLACK else Color.WHITE
    val surface: Int = if (text == Color.BLACK) 0xffeeeeee.toInt() else 0xff292929.toInt()
    val accent: Int = when {
        state.theme == "Custom" -> state.accent
        state.theme == "Material You" && Build.VERSION.SDK_INT >= 31 -> context.getColor(android.R.color.system_accent1_200)
        else -> text
    }

    /** Converts design dimensions to device-independent pixels. */
    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    /** Creates a vertical or horizontal container shared across launcher screens. */
    fun column(horizontal: Boolean = false): LinearLayout = LinearLayout(context).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        // The page shell owns its background, allowing Home to reveal the system wallpaper.
    }

    /** Creates readable text with generous line spacing for television and touch use. */
    fun label(value: String, size: Float = 20f): TextView = TextView(context).apply {
        text = value; textSize = size; setTextColor(this@LauncherUi.text)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Creates a focusable control with a visible directional-navigation focus ring. */
    fun button(value: String, action: () -> Unit): Button = Button(context).apply {
        text = value; textSize = 17f; isAllCaps = false
        minHeight = dp(48); setTextColor(this@LauncherUi.text)
        backgroundTintList = null
        // Outline the active remote target without changing its dimensions.
        fun decorate(focused: Boolean) {
            background = GradientDrawable().apply {
                setColor(surface); cornerRadius = dp(7).toFloat()
                setStroke(dp(if (focused) 3 else 1), if (focused) accent else surface)
            }
        }
        decorate(false)
        setOnFocusChangeListener { _, focused -> decorate(focused) }
        setOnClickListener { action() }
        isFocusable = true
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }

    /** Adds equally weighted controls to a toolbar without squeezing vertical touch targets. */
    fun addWeighted(row: LinearLayout, view: View) {
        row.addView(view, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }
}
