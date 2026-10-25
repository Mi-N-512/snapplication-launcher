package app.snapplication.launcher

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/*
LauncherWalkthrough builds the compact coaching area attached to the real launcher, not a separate
slide deck. MainActivity owns the saved tour step and routes the actions to Home, DrawerPanel,
preview settings and Android permission screens. DeviceProfileSetup still owns the first mode and
colour choice. LauncherUi supplies the shared colours, flat focus states and readable labels. The
launcher above this panel remains interactive.

This class is a helper and does not inherit from another launcher class.

context creates the controls and ui gives them the current theme. build receives the current step,
explanation and action from MainActivity, then connects Back, Continue and Skip to the supplied
functions. MainActivity keeps the saved step and attaches the returned panel to the working screen.
DeviceProfileSetup handles the earlier device and colour choices.

Screen controls: Progress marks, a scrollable explanation, one setup action and Back/Skip/Continue
controls. MainActivity docks this panel beside the working screen in landscape, below it in
portrait. It uses the current translucent TV theme or opaque e-paper surface, with angular controls
and no simulated content. Controls use native focusable buttons for touch, directional remotes and
accessibility services. No animation, screenshots or simulated permission approvals are used,
keeping the tour suitable for static e-paper screens. Edit spacing and coaching presentation here;
edit step wording and destinations in MainActivity.attachGuide(). Future modes can add steps there
without duplicating the real screens or permission logic.
*/
class LauncherWalkthrough(private val context: Context, private val ui: LauncherUi) {
    // Builds a limited, readable coach with separate action and navigation rows that fit narrow
    // displays.
    fun build(step: Int, count: Int, title: String, detail: String, action: String,
        onAction: () -> Unit, onBack: () -> Unit, onNext: () -> Unit, onSkip: () -> Unit): View {
        val wide = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val panel = ui.column().apply {
            setBackgroundColor(ui.surface)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
        }
        val progress = ui.column(true)
        repeat(count) { index ->
            progress.addView(View(context).apply { setBackgroundColor(if (index <= step) ui.focusAccent else ui.border) },
                LinearLayout.LayoutParams(0, ui.dp(3), 1f).apply { marginEnd = ui.dp(4) })
        }
        panel.addView(progress)
        val explanation = ui.column()
        explanation.addView(ui.label("SETUP ${step + 1} / $count", 12f).apply { setBackgroundColor(Color.TRANSPARENT) })
        explanation.addView(ui.label(title, 21f).apply {
            setBackgroundColor(Color.TRANSPARENT)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        explanation.addView(ui.label(detail, 14f).apply { setBackgroundColor(Color.TRANSPARENT) })
        panel.addView(ScrollView(context).apply { addView(explanation); isFillViewport = false },
            if (wide) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, ui.dp((context.resources.configuration.screenHeightDp / 4).coerceIn(120, 160))))
        val controls = ui.column()
        panel.addView(controls, LinearLayout.LayoutParams(-1, -2))
        controls.addView(ui.button(action, onAction).apply {
            textSize = 15f; ui.flatFocus(this, ui.surface, outline = false)
        }, LinearLayout.LayoutParams(-1, ui.dp(48)))
        val navigation = ui.column(true)
        listOf("Back" to onBack,
            (if (step == count - 1) "Finish" else "Continue") to onNext).forEachIndexed { index, (label, callback) ->
            navigation.addView((if (index == 1) ui.primaryButton(label, callback) else ui.button(label, callback)).apply {
                textSize = 14f
                if (index == 0) ui.flatFocus(this, ui.surface, outline = false)
                if (index == 0) { isEnabled = step > 0; alpha = if (isEnabled) 1f else 0.5f }
            }, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        }
        controls.addView(navigation)
        controls.addView(ui.button("Skip setup", onSkip).apply {
            textSize = 14f; ui.flatFocus(this, Color.TRANSPARENT, outline = false)
        }, LinearLayout.LayoutParams(-1, ui.dp(48)))
        return panel
    }
}
