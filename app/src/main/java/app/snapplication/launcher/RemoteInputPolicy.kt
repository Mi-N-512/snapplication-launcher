package app.snapplication.launcher

/*
Decides whether a saved remote action can run while a text field has focus. RemoteControls uses this
policy when remembering a press; MainActivity and RemoteActionService supply the focus check lazily.
There is no UI here. Navigation, Home, Back and screen locking already work during text entry, so
they must not wait for another app's accessibility tree. Media and macro shortcuts still check
focus. Edit the permitted actions here when adding remote functions; keep private-input protection
intact.

This object is a shared helper and does not inherit from another launcher class.

duringTextEntry lists actions allowed while a text field is active. permitted checks a saved action
and asks isEditing only when needed. nativeNavigation identifies navigation already handled by
Android, and handleInService chooses whether the accessibility service should process the key.
RemoteControls, MainActivity and RemoteActionService use this shared set of rules.
*/
object RemoteInputPolicy {
    private val duringTextEntry = setOf("Up", "Down", "Left", "Right", "Select", "Home", "Back", "Apps",
        "Screen off", "Record/stop macro", "Stop action")

    // Leave already-native directions with Android, including vendor dialogs that expose no
    // accessibility nodes.
    fun nativeNavigation(action: String?, code: Int): Boolean = when (action) {
        "Up" -> code == 19
        "Down" -> code == 20
        "Left" -> code == 21
        "Right" -> code == 22
        "Select" -> code == 23 || code == 66
        else -> false
    }

    // A vendor dialog can cover the launcher while accessibility still reports the launcher
    // package.
    fun handleInService(learning: Boolean, launcherHasFocus: Boolean): Boolean = !learning && !launcherHasFocus

    // Avoids cross-process focus reads for unmapped keys and actions independent of text-entry
    // state.
    fun permitted(action: String?, isEditing: () -> Boolean): String? {
        if (action == null) return null
        return if (action in duringTextEntry || !isEditing()) action else null
    }
}
