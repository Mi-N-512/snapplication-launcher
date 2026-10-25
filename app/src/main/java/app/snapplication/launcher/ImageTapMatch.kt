package app.snapplication.launcher

/*
Compares screenshot-authored macro references without Android dependencies. ImageTapAction samples
the image; ProjectionImageRunner requests fresh frames and dispatches once. No UI here. Adjust
similarity limits with rejection tests, including local button changes.

This object is a shared helper and does not inherit from another launcher class.

The two pixel arrays hold equally sized samples from the reference and current screen. matches
compares them and returns whether their differences are within the allowed limits. ImageTapAction
creates the samples and ProjectionImageRunner uses the answer. This shared helper has no screen
permission or changing state.
*/
internal object ImageTapMatch {
    // Rejects flat references and local mismatches rather than matching only an average background
    // colour.
    fun matches(a: IntArray, b: IntArray): Boolean {
        if (a.size != 6144 || a.size != b.size) return false
        val errors = LongArray(64); val counts = IntArray(64)
        var min = 255; var max = 0
        for (i in a.indices) {
            val gray = ((a[i] shr 16 and 255) + (a[i] shr 8 and 255) + (a[i] and 255)) / 3
            min = minOf(min, gray); max = maxOf(max, gray)
            val cell = (i / 96 / 8) * 8 + (i % 96 / 12)
            errors[cell] += (kotlin.math.abs((a[i] shr 16 and 255) - (b[i] shr 16 and 255)) +
                kotlin.math.abs((a[i] shr 8 and 255) - (b[i] shr 8 and 255)) + kotlin.math.abs((a[i] and 255) - (b[i] and 255))) / 3
            counts[cell]++
        }
        return max - min >= 40 && errors.sum().toDouble() / a.size <= 8 &&
            errors.indices.all { errors[it].toDouble() / counts[it] <= 15 }
    }
}
