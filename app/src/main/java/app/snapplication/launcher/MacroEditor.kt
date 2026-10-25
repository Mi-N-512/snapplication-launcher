package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject

/*
Edits local action timing and steps for MainActivity; RemoteActionService reads the saved options at
replay.

This class is a helper and does not inherit from another launcher class.

prefs stores the saved macro JSON, editor holds the open dialog, and selectedIndex identifies the
step being edited. show builds the preview and editing controls. Changes are checked before save
writes them back and onChanged refreshes the calling page. MainActivity opens the editor,
MacroPreviewView draws the rehearsal, and RemoteActionService performs real playback.

Used by or used with: MainActivity opens it; RemoteActionService reads saved macros;
MacroPreviewView rehearses timing. Screen controls: Full-screen preview workspace with timing,
reference screenshot, step editing and remote controls. Changes belong here: Add editable macro
options here together with validation and corresponding replay support in RemoteActionService.
*/
class MacroEditor(private val activity: Activity, private val onChanged: () -> Unit) {
    private val prefs = activity.getSharedPreferences("remoteMacros", Context.MODE_PRIVATE)

    // Reads recordings defensively without losing unrelated actions when a dialog opens.
    private fun all(): JSONObject = runCatching { JSONObject(prefs.getString("macros", "{}")!!) }.getOrElse { JSONObject() }

    // Saves one edit after stopping any running routine that may use the old steps.
    private fun save(name: String, macro: JSONObject) {
        RemoteActionService.instance?.performMappedAction("Stop action")
        prefs.edit().putString("macros", all().put(name, macro).toString()).apply()
        onChanged()
    }

    private var editor: android.app.Dialog? = null
    private var selectedIndex = 0

