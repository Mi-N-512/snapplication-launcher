package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/** Builds MainActivity's remote setup using RemoteControls assignments and MacroEditor recordings.
 * The generic diagram represents actions, not an inferred photograph or layout of the connected remote. */
class RemoteMappingPanel(
    private val activity: Activity,
    private val ui: LauncherUi,
    private val remote: RemoteControls
) {
    /** Creates a remote-friendly setup page with a diagram, saved keys and a record-then-assign flow. */
    fun build(
        onAccessibility: () -> Unit,
        onRecordAction: () -> Unit,
        onManageActions: () -> Unit,
        onChanged: () -> Unit
    ): View {
        val body = ui.column()
        body.addView(ui.label("Your remote, your shortcuts", 23f))
        body.addView(ui.label("Connect a remote by USB or Bluetooth. Choose an action below, then press and release the button you want to use.", 16f))
        if (RemoteActionService.instance == null) {
            body.addView(ui.button("Enable controls in other apps", onAccessibility))
        }
        body.addView(diagram(onChanged))
        body.addView(ui.label("Generic remote · choose an action to learn a physical button. Your remote may have a different layout.", 13f))
        body.addView(ui.button("Assign any button") { chooseAction { action -> remote.learn(action, onChanged) } })
        body.addView(ui.section("Recorded actions"))
        body.addView(ui.label("1. Record the steps you want.\n2. Stop and save the recording.\n3. Assign it to a remote button.", 16f))
        body.addView(ui.button("Record an action", onRecordAction))
        val names = RemoteActionService.savedMacroNames(activity)
        if (names.isNotEmpty()) {
            body.addView(ui.button("Assign a recorded action") {
                AlertDialog.Builder(activity).setTitle("Choose recorded action")
                    .setItems(names.toTypedArray()) { _, index -> remote.learn("Macro: ${names[index]}", onChanged) }
                    .setNegativeButton("Cancel", null).show()
            })
            body.addView(ui.button("Edit recordings and timing", onManageActions))
        }
        body.addView(ui.section("Saved buttons"))
        val mappings = remote.savedMappings()
        if (mappings.isEmpty()) {
            body.addView(ui.label("No custom assignments yet. Standard directional buttons still work.", 16f))
        } else {
            mappings.forEach { mapping ->
                body.addView(ui.button("${mapping.keyLabel} → ${mapping.action}\n${mapping.deviceName}") {
                    edit(mapping, onChanged)
                }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
            }
        }
        body.addView(ui.section("Screen off and system buttons"))
        body.addView(ui.label("Assign Screen off to any button Android delivers. Some remotes send Power or Home directly to Android, so those physical keys cannot be learned here. Screen off does not guarantee that HDMI disconnects; waking the phone depends on its hardware.", 15f))
        body.addView(ui.button("Try screen off now") {
            RemoteActionService.instance?.performMappedAction("Screen off") ?: onAccessibility()
        })
        if (mappings.isNotEmpty()) body.addView(ui.button("Reset all assignments") {
            AlertDialog.Builder(activity).setTitle("Reset all remote assignments?")
                .setMessage("This removes saved button assignments from every remote. Recorded actions are kept.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reset") { _, _ -> remote.reset(); onChanged() }.show()
        })
        return body
    }

    /** Uses actual focusable buttons so every diagram action supports touch, keyboard and remote navigation. */
    private fun diagram(onChanged: () -> Unit): View {
        val surround = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val device = ui.column().apply {
            setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12))
            background = GradientDrawable().apply {
                setColor(ui.surface); setStroke(ui.dp(2), ui.border); cornerRadius = ui.dp(16).toFloat()
            }
        }
        val layout = listOf(
            listOf("Screen off", "Home", "Apps"),
            listOf(null, "Up", null),
            listOf("Left", "Select", "Right"),
            listOf("Back", "Down", "Play/pause")
        )
        layout.forEach { actions ->
            val row = ui.column(true)
            actions.forEach { action ->
                val control = if (action == null) View(activity) else ui.button(action) {
                    remote.learn(action, onChanged)
                }.apply {
                    textSize = 13f
                    setPadding(ui.dp(3), ui.dp(4), ui.dp(3), ui.dp(4))
                    contentDescription = "Assign a remote button to $action"
                }
                row.addView(control, LinearLayout.LayoutParams(0, ui.dp(48), 1f).apply { setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2)) })
            }
            device.addView(row)
        }
        surround.addView(device, LinearLayout.LayoutParams(ui.dp(270), -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        // Respect narrow reader screens and split-window layouts without making the remote wider than its page.
        surround.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = minOf(ui.dp(270), right - left - surround.paddingLeft - surround.paddingRight)
            if (width > 0 && device.layoutParams.width != width) {
                device.layoutParams = device.layoutParams.apply { this.width = width }
            }
        }
        return surround
    }

    /** Changes or removes one saved physical key without disrupting the rest of the remote. */
    private fun edit(mapping: RemoteControls.Mapping, onChanged: () -> Unit) {
        AlertDialog.Builder(activity).setTitle("${mapping.keyLabel} · ${mapping.deviceName}")
            .setItems(arrayOf("Change action: ${mapping.action}", "Delete this assignment")) { _, choice ->
                if (choice == 0) chooseAction { action -> remote.reassign(mapping, action); onChanged() }
                else AlertDialog.Builder(activity).setTitle("Delete ${mapping.keyLabel} assignment?")
                    .setMessage("This key returns to its normal behavior. Other assignments and recorded actions are kept.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> remote.remove(mapping); onChanged() }.show()
            }.setNegativeButton("Done", null).show()
    }

    /** Offers all available launcher actions and current recordings from one consistent picker. */
    private fun chooseAction(onChosen: (String) -> Unit) {
        val actions = remote.actions
        AlertDialog.Builder(activity).setTitle("What should this button do?")
            .setItems(actions.toTypedArray()) { _, index -> onChosen(actions[index]) }
            .setNegativeButton("Cancel", null).show()
    }
}
