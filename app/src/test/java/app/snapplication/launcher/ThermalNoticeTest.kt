package app.snapplication.launcher

import org.junit.Assert.*
import org.junit.Test

/*
 * Regression checks for ThermalNotice; run by the local Gradle unit-test tasks.
 * Related code: ThermalNotice. No UI or Android device is created here.
 * Add boundary cases here when changing that helper so its existing callers retain correct behaviour.
 */
class ThermalNoticeTest {
    // Unknown or ordinary thermal states must not generate overheating notices.
    @Test fun ignoresMissingUnknownAndNonSevereStates() {
        listOf(null, -1, 0, 1, 2, 7, Int.MAX_VALUE).forEach { assertNull(ThermalNotice.forStatus(it)) }
    }

    // Every Android severe-to-shutdown state must produce a notice, including the threshold boundary.
    @Test fun warnsForAllSevereStates() {
        (3..6).forEach { assertFalse(ThermalNotice.forStatus(it).isNullOrBlank()) }
        assertTrue(ThermalNotice.forStatus(6)!!.contains("shutdown"))
    }
}
