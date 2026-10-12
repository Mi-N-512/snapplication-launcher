package app.snapplication.launcher

/*
 * Decides whether a saved remote action can run while a text field has focus. RemoteControls uses this
 * policy when latching a press; MainActivity and RemoteActionService supply the focus check lazily.
 * There is no UI here. Navigation, Home, Back and screen locking already work during text entry, so
 * they must not wait for another app's accessibility tree. Media and macro shortcuts still check focus.
 * Edit the permitted actions here when adding remote functions; keep private-input protection intact.
 */
object RemoteInputPolicy {
    private val duringTextEntry = setOf("Up", "Down", "Left", "Right", "Select", "Home", "Back", "Apps",
        "Screen off", "Record/stop macro", "Stop action")

    // Avoids cross-process focus reads for unmapped keys and actions independent of text-entry state.
    fun permitted(action: String?, isEditing: () -> Boolean): String? {
        if (action == null) return null
        return if (action in duringTextEntry || !isEditing()) action else null
    }
}
