package app.snapplication.launcher

import kotlin.math.abs

/*
 * Compares the interior of the user's Wired Projection screenshot with a fresh screen sample.
 * ProjectionImageAction supplies equally sized RGB grids; ProjectionImageRunner owns consent,
 * display checks and the one-shot tap. This pure matcher has no screen access or Android UI.
 * Edit tolerances here with rejection tests; do not compensate for a different dialog or layout.
 */
internal object ProjectionImageMatch {
    const val WIDTH = 320
    const val HEIGHT = 176

    // Requires a detailed reference, close colours throughout, and no mismatching local text/button region.
    fun matches(reference: IntArray, current: IntArray): Boolean {
        if (reference.size != WIDTH * HEIGHT || current.size != reference.size) return false
        var total = 0L
        var details = 0
        var missedDetails = 0
        val cells = IntArray(8 * 8)
        val counts = IntArray(cells.size)
        val cellDetails = IntArray(cells.size)
        val cellMisses = IntArray(cells.size)
        for (i in reference.indices) {
            val a = reference[i]; val b = current[i]
            val r = abs((a shr 16 and 255) - (b shr 16 and 255))
            val g = abs((a shr 8 and 255) - (b shr 8 and 255))
            val blue = abs((a and 255) - (b and 255))
            val error = (r + g + blue) / 3
            total += error
            val cell = (i / WIDTH * 8 / HEIGHT) * 8 + (i % WIDTH * 8 / WIDTH)
            cells[cell] += error; counts[cell]++
            if (maxOf(a shr 16 and 255, a shr 8 and 255, a and 255) > 100) {
                details++
                cellDetails[cell]++
                if (maxOf(r, g, blue) > 60) { missedDetails++; cellMisses[cell]++ }
            }
        }
        if (details < reference.size / 40 || details > reference.size * 3 / 4) return false
        return total.toDouble() / reference.size <= 8.0 && missedDetails.toDouble() / details <= .12 &&
            cells.indices.all { cells[it].toDouble() / counts[it] <= 18.0 &&
                (cellDetails[it] < 4 || cellMisses[it].toDouble() / cellDetails[it] <= .20) }
    }
}
