package app.snapplication.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression checks for reused macro control IDs across different screen containers. */
class MacroContextTest {
    /** An unchanged container accepts a control whose toggle wording may have changed. */
    @Test fun acceptsSameContainer() {
        assertTrue(MacroContext.matches(listOf("player|FrameLayout", "root|LinearLayout"), listOf("player|FrameLayout", "root|LinearLayout")))
    }
    /** A reused control on a different screen must stop rather than invoke the new action. */
    @Test fun rejectsDifferentContainer() {
        assertFalse(MacroContext.matches(listOf("player|FrameLayout"), listOf("downloads|FrameLayout")))
    }
    /** Container class and path depth are part of identity even where IDs are absent. */
    @Test fun rejectsChangedStructure() {
        assertFalse(MacroContext.matches(listOf("|FrameLayout"), listOf("|LinearLayout")))
        assertFalse(MacroContext.matches(listOf("root|FrameLayout"), emptyList()))
    }
}