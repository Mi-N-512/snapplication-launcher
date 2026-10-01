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
class LauncherUi(private val context: Context, state: LauncherState, private val glass: Boolean = false) {
    val background: Int = when (state.theme) {
        "OLED Black" -> Color.BLACK
        "Light" -> 0xfffafafa.toInt()
        "E-paper" -> Color.WHITE
        "Material You" -> 0xff1c1b1f.toInt()
        "Custom" -> state.background
        else -> 0xff1a1a1a.toInt()
    }
    val text: Int = if (Color.luminance(background) > 0.45) Color.BLACK else Color.WHITE
    val surface: Int = glassColour(when (state.theme) {
        "OLED Black" -> 0xff0d0d0d.toInt()
        "Material You" -> 0xff2b2930.toInt()
        "Light", "E-paper" -> Color.WHITE
        else -> if (text == Color.BLACK) 0xfff0f0f0.toInt() else 0xff252525.toInt()
    })
    /** Provides a translucent reading surface when the shared Transparency setting is active. */
    private fun glassColour(colour: Int): Int = if (glass)
        (colour and 0x00ffffff) or (if (text == Color.BLACK) 0xcc000000.toInt() else 0xaa000000.toInt()) else colour

    val border: Int = if (text == Color.BLACK) 0xffe0e0e0.toInt() else 0xff383838.toInt()
    val muted: Int = if (text == Color.BLACK) 0xff666666.toInt() else 0xffaaaaaa.toInt()
    val accent: Int = when {
        state.theme == "Custom" -> state.accent
        state.theme == "Material You" && Build.VERSION.SDK_INT >= 31 -> context.getColor(android.R.color.system_accent1_200)
        else -> text
    }

    /** Layers translucent highlights over the live wallpaper; controls remain sharp above the surface. */
    fun homeSurface(): android.graphics.drawable.Drawable = if (glass) GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (text == Color.BLACK) intArrayOf(0x70ffffff, 0x40ffffff, 0x60ffffff)
        else intArrayOf(0x30ffffff, 0x18181818, 0x38383838)
    ).apply { setStroke(dp(1), 0x40ffffff) }
    else android.graphics.drawable.ColorDrawable(surface)

    /** Keeps foreground text readable while revealing wallpaper through labelled controls. */
    fun textSurface(): Int = if (!glass) Color.TRANSPARENT
        else if (text == Color.BLACK) 0xcceeeeee.toInt() else 0xaa181818.toInt()

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
        if (glass) setBackgroundColor(if (this@LauncherUi.text == Color.BLACK) 0xcceeeeee.toInt() else 0xaa181818.toInt())
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
                setColor(if (glass) (if (this@LauncherUi.text == Color.BLACK) 0xcceeeeee.toInt() else 0xaa181818.toInt()) else surface); cornerRadius = dp(7).toFloat()
                setStroke(dp(if (focused) 3 else 1), if (focused) accent else surface)
            }
        }
        decorate(false)
        setOnFocusChangeListener { _, focused -> decorate(focused) }
        setOnClickListener { action() }
        isFocusable = true
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }

    /** Creates the wireframe's flat row while preserving remote focus feedback. */
    fun row(value: String, action: () -> Unit): Button = button(value, action).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        textSize = 16f
        flatFocus(this, surface)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        minHeight = dp(64)
    }

    /** Preserves flat reference styling through focus changes, with a clear remote outline. */
    fun flatFocus(view: View, colour: Int = Color.TRANSPARENT, outline: Boolean = true) {
        view.onFocusChangeListener = null
        view.background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                setColor(colour); setStroke(dp(2), accent)
            })
            addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(surface) })
            addState(intArrayOf(), GradientDrawable().apply {
                if (glass && colour != Color.TRANSPARENT) {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(if (text == Color.BLACK) 0xddffffff.toInt() else 0x99505768.toInt(), colour)
                } else setColor(colour)
                if (outline) setStroke(dp(1), if (glass) 0x40ffffff else border)
            })
        }
    }

    /** Builds a two-line settings row with the reference's muted subtitle and right-side action. */
    fun setting(title: String, subtitle: String, value: String = "›", action: () -> Unit): LinearLayout = column(true).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(72)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        flatFocus(this, surface)
        isFocusable = true; isClickable = true
        contentDescription = "$title, $subtitle, $value"
        val words = column()
        words.addView(label(title, 16f).apply { setPadding(0, 0, 0, dp(4)); setBackgroundColor(Color.TRANSPARENT) })
        words.addView(label(subtitle, 11f).apply {
            setTextColor(muted); setPadding(0, 0, 0, 0); setBackgroundColor(Color.TRANSPARENT)
        })
        addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label(value, 17f).apply { setBackgroundColor(Color.TRANSPARENT) })
        setOnClickListener { action() }
    }

    /** Labels a group using the reference's spaced, small uppercase type. */
    fun section(value: String): TextView = label(value.uppercase(), 11f).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        letterSpacing = 0.12f
        setTextColor(muted)
        setPadding(dp(16), dp(20), dp(16), dp(8))
    }

    /** Adds equally weighted controls to a toolbar without squeezing vertical touch targets. */
    fun addWeighted(row: LinearLayout, view: View) {
        row.addView(view, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
    }
}
