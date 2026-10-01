package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Previews custom theme colours for MainActivity and commits them to LauncherState only on Save. */
class ColourPicker(private val activity: Activity, private val state: LauncherState, private val ui: LauncherUi) {
    private val colours = intArrayOf(state.background, state.accent)
    private var selected = 0
    private var updating = false
    private val hsv = FloatArray(3)
    private val sliders = mutableListOf<SeekBar>()
    private val labels = mutableListOf<TextView>()
    private val preview = ui.label("Theme preview", 22f)
    private val code = ui.label("", 17f)

    /** Builds hue, saturation and brightness controls usable by touch, mouse and directional keys. */
    fun show(onSaved: () -> Unit) {
        val content = ui.column().apply { setBackgroundColor(ui.background) }
        val roles = RadioGroup(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val roleIds = List(2) { android.view.View.generateViewId() }
        listOf("Background", "Accent").forEachIndexed { index, name ->
            roles.addView(RadioButton(activity).apply {
                id = roleIds[index]; text = name; setTextColor(ui.text); minHeight = ui.dp(48)
            })
        }
        roles.check(roleIds[0])
        content.addView(roles)
        content.addView(preview, LinearLayout.LayoutParams(-1, ui.dp(80)))
        content.addView(code)
        listOf("Hue", "Saturation", "Brightness").forEachIndexed { index, name ->
            val label = ui.label(name, 17f)
            labels.add(label); content.addView(label)
            val slider = SeekBar(activity).apply {
                max = if (index == 0) 360 else 100
                contentDescription = name
                minimumHeight = ui.dp(48)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    /** Updates the staged colour and preview without writing preferences while dragging. */
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (updating || !fromUser) return
                        hsv[index] = if (index == 0) value.toFloat() else value / 100f
                        colours[selected] = Color.HSVToColor(hsv)
                        refreshPreview()
                    }
                    /** No transaction starts until the dialog's Save action. */
                    override fun onStartTrackingTouch(bar: SeekBar) {}
                    /** Retains the preview when the user releases the slider. */
                    override fun onStopTrackingTouch(bar: SeekBar) {}
                })
            }
            sliders.add(slider); content.addView(slider, LinearLayout.LayoutParams(-1, ui.dp(48)))
        }
        roles.setOnCheckedChangeListener { _, id ->
            val index = roleIds.indexOf(id)
            if (index >= 0) { selected = index; loadColour() }
        }
        loadColour()
        AlertDialog.Builder(activity).setTitle("Custom colours")
            .setView(ScrollView(activity).apply { addView(content) })
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                state.background = colours[0]; state.accent = colours[1]; state.theme = "Custom"
                onSaved()
            }.show()
    }

    /** Restores the selected colour into the sliders without generating artificial edits. */
    private fun loadColour() {
        updating = true
        Color.colorToHSV(colours[selected], hsv)
        sliders.forEachIndexed { index, slider ->
            slider.progress = if (index == 0) hsv[index].toInt() else (hsv[index] * 100).toInt()
        }
        updating = false
        refreshPreview()
    }

    /** Renders a theme sample plus colour gradients and numeric values for precise adjustment. */
    private fun refreshPreview() {
        preview.setTextColor(if (Color.luminance(colours[0]) > 0.45) Color.BLACK else Color.WHITE)
        preview.background = GradientDrawable().apply {
            setColor(colours[0]); setStroke(ui.dp(4), colours[1]); cornerRadius = ui.dp(8).toFloat()
        }
        code.text = "${if (selected == 0) "Background" else "Accent"} · #%06X".format(colours[selected] and 0xffffff)
        val names = listOf("Hue", "Saturation", "Brightness")
        sliders.forEachIndexed { index, slider ->
            labels[index].text = "${names[index]} · ${slider.progress}${if (index == 0) "°" else "%"}"
            val gradient = when (index) {
                0 -> intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED)
                1 -> intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
                else -> intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            }
            slider.progressTintList = null
            slider.progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, gradient)
                .apply { cornerRadius = ui.dp(4).toFloat(); setSize(ui.dp(200), ui.dp(8)) }
        }
    }
}
