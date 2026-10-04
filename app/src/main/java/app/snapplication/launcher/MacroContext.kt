package app.snapplication.launcher

/** Compares container paths recorded by RemoteActionService without relying on mutable button text. */
internal object MacroContext {
    /** Exact paths reject reused IDs in other containers; missing paths are handled by the legacy caller. */
    fun matches(expected: List<String>, actual: List<String>): Boolean = expected == actual
}