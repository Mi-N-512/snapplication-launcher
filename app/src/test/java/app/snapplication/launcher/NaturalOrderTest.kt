package app.snapplication.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Guards comic page and episode ordering used by ReaderPanel and LocalLibrary. */
class NaturalOrderTest {
    /** Unpadded file numbers must read in chapter order, not dictionary order. */
    @Test fun sortsComicPages() {
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), listOf("10.jpg", "2.jpg", "1.jpg").sortedWith(NaturalOrder))
    }

    /** Numeric chunks longer than machine integers must remain comparable without overflow. */
    @Test fun handlesLongNumbersAndFolders() {
        val first = "chapter2/page9999999999999999999999.png"
        val second = "chapter10/page1.png"
        assertEquals(listOf(first, second), listOf(second, first).sortedWith(NaturalOrder))
    }
}
