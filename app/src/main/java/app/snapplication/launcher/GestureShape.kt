package app.snapplication.launcher

/*
Classifies recorded motion by distance, so repeated stationary samples remain a tap. MacroEditor
uses this for labels and coordinate editing; it does not change recorded playback. Screen controls:
Tap/swipe step names and the tap-position controls. Extend thresholds here with tests.

This object is a shared helper and does not inherit from another launcher class.

points contains the recorded finger positions. isTap compares how far those positions move, so
several samples at the same spot still count as one tap. MacroEditor calls this shared helper to
choose the step label and editing controls. It keeps no changing state and sends no input.
*/
internal object GestureShape {
    // Normalized coordinates tolerate small finger jitter, regardless of sample count.
    fun isTap(points: List<Pair<Double, Double>>): Boolean {
        val first = points.firstOrNull() ?: return false
        return points.all { (x, y) -> x.isFinite() && y.isFinite() &&
            kotlin.math.hypot(x - first.first, y - first.second) <= .005 }
    }
}
