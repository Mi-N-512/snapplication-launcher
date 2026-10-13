package app.snapplication.launcher

import org.junit.Assert.*
import org.junit.Test

/*
 * Checks external-player advancement acknowledgement without Android services or network playback.
 * Related classes: EpisodeAdvanceGuard and ExternalWatchPlayback. No UI is created.
 * Add cases here when changing repeated-press handling or support for reused episode identities.
 */
class EpisodeAdvanceGuardTest {
    // Repeated presses at the old completed position must not skip several episodes.
    @Test fun waitsForPlayerAcknowledgement() {
        val guard = EpisodeAdvanceGuard()
        guard.sent("episode", 950)
        assertTrue(guard.blocked("episode", 950))
        assertTrue(guard.blocked("episode", 960))
        assertTrue(guard.blocked("episode", -1))
    }

    // A callback at the new start rearms even if the app reuses its title and session.
    @Test fun reusedIdentityCanCompleteAnotherEpisode() {
        val guard = EpisodeAdvanceGuard()
        guard.sent("series", 950)
        assertFalse(guard.blocked("series", 0))
        assertFalse(guard.blocked("series", 950))
    }

    // Switching episodes or applications does not retain another item's pending command.
    @Test fun newIdentityRearms() {
        val guard = EpisodeAdvanceGuard()
        guard.sent("first", 950)
        assertFalse(guard.blocked("second", 980))
    }
}
