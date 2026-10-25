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

/*
Learns per-device remote buttons for MainActivity; keeps mappings separate from media and library
state.

This class is a helper and does not inherit from another launcher class.

prefs holds saved remote assignments. held remembers the action chosen when a button was pressed so
its release matches the same action. learningDialog and learningOwner track the current mapping
session. learn waits for a physical button press and saves its action; assignment looks it up during
normal use. MainActivity and RemoteActionService each use this helper. RemoteMappingPanel displays
its saved mappings.

Used by or used with: MainActivity and RemoteActionService handle events; RemoteMappingPanel
displays assignments. Screen controls: Key-learning dialog and feedback; stores per-device mappings
and temporary held-key state. Changes belong here: Add stable action identifiers here with matching
handlers in MainActivity/RemoteActionService. Android-reserved keys cannot be made deliverable here.
*/
class RemoteControls(private val context: Context) {
    /*
    One saved physical-key assignment shared by RemoteControls and RemoteMappingPanel. Descriptor
    and keyCode identify the button; deviceName and action label its row. No UI of its own. Preserve
    these identifiers when changing labels or adding actions.
    */
    data class Mapping(val descriptor: String, val keyCode: Int, val deviceName: String, val action: String) {
        // Formats the Android-delivered key independently of the user's assigned action.
        val keyLabel: String get() = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
    }
    private val prefs = context.getSharedPreferences("remoteControls", Context.MODE_PRIVATE)
    val actions: List<String> get() = listOf("Up", "Down", "Left", "Right", "Select", "Back", "Home", "Apps", "Screen off", "Play/pause", "Record/stop macro", "Stop action") +
        RemoteActionService.savedMacroNames(context).map { "Macro: $it" }
    private val held = mutableMapOf<Pair<Int, Int>, String?>()
    private val reserved = setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_UNKNOWN)
    private var learningDialog: AlertDialog? = null
    private var cancelLearningTimeout: (() -> Unit)? = null
    private var removeLearningObserver: (() -> Unit)? = null
    var learningAction: String? = null
        private set

    companion object {
        private var learningOwner: RemoteControls? = null
        private var launcherWindow = java.lang.ref.WeakReference<android.view.View>(null)

        // Actual window focus, unlike a cached accessibility package, changes when a vendor dialog
        // covers Home.
        fun trackLauncherWindow(view: android.view.View) { launcherWindow = java.lang.ref.WeakReference(view) }
        fun launcherHasFocus(): Boolean = launcherWindow.get()?.hasWindowFocus() == true

        // Prevents the global accessibility filter from stealing the button being learned by the
        // dialog.
        // Exists only while RemoteMappingPanel is attached; callers may report keys without
        // changing them.
        var keyObserver: ((KeyEvent) -> Unit)? = null

        // Keeps the stored Screen off identifier compatible with existing remote assignments.
        fun displayLabel(action: String): String = if (action == "Screen off") "Screen on/off" else action.removePrefix("Macro: ")

        // Reports a physical key to the visible remote diagram without consuming or remapping it.
        fun observeKey(event: KeyEvent) { if (!learning) keyObserver?.invoke(event) }

        var learning: Boolean = false
            private set
    }

    // Reads the saved assignment for this input device and physical key without altering the event.
    fun action(event: KeyEvent): String? {
        val current = event.device ?: return null
        val root = mappings()
        // Normal key presses keep the exact descriptor's fast path and respect intentionally
        // unassigned keys.
        if (root.has(current.descriptor)) return root.optJSONObject(current.descriptor)
            ?.optJSONObject(event.keyCode.toString())?.optString("action")?.takeIf { it in actions }
        fun profile(device: android.view.InputDevice) = RemoteDeviceMatch.Profile(device.descriptor, device.name, device.vendorId, device.productId)
        val saved = root.keys().asSequence().mapNotNull { descriptor ->
            val entries = root.optJSONObject(descriptor) ?: return@mapNotNull null
            val entry = entries.keys().asSequence().mapNotNull { entries.optJSONObject(it) }.firstOrNull() ?: return@mapNotNull null
            RemoteDeviceMatch.Profile(descriptor, entry.optString("name"), entry.optInt("vendor", -1), entry.optInt("product", -1))
        }.toList()
        val connected = android.view.InputDevice.getDeviceIds().asIterable().mapNotNull { android.view.InputDevice.getDevice(it) }.map(::profile)
        val descriptor = RemoteDeviceMatch.resolve(profile(current), saved, connected) ?: return null
        return root.optJSONObject(descriptor)?.optJSONObject(event.keyCode.toString())
            ?.optString("action")?.takeIf { it in actions }
    }

    // Latches each press's assignment so entering a text field cannot change repeats or key-up.
    fun assignment(event: KeyEvent, isEditing: () -> Boolean): String? {
        val key = event.deviceId to event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val mapped = action(event)
            // Navigation and launcher escape keys must remain usable while a search field owns
            // focus.
            held[key] = RemoteInputPolicy.permitted(mapped, isEditing)
        }
        val releaseOnly = if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK && !event.isCanceled && !held.containsKey(key)) action(event) else null
        val assigned = held[key] ?: RemoteInputPolicy.permitted(releaseOnly, isEditing)
        if (event.action == KeyEvent.ACTION_UP) held.remove(key)
        return assigned
    }

    // Drops incomplete presses when another window takes input ownership.
    fun clearHeldButtons() { held.clear() }

    // Keeps an intercepted key release consumed even when its down event switched foreground apps.
    fun hasHeldAssignment(event: KeyEvent): Boolean = held[event.deviceId to event.keyCode] != null

    // Converts navigation assignments to native events so focus, key holds and selection still
    // work.
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

    // Captures physical Back and volume keys too; the visible Cancel button exits without saving.
    fun learn(action: String, onSaved: () -> Unit) {
        require(action in actions)
        val activity = context as? Activity ?: return
        learningOwner?.closeLearning()
        closeLearning()
        if (activity.isFinishing || activity.isDestroyed) return
        val status = TextView(activity).apply {
            text = "Press and release the remote button for ${displayLabel(action)}.\n\nAny existing assignment on that button will be replaced. Use Cancel to exit. Android receives remote keys first. It may reserve Home, Power or other keys so the launcher never receives them. If nothing appears, try a different physical button."
            setPadding(32, 24, 32, 24)
        }
        var captured: KeyEvent? = null
        val dialog = AlertDialog.Builder(activity).setTitle("Map ${displayLabel(action)}").setView(status)
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
            // Some remotes deliver only a Back release. Accept it when Android identifies the
            // device.
            val press = captured ?: event.takeIf { it.action == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_BACK && !it.isCanceled }
            if (event.action == KeyEvent.ACTION_UP && press?.keyCode == code && press.deviceId == event.deviceId) {
                if (!event.isCanceled) {
                    save(device.descriptor, device.name, code, action)
                    closeLearning()
                    if (!activity.isFinishing && !activity.isDestroyed) onSaved()
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
            if (learningDialog === dialog) {
                val visible = dialog.window?.decorView?.isAttachedToWindow == true
                closeLearning()
                if (visible && !activity.isFinishing && !activity.isDestroyed)
                    Toast.makeText(activity, "No button received. Android may reserve this key. Tap the action and try another remote button.", Toast.LENGTH_LONG).show()
            }
        }
        dialog.setOnDismissListener { if (learningDialog === dialog) releaseLearning() }
        learningDialog = dialog
        learningOwner = this
        learningAction = action
        learning = true
        cancelLearningTimeout = { timeoutHandler.removeCallbacks(timeout) }
        val decor = dialog.window!!.decorView
        val observer = object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: android.view.View) {}
            // Android can remove a dialog during display recreation without calling its dismiss
            // listener.
            override fun onViewDetachedFromWindow(view: android.view.View) {
                if (learningDialog === dialog) releaseLearning()
            }
        }
        decor.addOnAttachStateChangeListener(observer)
        removeLearningObserver = { decor.removeOnAttachStateChangeListener(observer) }
        dialog.show()
        timeoutHandler.postDelayed(timeout, 30_000)
    }

    // Cancels the callback first, including dialogs created by temporary editor-owned
    // RemoteControls instances.
    private fun releaseLearning() {
        cancelLearningTimeout?.invoke(); cancelLearningTimeout = null
        removeLearningObserver?.invoke(); removeLearningObserver = null
        learningDialog = null; learningAction = null
        if (learningOwner === this) { learningOwner = null; learning = false }
        clearHeldButtons()
    }

    // The window manager may already have removed the decor during an external-display change.
    fun closeLearning() {
        val dialog = learningDialog
        releaseLearning()
        if (dialog != null) {
            try { dialog.dismiss() } catch (_: IllegalArgumentException) {
                // Android already removed the window; pending work is already cancelled.
            }
        }
    }

    // Describes real saved assignments for the settings screen without claiming remote pairing.
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

    // Restores normal keys after explicit confirmation in MainActivity's remote settings.
    fun reset() { prefs.edit().remove("mappings").apply(); clearHeldButtons() }

    // Lists every persisted key, including assignments to actions that have since been deleted.
    fun savedMappings(): List<Mapping> {
        val root = mappings()
        val result = mutableListOf<Mapping>()
        root.keys().forEach { descriptor ->
            val device = root.optJSONObject(descriptor) ?: return@forEach
            for (key in device.keys()) {
                val code = key.toIntOrNull() ?: continue
                val entry = device.optJSONObject(key) ?: continue
                result.add(Mapping(descriptor, code, entry.optString("name", "Remote"), entry.optString("action")))
            }
        }
        return result.sortedWith(compareBy({ it.deviceName }, { it.keyCode }))
    }

    // Deletes only this physical button's assignment, preserving all other devices and buttons.
    fun remove(mapping: Mapping) {
        val root = mappings()
        val device = root.optJSONObject(mapping.descriptor) ?: return
        device.remove(mapping.keyCode.toString())
        if (device.length() == 0) root.remove(mapping.descriptor)
        prefs.edit().putString("mappings", root.toString()).apply()
        clearHeldButtons()
    }

    // Changes an already identified key without asking the user to learn that key again.
    fun reassign(mapping: Mapping, action: String) {
        require(action in actions)
        save(mapping.descriptor, mapping.deviceName, mapping.keyCode, action)
        clearHeldButtons()
    }

    // Restores valid JSON or recovers safely from unreadable preferences.
    private fun mappings(): JSONObject = runCatching {
        JSONObject(prefs.getString("mappings", "{}")!!)
    }.getOrElse { JSONObject() }

    // Replaces the chosen action's old button on this device and any prior action on the new
    // button.
    private fun save(descriptor: String, name: String, key: Int, action: String) {
        val root = mappings()
        val device = root.optJSONObject(descriptor) ?: JSONObject()
        val previous = device.optJSONObject(key.toString())
        val replaced = device.keys().asSequence().filter { device.optJSONObject(it)?.optString("action") == action }.toList()
        replaced.forEach { device.remove(it) }
        val hardware = android.view.InputDevice.getDeviceIds().asIterable().mapNotNull { android.view.InputDevice.getDevice(it) }
            .firstOrNull { it.descriptor == descriptor }
        device.put(key.toString(), JSONObject().put("action", action).put("name", name)
            .put("vendor", hardware?.vendorId ?: previous?.optInt("vendor", -1) ?: -1)
            .put("product", hardware?.productId ?: previous?.optInt("product", -1) ?: -1))
        root.put(descriptor, device)
        prefs.edit().putString("mappings", root.toString()).apply()
        clearHeldButtons()
    }
}
