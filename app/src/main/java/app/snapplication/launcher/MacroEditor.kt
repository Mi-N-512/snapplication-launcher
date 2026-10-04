package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject

/** Edits local action timing and steps for MainActivity; RemoteActionService reads the saved options at replay. */
class MacroEditor(private val activity: Activity, private val onChanged: () -> Unit) {
    private val prefs = activity.getSharedPreferences("remoteMacros", Context.MODE_PRIVATE)

    /** Reads recordings defensively without losing unrelated actions when a dialog opens. */
    private fun all(): JSONObject = runCatching { JSONObject(prefs.getString("macros", "{}")!!) }.getOrElse { JSONObject() }

    /** Saves one edit after stopping any running routine that may use the old steps. */
    private fun save(name: String, macro: JSONObject) {
        RemoteActionService.instance?.performMappedAction("Stop action")
        prefs.edit().putString("macros", all().put(name, macro).toString()).apply()
        onChanged()
    }

    /** Presents explicit speed, start behavior, step editing, rename and deletion controls. */
    fun show(name: String) {
        val macro = all().optJSONObject(name) ?: return
        val choices = arrayOf("Speed: ${macro.optDouble("speed", 1.0)}×", "Start: ${if (macro.optBoolean("currentScreen")) "current screen" else "open recorded app"}",
            "Edit steps (${macro.optJSONArray("steps")?.length() ?: 0})", "Rename", "Delete action")
        AlertDialog.Builder(activity).setTitle(name).setItems(choices) { _, index ->
            when (index) {
                0 -> AlertDialog.Builder(activity).setTitle("Playback speed").setItems(arrayOf("0.5× — slower", "1× — recorded timing", "2× — faster", "4× — fastest")) { _, choice ->
                    save(name, macro.put("speed", listOf(.5, 1.0, 2.0, 4.0)[choice])); show(name)
                }.setNegativeButton("Cancel", null).show()
                1 -> AlertDialog.Builder(activity).setTitle("Where should this action start?")
                    .setItems(arrayOf("Open recorded app", "Run on current screen (skip initial app launches)")) { _, choice ->
                        save(name, macro.put("currentScreen", choice == 1)); show(name)
                    }.setNegativeButton("Cancel", null).show()
                2 -> steps(name)
                3 -> rename(name)
                4 -> AlertDialog.Builder(activity).setTitle("Delete $name?").setMessage("Its remote assignment will no longer run an action.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        RemoteActionService.instance?.performMappedAction("Stop action")
                        val root = all(); root.remove(name); prefs.edit().putString("macros", root.toString()).apply(); onChanged()
                    }.show()
            }
        }.setNegativeButton("Done", null).show()
    }

    /** Names a recorded step using its visible label when available. */
    private fun title(step: JSONObject): String = when (step.optString("kind", "click")) {
        "home" -> "Go Home"
        "back" -> "Go Back"
        "launch" -> "Open " + runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(step.optString("package"), 0)).toString() }.getOrDefault("app")
        else -> "Tap " + step.optString("label").ifBlank { step.optString("id").substringAfterLast('/').replace('_', ' ').ifBlank { "button" } }
    }

    /** Lists individual delays so the user can inspect and shorten an existing routine. */
    private fun steps(name: String) {
        val macro = all().optJSONObject(name) ?: return
        val steps = macro.optJSONArray("steps") ?: return
        val labels = (0 until steps.length()).map { index ->
            val step = steps.getJSONObject(index)
            "${index + 1}. ${title(step)} · wait ${step.optLong("delay", 350)} ms"
        }
        AlertDialog.Builder(activity).setTitle("Steps · $name").setItems(labels.toTypedArray()) { _, index -> editStep(name, index) }
            .setNegativeButton("Back") { _, _ -> show(name) }.show()
    }

    /** Changes timing or order and permits removal without re-recording the whole macro. */
    private fun editStep(name: String, index: Int) {
        val macro = all().optJSONObject(name) ?: return
        val sequence = macro.optJSONArray("steps") ?: return
        val step = sequence.optJSONObject(index) ?: return
        val options = mutableListOf("Change wait time")
        if (index > 0) options.add("Move earlier")
        if (index < sequence.length() - 1) options.add("Move later")
        options.add("Remove step")
        AlertDialog.Builder(activity).setTitle(title(step)).setItems(options.toTypedArray()) { _, chosen ->
            if (chosen == 0) {
                val input = EditText(activity).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(step.optLong("delay", 350).toString()); selectAll() }
                AlertDialog.Builder(activity).setTitle("Wait before this step (0–10000 ms)").setView(input)
                    .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                        input.text.toString().toLongOrNull()?.let { step.put("delay", it.coerceIn(0, 10000)); save(name, macro) }
                        steps(name)
                    }.show()
            } else {
                val list = (0 until sequence.length()).map { sequence.getJSONObject(it) }.toMutableList()
                when (options[chosen]) {
                    "Move earlier" -> { list.removeAt(index); list.add(index - 1, step) }
                    "Move later" -> { list.removeAt(index); list.add(index + 1, step) }
                    else -> list.removeAt(index)
                }
                save(name, macro.put("steps", JSONArray(list))); steps(name)
            }
        }.setNegativeButton("Back") { _, _ -> steps(name) }.show()
    }

    /** Renames a routine and updates existing remote assignments atomically within each preference file. */
    private fun rename(old: String) {
        val input = EditText(activity).apply { setSingleLine(); setText(old); selectAll() }
        val dialog = AlertDialog.Builder(activity).setTitle("Action name").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim(); val root = all()
                if (name.isBlank() || name.length > 40 || (name != old && root.has(name))) { input.error = "Use a unique name, 1–40 characters"; return@setOnClickListener }
                if (name != old) {
                    val macro = root.optJSONObject(old) ?: return@setOnClickListener
                    RemoteActionService.instance?.performMappedAction("Stop action")
                    root.remove(old); root.put(name, macro); prefs.edit().putString("macros", root.toString()).apply()
                    val remote = activity.getSharedPreferences("remoteControls", Context.MODE_PRIVATE)
                    val mappings = runCatching { JSONObject(remote.getString("mappings", "{}")!!) }.getOrElse { JSONObject() }
                    mappings.keys().forEach { device -> mappings.optJSONObject(device)?.let { keys -> keys.keys().forEach { key ->
                        keys.optJSONObject(key)?.let { entry -> if (entry.optString("action") == "Macro: $old") entry.put("action", "Macro: $name") }
                    } } }
                    remote.edit().putString("mappings", mappings.toString()).apply()
                }
                dialog.dismiss(); onChanged(); show(name)
            }
        }
        dialog.show()
    }
}
