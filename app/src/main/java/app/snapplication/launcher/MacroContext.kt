package app.snapplication.launcher

/*
 * Compares container paths recorded by RemoteActionService without relying on mutable button text.
 *
 * Related classes: RemoteActionService and MacroContextTest.
 * UI: No UI; checks stored control-container identity during semantic replay.
 * Edit or extend: Edit matching rules and associated tests here; do not treat changing text as stable control
 * identity.
 */
internal object MacroContext {
    // Exact paths reject reused IDs in other containers; missing paths are handled by the legacy caller.
    fun matches(expected: List<String>, actual: List<String>): Boolean = expected == actual
}
