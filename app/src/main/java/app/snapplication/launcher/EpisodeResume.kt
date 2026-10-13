package app.snapplication.launcher

/*
 * Defines the completion-slider rule used by WatchPlayer and ExternalWatchPlayback.
 *
 * Related classes: WatchPlayer prepares local files; ExternalWatchPlayback advances supported external players
 * on the next explicit Play request. EpisodeResumeTest checks boundary cases.
 * UI: No UI; supplies a decision used by the episode-completion setting.
 * Edit or extend: Adjust completion thresholds and boundary rules here, with matching tests; external apps still
 * own their playback state.
 */
object EpisodeResume {
    // Unknown-duration streams never count as completed; zero percent advances any started item.
    fun shouldAdvance(positionMs: Long, durationMs: Long, thresholdPercent: Int): Boolean {
        if (durationMs <= 0 || positionMs <= 0) return false
        return positionMs.toDouble() / durationMs >= thresholdPercent.coerceIn(0, 100) / 100.0
    }
}
