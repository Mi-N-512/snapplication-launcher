package app.snapplication.launcher

/*
Compares container paths recorded by RemoteActionService without relying on mutable button text.

This object is a shared helper and does not inherit from another launcher class.

expected and actual are the saved and current paths of the controls containing a target. matches
compares those paths; targetMatches checks the relevant app; startOnCurrentScreen decides whether
playback should open a recorded app first. RemoteActionService calls these shared checks. The object
has no changing state and does not draw controls.

Used by or used with: RemoteActionService and MacroContextTest. Screen controls: none; checks
control-container identity and chooses the owner appropriate to each replay kind. Changes belong
here: Edit matching rules and associated tests here; do not treat changing text as stable control
identity.
*/
internal object MacroContext {
    // Touch recordings need their recorded starting screen; explicit launch choices and legacy
    // semantic macros retain their behavior.
    fun startOnCurrentScreen(explicit: Boolean?, version: Int, hasGestures: Boolean): Boolean =
        explicit ?: (version >= 2 && hasGestures)

    // Exact paths reject reused IDs in other containers; missing paths are handled by the legacy
    // caller.
    fun matches(expected: List<String>, actual: List<String>): Boolean = expected == actual

    // Gestures use the recorder's guarded top window; semantic actions retain active-app targeting.
    fun targetMatches(expected: String, activeOwner: String?, guardedOwner: String?, gesture: Boolean): Boolean =
        expected.isNotBlank() && expected == (if (gesture) guardedOwner else activeOwner)
}