    // Keeps timing, reference image, editable steps and rehearsal in one full-screen workspace.
    fun show(name: String) {
        val macro = all().optJSONObject(name) ?: return
        if (macro.optString("type") == ProjectionImageAction.KIND) {
            ProjectionImageAction.show(activity, name, macro) { confirmDelete(name) }
            return
        }
        editor?.dismiss()
        val state = LauncherState(activity)
        val ui = LauncherUi(activity, state, false)
        val dialog = android.app.Dialog(activity)
        editor = dialog
        val shell = ui.column().apply { setBackgroundColor(ui.background) }
        val header = ui.column(true)
        header.addView(ui.button("‹") { dialog.dismiss() }.apply {
            contentDescription = "Close macro editor"; ui.flatFocus(this, ui.surface, outline = false)
        }, android.widget.LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
        header.addView(ui.label(name, 20f), android.widget.LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        shell.addView(header)
        val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val workspace = ui.column(landscape)
        shell.addView(workspace, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        val stage = ui.column()
        val status = ui.label("", 16f)
        val preview = MacroPreviewView.create(activity, macro, ::title) { status.text = it }
        stage.addView(preview, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        stage.addView(status)
        workspace.addView(stage, if (landscape) android.widget.LinearLayout.LayoutParams(0, -1, 2f)
            else android.widget.LinearLayout.LayoutParams(-1, 0, 1.25f))
        val tools = ui.column()
        workspace.addView(android.widget.ScrollView(activity).apply { addView(tools) },
            if (landscape) android.widget.LinearLayout.LayoutParams(0, -1, 1f) else android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        tools.addView(ui.label("PREVIEW & EDIT", 16f))
        tools.addView(ui.label("Edits save automatically. Preview shows timing without pressing anything. Live test performs the saved steps.", 14f))
        tools.addView(ui.button("Show taps only · no presses") { preview.restart() })
        tools.addView(ui.button("Stop preview") { preview.stop() })
        tools.addView(ui.button("Live test…") {
            preview.stop()
            AlertDialog.Builder(activity).setTitle("Run this action on the phone?")
                .setMessage("This performs real actions using the selected run mode. Current-screen mode repeats positions on the visible page; matching mode checks the recorded app. Use the floating Stop action button to cancel.")
                .setNegativeButton("Cancel", null).setPositiveButton("Run") { _, _ ->
                    val service = RemoteActionService.instance
                    if (service == null) android.widget.Toast.makeText(activity, "Enable Snapplication accessibility support first.", android.widget.Toast.LENGTH_LONG).show()
                    else { dialog.dismiss(); service.openForReplay(name) }
                }.show()
        })
        tools.addView(ui.button("Choose reference screenshot") {
            prefs.edit().putString("overlayForMacro", name).apply()
            dialog.dismiss()
            activity.startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"; addCategory(android.content.Intent.CATEGORY_OPENABLE)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, 203)
        })
        if (macro.optString("overlayUri").isNotBlank()) tools.addView(ui.button("Remove reference screenshot") {
            save(name, macro.apply { remove("overlayUri") }); show(name)
        })
        tools.addView(ui.button("Run mode: ${if (macro.optBoolean("coordinateReplay")) "run on current screen" else "match recorded screens"}") {
            AlertDialog.Builder(activity).setTitle("How should taps and swipes run?")
                .setItems(arrayOf("Match recorded screens", "Run on current screen · repeat touch positions")) { _, choice ->
                    if (choice == 0) { save(name, macro.put("coordinateReplay", false).put("currentScreen", false)); show(name) }
                    else AlertDialog.Builder(activity).setTitle("Run on whichever page is visible?")
                        .setMessage("Saved taps and swipes run once at their relative screen positions, even in a different app. They can press the wrong item. Initial app launches are skipped; later navigation steps still run. Text and named controls still require their recorded app. Image actions still require an image match. Use Stop action to cancel.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Use coordinates") { _, _ ->
                            save(name, macro.put("coordinateReplay", true).put("currentScreen", true)); show(name)
                        }.show()
                }.setNegativeButton("Cancel", null).show()
        })
        tools.addView(ui.button("Speed: ${macro.optDouble("speed", 1.0)}×") {
            AlertDialog.Builder(activity).setTitle("Playback speed").setItems(arrayOf("0.5× — slower", "1× — recorded timing", "2× — faster", "4× — fastest")) { _, choice ->
                save(name, macro.put("speed", listOf(.5, 1.0, 2.0, 4.0)[choice])); show(name)
            }.setNegativeButton("Cancel", null).show()
        })
        if (!macro.optBoolean("coordinateReplay")) tools.addView(ui.button("Start: ${if (RemoteActionService.startsOnCurrentScreen(macro)) "current screen" else "open recorded app"}") {
            AlertDialog.Builder(activity).setTitle("Where should this action start?")
                .setItems(arrayOf("Open recorded app", "Current screen · skip initial app launches")) { _, choice ->
                    save(name, macro.put("currentScreen", choice == 1)); show(name)
                }.setNegativeButton("Cancel", null).show()
        })
        val sequence = macro.optJSONArray("steps") ?: JSONArray()
        selectedIndex = selectedIndex.coerceIn(0, (sequence.length() - 1).coerceAtLeast(0))
        val selected = sequence.optJSONObject(selectedIndex)
        if (selected != null) {
            preview.select(selected)
            tools.addView(ui.label("Selected: ${selectedIndex + 1}. ${title(selected)}", 16f))
            tools.addView(ui.button("Timing, order & remove step") { preview.stop(); editStep(name, selectedIndex) })
            val points = selected.optJSONArray("points")
            val stationary = points != null && GestureShape.isTap((0 until points.length()).map { i ->
                points.getJSONArray(i).optDouble(0) to points.getJSONArray(i).optDouble(1)
            })
            if (selected.optString("kind") in setOf("gesture", ImageTapAction.KIND) && stationary) {
                tools.addView(ui.button("Set tap coordinates") { editCoordinates(name, macro, selected) })
                tools.addView(android.widget.CheckBox(activity).apply {
                    text = "Tap preview to move this marker"; setTextColor(ui.text)
                    setOnCheckedChangeListener { _, checked ->
                        preview.onPosition = if (!checked) null else { x, y ->
                            moveTap(selected, x, y); save(name, macro); show(name)
                        }
                    }
                })
            }
        }
        tools.addView(ui.label("STEPS · ${sequence.length()}", 16f))
        if (sequence.length() == 0) tools.addView(ui.label("No steps remain. Record a new action to add touches.", 14f))
        for (i in 0 until sequence.length()) {
            val step = sequence.getJSONObject(i)
            tools.addView(ui.button("${i + 1}. ${title(step)} · ${step.optLong("delay", 350)} ms") {
                selectedIndex = i; show(name)
            }.apply { isSelected = i == selectedIndex })
        }
        tools.addView(ui.button("Assign remote button") {
            preview.stop()
            RemoteControls(activity).learn("Macro: $name") { show(name) }
        })
        tools.addView(ui.button("Rename") { preview.stop(); rename(name) })
        tools.addView(ui.button("Delete action") { preview.stop(); confirmDelete(name) })
        LauncherFont.apply(shell)
        dialog.setContentView(shell)
        dialog.setOnDismissListener { preview.stop() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ui.background))
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            val flags = if (state.fullscreen) android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0
            if (state.fullscreen) addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = flags
            // Android's file picker may reveal system bars; restore the editor's layout when focus
            // returns.
            decorView.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
                if (focused) decorView.systemUiVisibility = flags
            }
        }
    }

    // Moves an existing recorded tap only; its original app/window guards remain required during
    // replay.
    private fun moveTap(step: JSONObject, x: Double, y: Double) {
        val points = step.optJSONArray("points") ?: return
        if (step.optString("kind") == ImageTapAction.KIND) step.put("positionConfirmed", true)
        for (i in 0 until points.length()) points.getJSONArray(i).put(0, x).put(1, y)
    }

    // Numeric positioning gives remote and screen-reader users the same editing ability as touch
    // users.
    private fun editCoordinates(name: String, macro: JSONObject, step: JSONObject) {
        val point = step.getJSONArray("points").getJSONArray(0)
        val form = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        fun field(label: String, value: Double) = EditText(activity).apply {
            hint = label; contentDescription = label
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(java.util.Locale.US, "%.2f", value * 100)); form.addView(this)
        }
        val x = field("Horizontal position, 0–100 percent", point.optDouble(0))
        val y = field("Vertical position, 0–100 percent", point.optDouble(1))
        val dialog = AlertDialog.Builder(activity).setTitle("Tap position · percent of recorded screen").setView(form)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val nx = x.text.toString().toDoubleOrNull(); val ny = y.text.toString().toDoubleOrNull()
            if (nx == null || !nx.isFinite() || nx !in 0.0..100.0) { x.error = "Enter 0–100"; return@setOnClickListener }
            if (ny == null || !ny.isFinite() || ny !in 0.0..100.0) { y.error = "Enter 0–100"; return@setOnClickListener }
            moveTap(step, nx / 100, ny / 100); save(name, macro); dialog.dismiss(); show(name)
        } }
        dialog.show()
    }

    companion object {
        // Saves only the explicitly selected image URI and reopens the editor after Android's
        // picker.
        fun handleOverlayResult(activity: Activity, resultCode: Int, data: android.content.Intent?) {
            val prefs = activity.getSharedPreferences("remoteMacros", Context.MODE_PRIVATE)
            val name = prefs.getString("overlayForMacro", null) ?: return
            prefs.edit().remove("overlayForMacro").apply()
            val editor = MacroEditor(activity) {}
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val uri = data.data!!
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val macro = editor.all().optJSONObject(name) ?: return@runCatching
                    editor.save(name, macro.put("overlayUri", uri.toString()))
                }.onFailure { android.widget.Toast.makeText(activity, "Screenshot could not be kept. Please choose another image.", android.widget.Toast.LENGTH_LONG).show() }
            }
            editor.show(name)
        }
    }

    // Shares explicit confirmation across the list and editor; deleting saved data needs no
    // accessibility grant.
    fun confirmDelete(name: String) {
        AlertDialog.Builder(activity).setTitle("Delete $name?")
            .setMessage("Its remote assignment will no longer run an action.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                val service = RemoteActionService.instance
                if (service != null) service.deleteMacro(name)
                else {
                    val root = all(); root.remove(name)
                    prefs.edit().putString("macros", root.toString()).apply()
                }
                if (prefs.getString("pendingAssignment", null) == name) prefs.edit().remove("pendingAssignment").apply()
                editor?.dismiss(); onChanged()
            }.show()
    }

    // Names a recorded step using its visible label when available.
    private fun title(step: JSONObject): String = when (step.optString("kind", "click")) {
        "gesture" -> {
            val points = step.optJSONArray("points") ?: JSONArray()
            if (GestureShape.isTap((0 until points.length()).map { i ->
                points.getJSONArray(i).optDouble(0) to points.getJSONArray(i).optDouble(1)
            })) "Tap on screen" else "Swipe on screen"
        }
        ImageTapAction.KIND -> if (step.optBoolean("positionConfirmed")) "Image-matched tap" else "Place tap on image first"
        "text" -> "Enter text: " + step.optString("value").take(60)
        "home" -> "Go Home"
        "back" -> "Go Back"
        "scroll" -> if (step.optBoolean("forward", true)) "Scroll forward" else "Scroll back"
        "launch" -> "Open " + runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(step.optString("package"), 0)).toString() }.getOrDefault("app")
        else -> "Tap " + step.optString("label").ifBlank { step.optString("id").substringAfterLast('/').replace('_', ' ').ifBlank { "button" } }
    }

    // Returns step edits to the same full-screen workspace.
    private fun steps(name: String) { show(name) }

    // Changes timing or order and permits removal without re-recording the whole macro.
    private fun editStep(name: String, index: Int) {
        val macro = all().optJSONObject(name) ?: return
        val sequence = macro.optJSONArray("steps") ?: return
        val step = sequence.optJSONObject(index) ?: return
        val options = mutableListOf("Change wait time")
        if (step.optString("kind") == "text") options.add("Edit text")
        if (index > 0) options.add("Move earlier")
        if (index < sequence.length() - 1) options.add("Move later")
        options.add("Remove step")
        AlertDialog.Builder(activity).setTitle(title(step)).setItems(options.toTypedArray()) { _, chosen ->
            if (chosen == 0) {
                val input = EditText(activity).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(step.optLong("delay", 350).toString()); selectAll() }
                val dialog = AlertDialog.Builder(activity).setTitle("Wait before this step (0–10000 ms)").setView(input)
                    .setNegativeButton("Cancel") { _, _ -> steps(name) }.setPositiveButton("Save", null).create()
                // Keep invalid input available to correct; never silently clamp or discard the
                // user's timing.
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val delay = input.text.toString().trim().toLongOrNull()
                        if (delay == null || delay !in 0L..10000L) {
                            input.error = "Enter a whole number from 0 to 10000 milliseconds"
                            return@setOnClickListener
                        }
                        step.put("delay", delay); save(name, macro)
                        dialog.dismiss(); steps(name)
                    }
                }
                dialog.show()
            } else if (options[chosen] == "Edit text") {
                val input = EditText(activity).apply { setText(step.optString("value")); maxLines = 5; filters = arrayOf(android.text.InputFilter.LengthFilter(2000)) }
                AlertDialog.Builder(activity).setTitle("Text to enter").setView(input)
                    .setMessage("Stored locally in this macro. Do not enter passwords or verification codes.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                        if (input.length() <= 2000) { step.put("value", input.text.toString()); save(name, macro) }
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

    // Renames a routine and updates existing remote assignments atomically within each preference
    // file.
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
