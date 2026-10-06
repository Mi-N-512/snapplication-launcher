package app.snapplication.launcher

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/** Builds the first, static setup screen for MainActivity; LauncherState owns profile and theme defaults. */
class DeviceProfileSetup(private val activity: Activity, private val state: LauncherState) {
    /** Lets users choose a device and see its theme before any permission or media setup pages. */
    fun build(onChanged: () -> Unit, onContinue: () -> Unit): View {
        // Setup is deliberately opaque and never animates, including on an unrecognised e-reader.
        val ui = LauncherUi(activity, state, false)
        val root = ui.column().apply { setBackgroundColor(ui.background) }
        val body = ui.column().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(12)) }
        root.addView(ScrollView(activity).apply { addView(body); isFillViewport = true },
            LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(ui.section("WELCOME TO SNAPPLICATION"))
        body.addView(ui.label("Make this screen yours", 28f))
        body.addView(ui.label("Choose your device and theme first. You can change either later in Settings.", 16f))
        body.addView(ui.section("1 · Your device"))
        val descriptions = linkedMapOf(
            "Reader" to "E-paper, including Meebook M6 · books and pages, portrait, no animation",
            "Phone" to "Touch controls · resume videos or switch to reading",
            "TV" to "Landscape · large controls for a remote")
        descriptions.forEach { (profile, description) ->
            body.addView(ui.setting(profile, description, if (state.deviceProfile == profile) "Selected" else "Choose") {
                state.applyDeviceProfile(profile)
                onChanged()
            })
        }
        body.addView(ui.section("2 · Your theme"))
        listOf("E-paper", "Light", "Dark").forEach { theme ->
            val explanation = when (theme) {
                "E-paper" -> "Black on white · no animation or transparency"
                "Light" -> "Bright surfaces with dark text"
                else -> "Dark surfaces with light text"
            }
            body.addView(ui.setting(theme, explanation, if (state.theme == theme) "Selected" else "Choose") {
                state.theme = theme
                onChanged()
            })
        }
        body.addView(ui.label(if (state.deviceProfile == "Reader")
            "Reader setup follows. Screen preview permission is optional; your books stay in their reading apps."
            else "Next: arrange your apps, then choose which optional features to enable.", 14f))
        root.addView(ui.button("Continue") {
            state.profileSetupComplete = true
            onContinue()
        }, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { setMargins(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(12)) })
        return root
    }
}
