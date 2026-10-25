package app.snapplication.launcher

/*
Prevents repeat Play presses from sending Next repeatedly before a player reports its new episode.
Used by or used with: ExternalWatchPlayback observes session callbacks; EpisodeAdvanceGuardTest
covers rearming. Screen controls: None. The in-memory identity contains no stored media URL or user
history. Edit here for acknowledgement rules; retain progress-reset handling for apps that reuse
episode IDs.

This class is a helper and does not inherit from another launcher class.

sentIdentity remembers the item for which Next was already sent. sentPosition records its playback
position. ExternalWatchPlayback calls sent after a successful command, then blocked before another
one. A new item or a reset playback position clears the block. ExternalWatchPlayback owns this
helper; neither class inherits from the other.
*/
class EpisodeAdvanceGuard {
    private var sentIdentity: Any? = null
    private var sentPosition = 0L

    // Marks only a command successfully dispatched to the player.
    fun sent(identity: Any, position: Long) { sentIdentity = identity; sentPosition = position }

    // New media or a confirmed progress reset allows another Next; unknown positions do not
    // acknowledge it.
    fun blocked(identity: Any, position: Long): Boolean {
        if (identity != sentIdentity || position in 0 until sentPosition) sentIdentity = null
        return sentIdentity != null
    }
}
