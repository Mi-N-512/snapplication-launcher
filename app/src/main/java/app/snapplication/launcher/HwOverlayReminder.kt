package app.snapplication.launcher

import android.content.Context
import android.provider.Settings

/*
Decides when Watch Home shows the "Disable HW overlays" reminder.

Android forgets the developer switch "Disable HW overlays" on every restart, and an app cannot read
it directly. Android's boot count can be read, so the launcher records the boot in which the
switch was last set (or the reminder was dismissed). The reminder appears in any other boot, the
same way Repair previews appears only while capture has stopped.
*/
object HwOverlayReminder {
    private fun prefs(context: Context) = context.getSharedPreferences("hwOverlay", Context.MODE_PRIVATE)

    private fun boot(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun needed(context: Context, state: LauncherState): Boolean {
        if (!state.hwOverlayButton) return false
        val current = boot(context)
        val prefs = prefs(context)
        return prefs.getInt("setBoot", -2) != current && prefs.getInt("dismissedBoot", -2) != current
    }

    // True once the user has set the switch through the launcher at least once.
    fun everSet(context: Context): Boolean = prefs(context).contains("setBoot")

    fun markSet(context: Context) { prefs(context).edit().putInt("setBoot", boot(context)).apply() }

    fun dismiss(context: Context) { prefs(context).edit().putInt("dismissedBoot", boot(context)).apply() }
}
