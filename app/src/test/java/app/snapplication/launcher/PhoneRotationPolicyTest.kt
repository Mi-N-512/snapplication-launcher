package app.snapplication.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/*
Tests PhoneRotation's physical angle policy without requiring sensor hardware. Covers all four
holds, wraparound and unstable positions; no UI or stored preferences are used.
*/
class PhoneRotationPolicyTest {
    // Both upright directions and both sideways directions are supported.
    @Test fun supportsEveryQuarterTurn() {
        listOf(0, 90, 180, 270).forEachIndexed { index, angle ->
            assertEquals(index, PhoneRotationPolicy.quadrant(angle))
        }
        assertEquals(0, PhoneRotationPolicy.quadrant(359))
    }
    // Diagonal/flat readings must not produce repeated rotation changes.
    @Test fun ignoresUnknownAndDiagonalHolds() {
        listOf(-1, 45, 135, 225, 315, 360).forEach {
            assertEquals(-1, PhoneRotationPolicy.quadrant(it))
        }
        assertEquals(0, PhoneRotationPolicy.quadrant(25))
        assertEquals(-1, PhoneRotationPolicy.quadrant(26))
    }
}
