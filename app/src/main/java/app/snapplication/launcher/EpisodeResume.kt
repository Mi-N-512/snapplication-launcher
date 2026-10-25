package app.snapplication.launcher

/*
Defines the completion-slider rule used by WatchPlayer and ExternalWatchPlayback.

This object is a shared helper and does not inherit from another launcher class.

positionMs is the current playback time, durationMs is the total length, and thresholdPercent is the
user setting. shouldAdvance rejects unknown durations and compares the current position with that
percentage. WatchPlayer and ExternalWatchPlayback call this shared helper; it keeps no changing
state between calls.

Used by or used with: WatchPlayer prepares local files; ExternalWatchPlayback advances supported
external players on the next explicit Play request. EpisodeResumeTest checks boundary cases. Screen
controls: none; supplies a decision used by the episode-completion setting. Changes belong here:
Adjust completion thresholds and boundary rules here, with matching tests; external apps still own
their playback state.
*/
object EpisodeResume {
    // Unknown-duration streams never count as completed; zero percent advances any started item.
    fun shouldAdvance(positionMs: Long, durationMs: Long, thresholdPercent: Int): Boolean {
        if (durationMs <= 0 || positionMs <= 0) return false
        return positionMs.toDouble() / durationMs >= thresholdPercent.coerceIn(0, 100) / 100.0
    }
}
