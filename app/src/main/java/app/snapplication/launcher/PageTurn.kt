package app.snapplication.launcher

/*
Decides which way a tap, swipe or key turns a page in ReaderPanel. Keeping the rules here lets
PageTurnTest check them without an Android screen.

This object is a shared helper and does not inherit from another launcher class.

tap divides the page into a back zone (the left third) and a forward zone (the rest), the usual
e-reader layout, so a reader holding the device in the right hand can move forward with one thumb.
swipe needs a mostly horizontal movement past a minimum distance; vertical swipes stay free for
MainActivity's drawer gesture. key maps remote, keyboard and page-turner buttons. ReaderPanel calls
these functions; it is not their parent class.

Used by or used with: ReaderPanel. Screen controls: none of its own. Changes belong here: edit tap
zones, swipe thresholds or accepted keys here; keep ReaderPanel responsible for moving and saving.
*/
object PageTurn {
    // Returns -1 for the left third and +1 elsewhere; an unmeasured page turns nothing.
    fun tap(x: Float, width: Int): Int = when {
        width <= 0 -> 0
        x < width / 3f -> -1
        else -> 1
    }

    // A leftward swipe moves forward, as when turning a paper page; short or vertical drags are
    // ignored.
    fun swipe(dx: Float, dy: Float, minDistance: Float): Int = when {
        kotlin.math.abs(dx) < minDistance || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f -> 0
        dx < 0 -> 1
        else -> -1
    }

    // Maps directional, page and media keys used by remotes, keyboards and Bluetooth page turners.
    fun key(keyCode: Int): Int = when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_PAGE_DOWN,
        android.view.KeyEvent.KEYCODE_MEDIA_NEXT, android.view.KeyEvent.KEYCODE_SPACE -> 1
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_PAGE_UP,
        android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1
        else -> 0
    }
}
