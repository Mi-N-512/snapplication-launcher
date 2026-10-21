package app.snapplication.launcher

/*
 * Classifies recorded motion by distance, so repeated stationary samples remain a tap.
 * MacroEditor uses this for labels and coordinate editing; it does not change recorded playback.
 * UI: Tap/swipe step names and the tap-position controls. Extend thresholds here with tests.
 */
internal object GestureShape {
    // Normalized coordinates tolerate small finger jitter, regardless of sample count.
    fun isTap(points: List<Pair<Double, Double>>): Boolean {
        val first = points.firstOrNull() ?: return false
        return points.all { (x, y) -> x.isFinite() && y.isFinite() &&
            kotlin.math.hypot(x - first.first, y - first.second) <= .005 }
    }
}
