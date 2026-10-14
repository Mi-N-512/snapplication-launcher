package app.snapplication.launcher

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/*
 * LauncherWalkthrough builds the compact coaching area attached to the real launcher, not a separate slide deck.
 * MainActivity owns the saved tour step and routes the actions to Home, DrawerPanel, preview settings and Android
 * permission screens. DeviceProfileSetup still owns the first mode and colour choice. LauncherUi supplies the
 * shared colours, flat focus states and readable labels. The launcher above this panel remains interactive.
 *
 * UI: A numbered heading, scrollable explanation, one relevant setup action and Back/Skip/Next controls.
 * Controls use native focusable buttons for touch, directional remotes and accessibility services. No animation,
 * screenshots or simulated permission approvals are used, keeping the tour suitable for static e-paper screens.
 * Edit spacing and coaching presentation here; edit step wording and destinations in MainActivity.attachGuide().
 * Future modes can add steps there without duplicating the real screens or permission logic.
 */
class LauncherWalkthrough(private val context: Context, private val ui: LauncherUi) {
    // Builds a bounded, readable coach with separate action and navigation rows that fit narrow displays.
    fun build(step: Int, count: Int, title: String, detail: String, action: String,
        onAction: () -> Unit, onBack: () -> Unit, onNext: () -> Unit, onSkip: () -> Unit): View {
        val panel = ui.column().apply { setBackgroundColor(ui.background) }
        panel.addView(View(context).apply { setBackgroundColor(ui.border) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
        val explanation = ui.column()
        explanation.addView(ui.label("${step + 1} / $count · $title", 16f).apply {
            setBackgroundColor(Color.TRANSPARENT)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        explanation.addView(ui.label(detail, 14f).apply { setBackgroundColor(Color.TRANSPARENT) })
        panel.addView(ScrollView(context).apply { addView(explanation); isFillViewport = false },
            LinearLayout.LayoutParams(-1, ui.dp(84)))
        panel.addView(ui.button(action, onAction).apply {
            textSize = 15f; ui.flatFocus(this, ui.surface, outline = false)
        }, LinearLayout.LayoutParams(-1, ui.dp(48)))
        val navigation = ui.column(true)
        listOf("Back" to onBack, "Skip tour" to onSkip,
            (if (step == count - 1) "Finish" else "Next") to onNext).forEachIndexed { index, (label, callback) ->
            navigation.addView(ui.button(label, callback).apply {
                textSize = 14f
                ui.flatFocus(this, ui.surface, outline = false)
                if (index == 0) { isEnabled = step > 0; alpha = if (isEnabled) 1f else 0.5f }
            }, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        }
        panel.addView(navigation)
        return panel
    }
}
