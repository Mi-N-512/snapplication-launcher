package app.snapplication.launcher

/*
 * Prevents repeat Play presses from sending Next repeatedly before a player reports its new episode.
 * Related classes: ExternalWatchPlayback observes session callbacks; EpisodeAdvanceGuardTest covers rearming.
 * UI: None. The in-memory identity contains no stored media URL or user history.
 * Edit here for acknowledgement rules; retain progress-reset handling for apps that reuse episode IDs.
 */
class EpisodeAdvanceGuard {
    private var sentIdentity: Any? = null
    private var sentPosition = 0L

    // Marks only a command successfully dispatched to the player.
    fun sent(identity: Any, position: Long) { sentIdentity = identity; sentPosition = position }

    // New media or a confirmed progress reset rearms Next; unknown positions do not acknowledge it.
    fun blocked(identity: Any, position: Long): Boolean {
        if (identity != sentIdentity || position in 0 until sentPosition) sentIdentity = null
        return sentIdentity != null
    }
}
