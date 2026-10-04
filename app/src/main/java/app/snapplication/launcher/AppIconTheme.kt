package app.snapplication.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches the launcher icon aliases for MainActivity's Settings without disabling the HOME activity.
 * Android PackageManager persists the choice across process death; no separate preference can drift. */
object AppIconTheme {
    val choices = listOf("Blue / white", "Gold / black")

    /** Reads the enabled alias to provide Settings and onboarding with the current icon label. */
    fun currentLabel(context: Context): String = if (
        context.packageManager.getComponentEnabledSetting(alias(context, "GoldIcon")) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    ) choices[1] else choices[0]

    /** Enables the destination first, then hides the previous icon while leaving the running app alive. */
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

    /** Uses the installed flavour's package id and the aliases' shared fully qualified class names. */
    private fun alias(context: Context, name: String) = ComponentName(context.packageName, "app.snapplication.launcher.$name")
}
