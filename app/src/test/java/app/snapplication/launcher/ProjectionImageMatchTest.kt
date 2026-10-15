package app.snapplication.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Exercises the exact pixel matcher used before the visual projection action can tap.
 * Synthetic dialog-like grids contain text and button detail without storing a user's screen.
 * ProjectionImageRunner's physical capture and one-shot dispatch need additional device checks.
 * Add mismatching layouts and local button changes here when adjusting comparison tolerances.
 */
class ProjectionImageMatchTest {
    private fun reference() = IntArray(ProjectionImageMatch.WIDTH * ProjectionImageMatch.HEIGHT) { i ->
        val x = i % ProjectionImageMatch.WIDTH; val y = i / ProjectionImageMatch.WIDTH
        if (x in 30..280 && y in 35..145 && x % 13 < 3 && y % 17 < 6) 0xffeeeeee.toInt() else 0xff202020.toInt()
    }

    @Test fun identicalDetailedReferenceMatches() { val a = reference(); assertTrue(ProjectionImageMatch.matches(a, a)) }
    @Test fun smallCompressionNoiseMatches() {
        val a = reference(); val b = a.mapIndexed { i, c -> if (i % 3 == 0) c + 0x020202 else c }.toIntArray()
        assertTrue(ProjectionImageMatch.matches(a, b))
    }
    @Test fun blackCaptureIsRejected() { assertFalse(ProjectionImageMatch.matches(reference(), IntArray(reference().size))) }
    @Test fun flatReferenceIsRejected() { val a = IntArray(reference().size) { 0xff202020.toInt() }; assertFalse(ProjectionImageMatch.matches(a, a)) }
    @Test fun wrongDimensionsAreRejected() { assertFalse(ProjectionImageMatch.matches(reference(), IntArray(100))) }
    @Test fun differentThemeIsRejected() { val a = reference(); assertFalse(ProjectionImageMatch.matches(a, a.map { it xor 0xffffff }.toIntArray())) }
    @Test fun alteredButtonRegionIsRejected() {
        val a = reference(); val b = a.clone()
        for (y in 130..151) for (x in 240..279) b[y * ProjectionImageMatch.WIDTH + x] = 0xffffffff.toInt()
        assertFalse(ProjectionImageMatch.matches(a, b))
    }
    @Test fun movedDialogIsRejected() {
        val a = reference(); val b = IntArray(a.size) { i -> a[(i + 10) % a.size] }
        assertFalse(ProjectionImageMatch.matches(a, b))
    }
    @Test fun missingSmallTextRegionIsRejected() {
        val a = reference(); val b = a.clone()
        for (y in 132..153) for (x in 240..279) b[y * ProjectionImageMatch.WIDTH + x] = 0xff202020.toInt()
        assertFalse(ProjectionImageMatch.matches(a, b))
    }
}
