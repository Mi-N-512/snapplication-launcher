package app.snapplication.launcher

import org.junit.Assert.*
import org.junit.Test

class GestureShapeTest {
    @Test fun repeatedSamplesAreStillATap() {
        assertTrue(GestureShape.isTap(List(4) { .703378 to .694727 }))
        assertTrue(GestureShape.isTap(listOf(.5 to .5, .502 to .501, .5 to .5)))
    }
    @Test fun movementRatherThanPointCountDefinesSwipe() {
        assertFalse(GestureShape.isTap(listOf(.2 to .2, .6 to .6)))
        assertFalse(GestureShape.isTap(emptyList()))
        assertFalse(GestureShape.isTap(listOf(Double.NaN to .2)))
    }
}
