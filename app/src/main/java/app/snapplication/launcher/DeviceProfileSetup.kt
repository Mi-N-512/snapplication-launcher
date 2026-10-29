package app.snapplication.launcher

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/*
Builds the first, static setup screen for MainActivity; LauncherState owns profile and theme
defaults.

This class is a helper and does not inherit from another launcher class.

activity creates Android controls and state stores the selected profile and theme. build creates the
choices and connects each button to a callback supplied by MainActivity. A choice updates state
before the activity redraws the screen; Continue moves into the guided setup. LauncherUi supplies
the shared control styles.

Used by or used with: MainActivity, LauncherState and LauncherUi. Screen controls: Static
Reader/Phone/TV choices, theme choices and Continue button. Changes belong here: Edit first-run
descriptions and layout here; change saved profile defaults in LauncherState. A future Gaming
profile needs defaults and a real destination too.
*/
class DeviceProfileSetup(private val activity: Activity, private val state: LauncherState) {
    // Outlines and ticks the chosen row so the current choice stands out at a glance and is
    // announced as selected.
    private fun choice(ui: LauncherUi, title: String, description: String, selected: Boolean, action: () -> Unit): View =
        ui.setting(title, description, if (selected) "✓ Selected" else "Choose", action).apply {
            isSelected = selected
            if (selected) foreground = android.graphics.drawable.GradientDrawable().apply { setStroke(ui.dp(2), ui.text) }
        }

    // Lets users choose a device and see its theme before any permission or media setup pages.
    fun build(onChanged: () -> Unit, onContinue: () -> Unit): View {
        // Setup stays still. E-paper is opaque; other themes can show the wallpaper.
        val ui = LauncherUi(activity, state, state.useWallpaper && state.theme != "E-paper")
        val root = ui.column().apply { setBackgroundColor(if (state.useWallpaper && state.theme != "E-paper") android.graphics.Color.TRANSPARENT else ui.background) }
        val body = ui.column().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(12)) }
        root.addView(ScrollView(activity).apply { addView(body); isFillViewport = true },
            LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(ui.section("WELCOME TO SNAPPLICATION"))
        body.addView(ui.label("Make this screen yours", 28f))
        body.addView(ui.label(if (state.readerOnly) "Your reading launcher. Choose a comfortable theme to begin." else "Choose your device and theme first. You can change either later in Settings.", 16f))
        if (!state.readerOnly) body.addView(ui.section("1 · Your device"))
        val descriptions = linkedMapOf(
            "Reader" to "E-paper · books and pages, portrait, no animation",
            "Phone" to "Rotate upright to read · sideways to watch",
            "TV" to "Landscape · grid favourites · large controls for a remote")
        if (!state.readerOnly) descriptions.forEach { (profile, description) ->
            body.addView(choice(ui, profile, description, state.deviceProfile == profile) {
                state.applyDeviceProfile(profile)
                onChanged()
            })
        }
        body.addView(ui.section(if (state.readerOnly) "Your reading theme" else "2 · Your theme"))
        listOf("E-paper", "Light", "Dark", "OLED Black").forEach { theme ->
            val explanation = when (theme) {
                "E-paper" -> "Black on white · no animation or transparency"
                "Light" -> "Bright surfaces · pastel pink accents"
                "OLED Black" -> "Black surfaces · racing green accents"
                else -> "Dark surfaces · racing green accents"
            }
            body.addView(choice(ui, theme, explanation, state.theme == theme) {
                state.theme = theme
                onChanged()
            })
        }
        if (state.theme != "E-paper") body.addView(ui.setting("View wallpaper", "Show your background behind the launcher", if (state.useWallpaper) "On" else "Off") {
            state.useWallpaper = !state.useWallpaper; onChanged()
        })
        body.addView(ui.label(if (state.deviceProfile == "Reader")
            "Reader setup follows. Screen preview permission is optional; your books stay in their reading apps."
            else "Next: arrange your apps, then choose which optional features to enable.", 14f))
        root.addView(ui.primaryButton("Continue") {
            state.profileSetupComplete = true
            onContinue()
        }, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { setMargins(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(12)) })
        return root
    }
}
