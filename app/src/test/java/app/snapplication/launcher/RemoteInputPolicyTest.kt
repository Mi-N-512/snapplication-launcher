package app.snapplication.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/*
 * Checks the focus policy used by RemoteControls before MainActivity or RemoteActionService runs an
 * assignment. No Android UI is created. Throwing focus probes model an unavailable or slow remote app;
 * navigation must not consult them, while media and macro shortcuts must preserve text-entry protection.
 * Extend these checks when adding actions or changing the keyboard policy.
 */
class RemoteInputPolicyTest {
    // Navigation and escape actions must remain usable even when another app cannot supply focus.
    @Test fun navigationDoesNotInspectOtherApp() {
        for (action in listOf("Up", "Down", "Left", "Right", "Select", "Home", "Back", "Apps",
            "Screen off", "Record/stop macro", "Stop action")) {
            assertEquals(action, RemoteInputPolicy.permitted(action) { error("Unexpected focus query") })
        }
    }

    // Unassigned typing/buttons pass through without querying a window in another process.
    @Test fun unmappedKeysDoNotInspectOtherApp() {
        assertNull(RemoteInputPolicy.permitted(null) { error("Unexpected focus query") })
    }

    // Both media shortcuts and arbitrary named routines still respect an active text field.
    @Test fun shortcutsAreBlockedDuringTextEntry() {
        for (action in listOf("Play/pause", "Macro: accept display")) {
            var queries = 0
            assertNull(RemoteInputPolicy.permitted(action) { queries++; true })
            assertEquals(1, queries)
        }
    }

    // Leaving the text field restores the original shortcut without changing its saved identifier.
    @Test fun shortcutsRunOutsideTextEntry() {
        assertEquals("Macro: accept display", RemoteInputPolicy.permitted("Macro: accept display") { false })
        assertEquals("Play/pause", RemoteInputPolicy.permitted("Play/pause") { false })
    }
}
