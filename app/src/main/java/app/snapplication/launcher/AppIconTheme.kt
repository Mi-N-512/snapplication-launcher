package app.snapplication.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/*
Switches the launcher icon aliases for MainActivity's Settings without disabling the HOME activity.
Android PackageManager saves the choice across process death; no separate preference can drift.

This object is a shared helper and does not inherit from another launcher class.

choices holds the two icon names. showChooser draws their previews and remembers the selected name
until Apply is pressed. apply enables that icon first, then hides the other one. MainActivity calls
this helper; it is not its parent class.

Used by or used with: MainActivity and the BlueIcon/GoldIcon manifest aliases. Screen controls: No
screen of its own; changes the installed launcher icon selected in Settings. Changes belong here:
Add icon choices together with their manifest aliases and preview artwork; keep the HOME activity
enabled.
*/
object AppIconTheme {
    val choices = listOf("Blue / white", "Gold / black")

    // Previews both installed icon variants before MainActivity commits the selected alias.
    fun showChooser(activity: android.app.Activity, onChanged: () -> Unit) {
        var selected = currentLabel(activity)
        val density = activity.resources.displayMetrics.density
        val body = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val images = android.widget.LinearLayout(activity)
        listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_gold).forEachIndexed { index, resource ->
            images.addView(android.widget.ImageView(activity).apply {
                setImageResource(resource)
                contentDescription = choices[index] + " icon preview"
            }, android.widget.LinearLayout.LayoutParams(0, (112 * density).toInt(), 1f))
        }
        body.addView(images)
        val options = android.widget.RadioGroup(activity).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        choices.forEach { choice ->
            options.addView(android.widget.RadioButton(activity).apply {
                id = android.view.View.generateViewId(); text = choice; isChecked = choice == selected
                setOnClickListener { selected = choice }
            }, android.widget.RadioGroup.LayoutParams(0, -2, 1f))
        }
        body.addView(options)
        android.app.AlertDialog.Builder(activity).setTitle("Choose your launcher icon").setView(body)
            .setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
                if (apply(activity, selected)) onChanged()
                else android.widget.Toast.makeText(activity, "The icon could not change. Try again.", android.widget.Toast.LENGTH_LONG).show()
            }.show()
    }

    // Reads the enabled alias to provide Settings and onboarding with the current icon label.
    fun currentLabel(context: Context): String = if (
        context.packageManager.getComponentEnabledSetting(alias(context, "GoldIcon")) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    ) choices[1] else choices[0]

    // Enables the destination first, then hides the previous icon while leaving the running app
    // alive.
    fun apply(context: Context, choice: String): Boolean {
        if (choice !in choices) return false
        val gold = choice == choices[1]
        val selected = alias(context, if (gold) "GoldIcon" else "BlueIcon")
        val previous = alias(context, if (gold) "BlueIcon" else "GoldIcon")
        return try {
            context.packageManager.setComponentEnabledSetting(selected,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            context.packageManager.setComponentEnabledSetting(previous,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            true
        } catch (_: RuntimeException) { false }
    }

    // Uses the installed flavour's package id and the aliases' shared fully qualified class names.
    private fun alias(context: Context, name: String) = ComponentName(context.packageName, "app.snapplication.launcher.$name")
}
