package app.snapplication.launcher

import org.junit.Assert.*
import org.junit.Test

/*
Regression checks for EpisodeResume; run by the local Gradle unit-test tasks. Related code:
EpisodeResume. No UI or Android device is created here. Add boundary cases here when changing that
helper so its existing callers retain correct behaviour.
*/
class EpisodeResumeTest {
    // A threshold is inclusive and must not advance before the configured point.
    @Test fun thresholdBoundary() {
        assertFalse(EpisodeResume.shouldAdvance(9499, 10000, 95))
        assertTrue(EpisodeResume.shouldAdvance(9500, 10000, 95))
    }
    // Unknown or empty durations and unstarted items must never advance.
    @Test fun ignoresUnknownDurationsAndUnstartedItems() {
        assertFalse(EpisodeResume.shouldAdvance(5000, -1, 95))
        assertFalse(EpisodeResume.shouldAdvance(0, 10000, 0))
        assertFalse(EpisodeResume.shouldAdvance(1, 0, 0))
    }
    // Both user-selectable endpoints have defined behavior.
    @Test fun endpoints() {
        assertTrue(EpisodeResume.shouldAdvance(1, 10000, 0))
        assertFalse(EpisodeResume.shouldAdvance(9999, 10000, 100))
        assertTrue(EpisodeResume.shouldAdvance(10000, 10000, 100))
    }
}
