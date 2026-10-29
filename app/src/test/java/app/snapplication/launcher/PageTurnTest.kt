package app.snapplication.launcher

import org.junit.Assert.*
import org.junit.Test

/*
Regression checks for PageTurn; run by the local Gradle unit-test tasks. Related code: PageTurn
and ReaderPanel. No UI or Android device is created here. Add cases here when changing tap zones,
swipe thresholds or accepted keys.
*/
class PageTurnTest {
    // The left third goes back; the middle and right go forward; an unmeasured page does nothing.
    @Test fun tapZones() {
        assertEquals(-1, PageTurn.tap(0f, 900))
        assertEquals(-1, PageTurn.tap(299f, 900))
        assertEquals(1, PageTurn.tap(300f, 900))
        assertEquals(1, PageTurn.tap(899f, 900))
        assertEquals(0, PageTurn.tap(10f, 0))
    }

    // Leftward swipes move forward, rightward swipes move back, and short or vertical drags are
    // ignored so the drawer swipe keeps working.
    @Test fun swipes() {
        assertEquals(1, PageTurn.swipe(-200f, 10f, 100f))
        assertEquals(-1, PageTurn.swipe(200f, -10f, 100f))
        assertEquals(0, PageTurn.swipe(-80f, 0f, 100f))
        assertEquals(0, PageTurn.swipe(-150f, 200f, 100f))
    }

    // Remote, keyboard and page-turner keys map to a direction; other keys are left alone.
    @Test fun keys() {
        assertEquals(1, PageTurn.key(android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(1, PageTurn.key(android.view.KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(-1, PageTurn.key(android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(-1, PageTurn.key(android.view.KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(0, PageTurn.key(android.view.KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(0, PageTurn.key(android.view.KeyEvent.KEYCODE_ENTER))
    }
}
