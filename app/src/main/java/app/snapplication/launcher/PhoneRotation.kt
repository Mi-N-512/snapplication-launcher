package app.snapplication.launcher

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.OrientationEventListener

/*
Reads physical tilt while the Phone launcher is visible, including devices whose system rotation
listener is unavailable. MainActivity starts/stops this helper with its lifecycle. LauncherState
decides whether rotation is enabled; MainActivity selects Read/Watch after configuration changes. No
overlay or system setting is changed and other apps are unaffected. Screen controls: none. Adjust
the settling delay here and angular thresholds in PhoneRotationPolicy.

This class is a helper and does not inherit from another launcher class.

activity is the launcher screen and enabled checks whether automatic rotation is currently allowed.
listener receives tilt changes, pending stores the proposed orientation, and handler waits briefly
before apply requests it. MainActivity starts and stops this helper with the screen lifecycle.
PhoneRotationPolicy decides which angles count as a settled quarter turn.
*/
class PhoneRotation(private val activity: Activity, private val enabled: () -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private var pending = -1
    private val apply = Runnable {
        if (enabled() && pending >= 0 && !activity.isFinishing && !activity.isDestroyed) {
            val rotation = activity.windowManager.defaultDisplay.rotation
            val portrait = activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val naturalPortrait = if (rotation % 2 == 0) portrait else !portrait
            val requests = if (naturalPortrait) intArrayOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            else intArrayOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT)
            activity.requestedOrientation = requests[pending]
        }
    }
    private val listener = object : OrientationEventListener(activity) {
        // Ignore flat/diagonal positions; require a stable quarter-turn before changing layout.
        override fun onOrientationChanged(orientation: Int) {
            val next = if (enabled()) PhoneRotationPolicy.quadrant(orientation) else -1
            if (next == pending) return
            handler.removeCallbacks(apply)
            pending = next
            if (next >= 0) handler.postDelayed(apply, 450)
        }
    }
    val supported: Boolean get() = listener.canDetectOrientation()

    // Sensor work is limited to the visible launcher, never background reading or playback.
    fun start() { if (listener.canDetectOrientation()) listener.enable() }
    // Cancels pending rotations before another app or system dialog becomes active.
    fun stop() { listener.disable(); handler.removeCallbacks(apply); pending = -1 }
}
