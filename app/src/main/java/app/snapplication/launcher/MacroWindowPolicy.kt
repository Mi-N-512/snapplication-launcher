package app.snapplication.launcher

/*
Identifies the narrow Android navigation bar so it cannot replace a macro's application target. Used
by or used with: RemoteActionService inspects window roots; MacroWindowPolicyTest checks boundaries.
Screen controls: No independent screen. This affects the recorder's target selection, not remote key
mappings. Changes belong here: Add only verified chrome identities here. Permission dialogs and
notification panels must remain separate windows subject to the recorder's existing protection and
overlap checks.

This object is a shared helper and does not inherit from another launcher class.

owner and id identify a window, while the position and size arguments describe its bounds.
navigationBar checks whether that window is Android navigation rather than application content.
RemoteActionService uses the result while choosing a recording target. This shared helper keeps no
state and creates no windows.
*/
internal object MacroWindowPolicy {
    // Requires both the observed System UI resource and a thin strip attached to a screen edge.
    fun navigationBar(owner: String?, id: String?, left: Int, top: Int, right: Int, bottom: Int,
        screenWidth: Int, screenHeight: Int): Boolean {
        if (owner != "com.android.systemui" || id != "com.android.systemui:id/navigation_bar_frame" ||
            screenWidth <= 0 || screenHeight <= 0) return false
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0 || left < 0 || top < 0 || right > screenWidth || bottom > screenHeight) return false
        return (width == screenWidth && height <= screenHeight * .15 && (top == 0 || bottom == screenHeight)) ||
            (height == screenHeight && width <= screenWidth * .15 && (left == 0 || right == screenWidth))
    }
}
