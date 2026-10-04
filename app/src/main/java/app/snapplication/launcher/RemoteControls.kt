package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.view.KeyEvent
import android.widget.TextView
import org.json.JSONObject

/** Learns per-device remote buttons for MainActivity; keeps mappings separate from media and library state. */
class RemoteControls(private val context: Context) {
    private val prefs = context.getSharedPreferences("remoteControls", Context.MODE_PRIVATE)
    val actions: List<String> get() = listOf("Up", "Down", "Left", "Right", "Select", "Back", "Home", "Apps", "Screen off", "Play/pause", "Record/stop macro", "Stop action") +
        RemoteActionService.savedMacroNames(context).map { "Macro: $it" }
    private val held = mutableMapOf<Pair<Int, Int>, String?>()
    private val reserved = setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_UNKNOWN)

    companion object {
        /** Prevents the global accessibility filter from stealing the button being learned by the dialog. */
        var learning: Boolean = false
            private set
    }

    /** Reads the saved assignment for this input device and physical key without altering the event. */
    fun action(event: KeyEvent): String? {
        val descriptor = event.device?.descriptor ?: return null
        return mappings().optJSONObject(descriptor)?.optJSONObject(event.keyCode.toString())
            ?.optString("action")?.takeIf { it in actions }
    }

    /** Latches each press's assignment so entering a text field cannot change repeats or key-up. */
    fun assignment(event: KeyEvent, editing: Boolean): String? {
        val key = event.deviceId to event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val mapped = action(event)
            // Navigation and launcher escape keys must remain usable while a search field owns focus.
            held[key] = if (editing && mapped !in setOf("Up", "Down", "Left", "Right", "Select", "Home", "Back", "Apps", "Screen off", "Record/stop macro", "Stop action")) null else mapped
        }
        val releaseOnly = if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK && !event.isCanceled && !held.containsKey(key)) action(event) else null
        val assigned = held[key] ?: releaseOnly?.takeIf { !editing || it in setOf("Up", "Down", "Left", "Right", "Select", "Home", "Back", "Apps", "Screen off", "Record/stop macro", "Stop action") }
        if (event.action == KeyEvent.ACTION_UP) held.remove(key)
        return assigned
    }

    /** Drops incomplete presses when another window takes input ownership. */
    fun clearHeldButtons() { held.clear() }

    /** Keeps an intercepted key release consumed even when its down event switched foreground apps. */
    fun hasHeldAssignment(event: KeyEvent): Boolean = held[event.deviceId to event.keyCode] != null

    /** Converts navigation assignments to native events so focus, key holds and selection still work. */
    fun navigationEvent(event: KeyEvent, action: String): KeyEvent? {
        val code = when (action) {
            "Up" -> KeyEvent.KEYCODE_DPAD_UP
            "Down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "Left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "Right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "Select" -> KeyEvent.KEYCODE_DPAD_CENTER
            else -> return null
        }
        return KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount,
            event.metaState, event.deviceId, event.scanCode, event.flags, event.source)
    }

    /** Captures physical Back and volume keys too; the visible Cancel button exits without saving. */
    fun learn(action: String, onSaved: () -> Unit) {
        require(action in actions)
        val activity = context as? Activity ?: return
        val status = TextView(activity).apply {
            text = "Press and release the remote button for $action. Use Cancel below to exit, or wait 30 seconds for this dialog to close without saving. Home and Power are controlled by Android."
            setPadding(32, 24, 32, 24)
        }
        var captured: KeyEvent? = null
        val dialog = AlertDialog.Builder(activity).setTitle("Map $action").setView(status)
            .setNegativeButton("Cancel", null).create()
        // Back belongs to the learning session, not Android's default dialog-dismiss behavior.
        dialog.setCancelable(false)
        dialog.setOnKeyListener { _, code, event ->
            if (code in reserved) return@setOnKeyListener false
            val device = event.device
            if (device == null || device.isVirtual || device.descriptor.isBlank()) {
                status.text = "This input cannot be identified. Try a connected remote."
                return@setOnKeyListener true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && captured == null) {
                captured = KeyEvent(event)
                status.text = "${device.name}: ${KeyEvent.keyCodeToString(code)}. Release to save."
            }
            // Some remotes deliver only a Back release. Accept it when Android identifies the device.
            val press = captured ?: event.takeIf { it.action == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_BACK && !it.isCanceled }
            if (event.action == KeyEvent.ACTION_UP && press?.keyCode == code && press.deviceId == event.deviceId) {
                if (!event.isCanceled) {
                    save(device.descriptor, device.name, code, action)
                    dialog.dismiss()
                    onSaved()
                } else {
                    captured = null
                    status.text = "Press cancelled. Try again."
                }
            }
            true
        }
        dialog.setCanceledOnTouchOutside(false)
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            if (dialog.isShowing) {
                dialog.dismiss()
                Toast.makeText(activity, "No button saved. Learning timed out after 30 seconds; choose Map to try again.", Toast.LENGTH_LONG).show()
            }
        }
        dialog.setOnDismissListener { learning = false; timeoutHandler.removeCallbacks(timeout) }
        learning = true
        dialog.show()
        timeoutHandler.postDelayed(timeout, 30_000)
    }

    /** Describes real saved assignments for the settings screen without claiming remote pairing. */
    fun summary(): String {
        val root = mappings()
        val lines = mutableListOf<String>()
        root.keys().forEach { descriptor ->
            val device = root.optJSONObject(descriptor) ?: return@forEach
            device.keys().forEach { key ->
                val entry = device.optJSONObject(key) ?: return@forEach
                lines.add("${entry.optString("name")} · ${KeyEvent.keyCodeToString(key.toIntOrNull() ?: 0)} → ${entry.optString("action")}")
            }
        }
        return lines.sorted().joinToString("\n").ifEmpty { "No custom buttons. Standard remote navigation is available." }
    }

    /** Restores normal keys after explicit confirmation in MainActivity's remote settings. */
    fun reset() { prefs.edit().remove("mappings").apply() }

    /** Restores valid JSON or recovers safely from unreadable preferences. */
    private fun mappings(): JSONObject = runCatching {
        JSONObject(prefs.getString("mappings", "{}")!!)
    }.getOrElse { JSONObject() }

    /** Replaces the chosen action's old button on this device and any prior action on the new button. */
    private fun save(descriptor: String, name: String, key: Int, action: String) {
        val root = mappings()
        val device = root.optJSONObject(descriptor) ?: JSONObject()
        val replaced = device.keys().asSequence().filter { device.optJSONObject(it)?.optString("action") == action }.toList()
        replaced.forEach { device.remove(it) }
        device.put(key.toString(), JSONObject().put("action", action).put("name", name))
        root.put(descriptor, device)
        prefs.edit().putString("mappings", root.toString()).apply()
    }
}
