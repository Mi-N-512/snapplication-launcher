package app.snapplication.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
Regression checks for MacroContext; run by the local Gradle unit-test tasks. Related code:
MacroContext. No UI or Android device is created here. Add boundary cases here when changing that
helper so its existing callers retain correct behaviour.
*/
class MacroContextTest {
    // A vendor dialog remains the gesture target even when Android reports the launcher as active.
    @Test fun gestureUsesGuardedDialogOwner() {
        assertTrue(MacroContext.targetMatches("vendor.dialog", "launcher", "vendor.dialog", true))
        assertFalse(MacroContext.targetMatches("launcher", "launcher", "vendor.dialog", true))
    }
    // Missing or rejected guard data must never fall back to a seemingly matching active app.
    @Test fun gestureRejectsUnavailableGuard() {
        assertFalse(MacroContext.targetMatches("vendor.dialog", "vendor.dialog", null, true))
        assertFalse(MacroContext.targetMatches("", "launcher", "", true))
    }
    // Semantic controls continue to require the active app instead of an unrelated top window.
    @Test fun semanticActionUsesActiveOwner() {
        assertTrue(MacroContext.targetMatches("launcher", "launcher", "vendor.dialog", false))
        assertFalse(MacroContext.targetMatches("vendor.dialog", "launcher", "vendor.dialog", false))
    }
    // An unchanged container accepts a control whose toggle wording may have changed.
    @Test fun acceptsSameContainer() {
        assertTrue(MacroContext.matches(listOf("player|FrameLayout", "root|LinearLayout"), listOf("player|FrameLayout", "root|LinearLayout")))
    }
    // A reused control on a different screen must stop rather than invoke the new action.
    @Test fun rejectsDifferentContainer() {
        assertFalse(MacroContext.matches(listOf("player|FrameLayout"), listOf("downloads|FrameLayout")))
    }
    // Container class and path depth are part of identity even where IDs are absent.
    @Test fun rejectsChangedStructure() {
        assertFalse(MacroContext.matches(listOf("|FrameLayout"), listOf("|LinearLayout")))
        assertFalse(MacroContext.matches(listOf("root|FrameLayout"), emptyList()))
    }
    // Older touch macros must not reset Home before replaying coordinates from settings or an
    // in-app page.
    @Test fun touchMacroDefaultsToCurrentScreen() { assertTrue(MacroContext.startOnCurrentScreen(null, 2, true)) }
    @Test fun explicitLaunchChoiceIsPreserved() { assertFalse(MacroContext.startOnCurrentScreen(false, 2, true)) }
    @Test fun semanticAndLegacyDefaultsStayUnchanged() {
        assertFalse(MacroContext.startOnCurrentScreen(null, 2, false))
        assertFalse(MacroContext.startOnCurrentScreen(null, 1, true))
        assertTrue(MacroContext.startOnCurrentScreen(true, 1, false))
    }
}
