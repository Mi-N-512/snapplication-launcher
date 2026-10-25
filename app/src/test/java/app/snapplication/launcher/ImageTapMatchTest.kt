package app.snapplication.launcher

import org.junit.Assert.*
import org.junit.Test

class ImageTapMatchTest {
    private fun reference() = IntArray(6144) { if ((it / 96 / 4 + it % 96 / 4) % 2 == 0) 0xffeeeeee.toInt() else 0xff222222.toInt() }
    @Test fun identicalDetailedImageMatches() { val a = reference(); assertTrue(ImageTapMatch.matches(a, a.copyOf())) }
    @Test fun blankAndWrongImagesAreRejected() {
        assertFalse(ImageTapMatch.matches(IntArray(6144), IntArray(6144)))
        assertFalse(ImageTapMatch.matches(reference(), IntArray(6144)))
        assertFalse(ImageTapMatch.matches(reference(), IntArray(2)))
    }
    @Test fun changedLocalButtonIsRejected() {
        val a = reference(); val b = a.copyOf()
        for (y in 48 until 56) for (x in 72 until 84) b[y * 96 + x] = 0xff00ff00.toInt()
        assertFalse(ImageTapMatch.matches(a, b))
    }
}
