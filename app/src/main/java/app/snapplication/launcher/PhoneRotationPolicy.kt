package app.snapplication.launcher

/*
Converts a physical angle into a stable quarter-turn for PhoneRotation. There is no UI or Android
dependency, so flat-device and boundary behaviour can be regression tested. Change the 25-degree
tolerance here if the physical settling behaviour needs adjustment.

This object is a shared helper and does not inherit from another launcher class.

angle is the physical tilt reported by Android. quadrant accepts angles near a quarter turn and
returns the corresponding direction, or a rejected value for uncertain positions. PhoneRotation uses
that answer before changing the launcher orientation. This shared helper stores no state and has no
Android controls.
*/
object PhoneRotationPolicy {
    // Unknown angles and diagonal holds do not choose a new orientation.
    fun quadrant(angle: Int): Int {
        if (angle !in 0..359) return -1
        val nearest = ((angle + 45) / 90) % 4
        val distance = kotlin.math.abs(angle - nearest * 90).let { minOf(it, 360 - it) }
        return if (distance <= 25) nearest else -1
    }
}
