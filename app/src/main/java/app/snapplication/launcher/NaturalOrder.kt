package app.snapplication.launcher

/*
Compares numeric filename chunks for ReaderPanel and LocalLibrary without integer overflow.

This object is a shared helper and does not inherit from another launcher class.

compare receives two file names. It compares text normally and compares runs of digits by their
numeric size without converting huge numbers to integers. LocalLibrary uses it for file order and
ReaderPanel uses it for comic page order. Comparator<String> is an interface contract: this object
supplies its compare function rather than inheriting a user interface.

Used by or used with: LocalLibrary and ReaderPanel use the comparator; NaturalOrderTest covers
numeric filenames. Screen controls: none; controls the order of file and page names. Changes belong
here: Edit numeric/text comparison rules here and add boundary tests so large chapter numbers remain
correctly ordered.
*/
object NaturalOrder : Comparator<String> {
    // Sorts chapter2 before chapter10 while using text comparison for nonnumeric chunks.
    override fun compare(a: String, b: String): Int {
        val chunks = Regex("[0-9]+|[^0-9]+")
        val left = chunks.findAll(a).map { it.value }.toList()
        val right = chunks.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]; val y = right[i]
            val comparison = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0').ifEmpty { "0" }
                val ny = y.trimStart('0').ifEmpty { "0" }
                nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
            } else x.compareTo(y, ignoreCase = true)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
