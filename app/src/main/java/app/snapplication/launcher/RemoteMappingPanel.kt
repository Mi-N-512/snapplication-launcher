package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout

/*
 * Builds MainActivity's remote setup using RemoteControls assignments and MacroEditor recordings. The generic
 * diagram represents actions, not an inferred photograph or layout of the connected remote.
 *
 * Related classes: MainActivity hosts the page; RemoteControls learns keys; MacroEditor and RemoteActionService
 * manage recordings.
 * UI: Generic remote diagram, live inverted key feedback, macro buttons, saved assignments and Return to Home.
 * Edit or extend: Edit remote shape, action placement and mapping instructions here. A future gamepad layout should
 * reuse the same mapping storage and page-scoped feedback.
 */
class RemoteMappingPanel(
    private val activity: Activity,
    private val ui: LauncherUi,
    private val remote: RemoteControls
) {
    private val controls = linkedMapOf<String, Button>()
    private var status: TextView? = null
    private var activeAction: String? = null
    private val resetHighlight = Runnable {
        activeAction = null
        controls.forEach { (action, button) -> decorate(button, action) }
    }
    private val observer: (KeyEvent) -> Unit = { event -> showKey(event) }

    // Creates a remote-friendly setup page with a diagram, saved keys and a record-then-assign flow.
    fun build(
        onAccessibility: () -> Unit,
        onRecordAction: () -> Unit,
        onManageActions: () -> Unit,
        onChanged: () -> Unit,
        onHome: () -> Unit
    ): View {
        val body = ui.column()
        body.addView(ui.label("Your remote, your shortcuts", 23f))
        body.addView(ui.label("Tap an action on this remote, then press the physical button to assign it. Press a mapped button here to see its action light up.", 16f))
        body.addView(ui.label("Android receives buttons first. Reserved keys, often Power and Home, may never reach the launcher. If a key is not detected, assign another button to that action.", 14f))
        if (RemoteActionService.instance == null) {
            body.addView(ui.button("Enable controls in other apps", onAccessibility))
        }
        body.addView(diagram(onChanged))
        body.addView(ui.label("Generic remote · choose an action to learn a physical button. Your remote may have a different layout.", 13f))
        status = ui.label("Press a remote button to check its assignment.", 14f)
        body.addView(status)
        body.addView(ui.section("Recorded actions"))
        body.addView(ui.label("1. Record the steps you want.\n2. Stop and save the recording.\n3. Assign it to a remote button.", 16f))
        body.addView(ui.button("Record an action", onRecordAction))
        val names = RemoteActionService.savedMacroNames(activity)
        if (names.isNotEmpty()) {
            body.addView(ui.button("Edit recordings and timing", onManageActions))
        }
        body.addView(ui.section("Saved buttons"))
        val mappings = remote.savedMappings()
        if (mappings.isEmpty()) {
            body.addView(ui.label("No custom assignments yet. Standard directional buttons still work.", 16f))
        } else {
            mappings.forEach { mapping ->
                body.addView(ui.button("${mapping.keyLabel} → ${RemoteControls.displayLabel(mapping.action)}\n${mapping.deviceName}") {
                    edit(mapping, onChanged)
                }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL })
            }
        }
        body.addView(ui.section("Screen on/off"))
        body.addView(ui.label("Assign Screen on/off to any button Android delivers. Some remotes send Power or Home directly to Android, so those physical keys cannot be learned here. Turning the screen off does not guarantee that HDMI disconnects; waking the phone depends on its hardware.", 15f))
        body.addView(ui.button("Test screen on/off") {
            RemoteActionService.instance?.performMappedAction("Screen off") ?: onAccessibility()
        })
        if (mappings.isNotEmpty()) body.addView(ui.button("Reset all assignments") {
            AlertDialog.Builder(activity).setTitle("Reset all remote assignments?")
                .setMessage("This removes saved button assignments from every remote. Recorded actions are kept.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reset") { _, _ -> remote.reset(); onChanged() }.show()
        })
        body.addView(ui.button("Return to Home", onHome))
        body.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { RemoteControls.keyObserver = observer }
            override fun onViewDetachedFromWindow(view: View) {
                if (RemoteControls.keyObserver === observer) RemoteControls.keyObserver = null
                view.removeCallbacks(resetHighlight)
                controls.values.forEach { it.removeCallbacks(resetHighlight) }
                activeAction = null
            }
        })
        return body
    }

    // Uses actual focusable buttons so every diagram action supports touch, keyboard and remote navigation.
    private fun diagram(onChanged: () -> Unit): View {
        val surround = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val device = ui.column().apply {
            setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(12))
            background = GradientDrawable().apply {
                setColor(ui.surface); setStroke(ui.dp(2), ui.border); cornerRadius = ui.dp(30).toFloat()
            }
        }
        device.addView(ui.label("SNAPPLICATION", 12f).apply { gravity = Gravity.CENTER })
        val layout = listOf(
            listOf("Screen off", "Home"),
            listOf("Apps", "Back"),
            listOf(null, "Up", null),
            listOf("Left", "Select", "Right"),
            listOf(null, "Down", null),
            listOf("Play/pause", "Stop action"),
            listOf("Record/stop macro")
        )
        layout.forEach { actions ->
            val row = ui.column(true)
            actions.forEach { action ->
                val control = if (action == null) View(activity) else actionButton(action, onChanged)
                row.addView(control, LinearLayout.LayoutParams(0, ui.dp(58), 1f).apply {
                    setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
                })
            }
            device.addView(row)
        }
        val macros = RemoteActionService.savedMacroNames(activity)
        if (macros.isNotEmpty()) {
            device.addView(ui.label("YOUR RECORDINGS", 12f).apply { gravity = Gravity.CENTER })
            macros.forEach { name ->
                device.addView(actionButton("Macro: $name", onChanged), LinearLayout.LayoutParams(-1, ui.dp(58)).apply {
                    setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
                })
            }
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

    // Builds a directly assignable action with a stable identity for live key feedback.
    private fun actionButton(action: String, onChanged: () -> Unit): Button {
        val symbol = when (action) {
            "Screen off" -> "⏻"; "Home" -> "⌂"; "Apps" -> "▦"; "Back" -> "↶"
            "Up" -> "▲"; "Down" -> "▼"; "Left" -> "◀"; "Right" -> "▶"
            "Select" -> "OK"; "Play/pause" -> "▶ / Ⅱ"; "Stop action" -> "■"
            "Record/stop macro" -> "●"; else -> "◆"
        }
        val label = RemoteControls.displayLabel(action)
        return ui.button(if (action in setOf("Up", "Down", "Left", "Right", "Select")) symbol else "$symbol  $label") {
            remote.learn(action, onChanged)
        }.apply {
            textSize = if (action in setOf("Up", "Down", "Left", "Right", "Select")) 20f else 12f
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
            contentDescription = "Assign a remote button to $label"
            controls[action] = this
            setOnFocusChangeListener { _, _ -> decorate(this, action) }
            decorate(this, action)
        }
    }

    // Inverts the reported action while retaining a visible keyboard/remote focus outline.
    private fun decorate(button: Button, action: String) {
        val highlighted = activeAction == action
        button.setTextColor(if (highlighted) ui.background else ui.text)
        button.background = GradientDrawable().apply {
            setColor(if (highlighted) ui.text else ui.surface)
            setStroke(ui.dp(if (button.hasFocus()) 3 else 1), if (button.hasFocus()) ui.accent else ui.border)
            cornerRadius = ui.dp(8).toFloat()
        }
    }

    // Shows only Android-delivered physical keys; this read-only feedback never executes an action.
    private fun showKey(event: KeyEvent) {
        if (event.device?.isVirtual != false) return
        val mapped = remote.action(event)
        val standard = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> "Up"; KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left"; KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "Select"
            KeyEvent.KEYCODE_BACK -> "Back"; KeyEvent.KEYCODE_HOME -> "Home"
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "Play/pause"; else -> null
        }
        val action = mapped ?: standard
        val key = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_").replace('_', ' ')
        status?.text = if (action == null) "$key received • not assigned. Tap an action to assign it."
            else "$key → ${RemoteControls.displayLabel(action)}${if (mapped == null) " (standard)" else ""}"
        controls.values.forEach { it.removeCallbacks(resetHighlight) }
        activeAction = action
        controls.forEach { (name, button) -> decorate(button, name) }
        // A brief release tail makes fast clicks visible; a held key is never animated repeatedly.
        controls.values.firstOrNull()?.postDelayed(resetHighlight, if (event.action == KeyEvent.ACTION_UP) 220 else 1200)
    }

    // Changes or removes one saved physical key without disrupting the rest of the remote.
    private fun edit(mapping: RemoteControls.Mapping, onChanged: () -> Unit) {
        AlertDialog.Builder(activity).setTitle("${mapping.keyLabel} · ${mapping.deviceName}")
            .setItems(arrayOf("Change action: ${RemoteControls.displayLabel(mapping.action)}", "Delete this assignment")) { _, choice ->
                if (choice == 0) chooseAction { action -> remote.reassign(mapping, action); onChanged() }
                else AlertDialog.Builder(activity).setTitle("Delete ${mapping.keyLabel} assignment?")
                    .setMessage("This key returns to its normal behavior. Other assignments and recorded actions are kept.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> remote.remove(mapping); onChanged() }.show()
            }.setNegativeButton("Done", null).show()
    }

    // Offers all available launcher actions and current recordings from one consistent picker.
    private fun chooseAction(onChosen: (String) -> Unit) {
        val actions = remote.actions
        AlertDialog.Builder(activity).setTitle("What should this button do?")
            .setItems(actions.map(RemoteControls::displayLabel).toTypedArray()) { _, index -> onChosen(actions[index]) }
            .setNegativeButton("Cancel", null).show()
    }
}
