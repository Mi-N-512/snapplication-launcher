package app.snapplication.launcher

/** Defines the completion-slider rule used by WatchPlayer when the screen turns off. */
object EpisodeResume {
    /** Unknown-duration streams never count as completed; zero percent advances any started item. */
    fun shouldAdvance(positionMs: Long, durationMs: Long, thresholdPercent: Int): Boolean {
        if (durationMs <= 0 || positionMs <= 0) return false
        return positionMs.toDouble() / durationMs >= thresholdPercent.coerceIn(0, 100) / 100.0
    }
}
