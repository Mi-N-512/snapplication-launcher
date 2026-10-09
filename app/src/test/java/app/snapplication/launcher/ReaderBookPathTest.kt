package app.snapplication.launcher

import org.junit.Assert.*
import org.junit.Test

/* Checks the external book-path boundary shared by Meebook resume and its metadata provider. */
class ReaderBookPathTest {
    // Both observed storage types must resume without treating removable storage as internal.
    @Test fun acceptsInternalAndRemovableBooks() {
        assertTrue(ReaderBookPath.valid("/storage/emulated/0/Download/book.pdf"))
        assertTrue(ReaderBookPath.valid("/storage/7AFF-5C28/Download/book.epub"))
        assertTrue(ReaderBookPath.valid("/sdcard/Books/comic.cbz"))
    }
    // External-looking prefixes cannot admit private paths, traversal or malformed locations.
    @Test fun rejectsUnsafeOrIncompletePaths() {
        listOf("/data/data/reader/book", "/storage/7AFF-5C28/../private", "/sdcard//book",
            "/sdcard/", "/storage/emulated/10/book", "relative.epub", "/sdcard/a\u0000b",
            "/storage/emulated/0/./book", "/storage/anything/book").forEach {
            assertFalse(it, ReaderBookPath.valid(it))
        }
    }
}
