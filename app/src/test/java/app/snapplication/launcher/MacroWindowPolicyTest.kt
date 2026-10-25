package app.snapplication.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
Verifies the narrow chrome exception used by RemoteActionService during macro recording and replay.
Used by or used with: MacroWindowPolicy. No Android UI is created by these local regression tests.
Extend these cases when adding verified bar identities; preserve rejection of dialogs and panels.
*/
class MacroWindowPolicyTest {
    private val owner = "com.android.systemui"
    private val id = "com.android.systemui:id/navigation_bar_frame"

    // Matches the right-hand Huawei navigation strip observed on the physical Mate.
    @Test fun acceptsObservedLandscapeBar() {
        assertTrue(MacroWindowPolicy.navigationBar(owner, id, 2041, 0, 2160, 1080, 2160, 1080))
    }

    // The same identity may appear along the bottom after rotation.
    @Test fun acceptsPortraitBottomBar() {
        assertTrue(MacroWindowPolicy.navigationBar(owner, id, 0, 2041, 1080, 2160, 1080, 2160))
    }

    // A full panel or centered dialog is never treated as a navigation strip.
    @Test fun rejectsPanelsAndDialogs() {
        assertFalse(MacroWindowPolicy.navigationBar(owner, id, 0, 0, 2160, 1080, 2160, 1080))
        assertFalse(MacroWindowPolicy.navigationBar(owner, id, 358, 316, 1683, 828, 2160, 1080))
    }

    // Geometry alone cannot exempt an unknown System UI surface or another app's view.
    @Test fun rejectsUnverifiedIdentity() {
        assertFalse(MacroWindowPolicy.navigationBar(owner, "permission_dialog", 2041, 0, 2160, 1080, 2160, 1080))
        assertFalse(MacroWindowPolicy.navigationBar("other.app", id, 2041, 0, 2160, 1080, 2160, 1080))
        assertFalse(MacroWindowPolicy.navigationBar(null, null, 2041, 0, 2160, 1080, 2160, 1080))
    }

    // Reject malformed and off-screen bounds instead of relaxing the shape checks.
    @Test fun rejectsInvalidBounds() {
        assertFalse(MacroWindowPolicy.navigationBar(owner, id, -1, 0, 119, 1080, 2160, 1080))
        assertFalse(MacroWindowPolicy.navigationBar(owner, id, 2160, 0, 2160, 1080, 2160, 1080))
        assertFalse(MacroWindowPolicy.navigationBar(owner, id, 2041, 0, 2160, 1080, 0, 0))
    }
}
