package app.snapplication.launcher

import org.junit.Assert.*
import org.junit.Test

/*
Checks file routing at the boundary between user-granted folders and reading apps. Used by or used
with: ReadingFormats, LocalLibrary, MainActivity and ReaderPanel. Screen controls: No test UI;
protects Library recognition and Open book routing. Edit here when adding formats, especially when
external formats resemble ZIPs or images.
*/
class ReadingFormatsTest {
    // Exercises the decoder selector consumed by ReaderPanel, including providers without useful
    // filenames/MIME.
    @Test fun decoderMatchesGenericAndMimeOnlyFiles() {
        assertEquals(ReadingFormats.InlineKind.IMAGE, ReadingFormats.inlineKind("page.PNG", "application/octet-stream"))
        assertEquals(ReadingFormats.InlineKind.COMIC, ReadingFormats.inlineKind("opaque-id", "application/x-cbz"))
        assertEquals(ReadingFormats.InlineKind.PDF, ReadingFormats.inlineKind("opaque-id", "application/pdf"))
        assertEquals(ReadingFormats.InlineKind.EPUB, ReadingFormats.inlineKind("book.epub", "application/zip"))
        assertNull(ReadingFormats.inlineKind("backup.zip", "application/zip"))
    }
    // Generic document providers must still expose uppercase EPUB and comic filenames correctly.
    @Test fun genericProviderTypesResolveFromBookNames() {
        assertEquals("application/epub+zip", ReadingFormats.mime("Book.EPUB", "application/octet-stream"))
        assertEquals("application/vnd.comicbook+zip", ReadingFormats.mime("Chapter.CBZ", "application/zip"))
        assertTrue(ReadingFormats.accepts("Book.FB2", "application/octet-stream"))
    }

    // Archive-shaped book formats require an external parser instead of the CBZ image renderer.
    @Test fun externalFormatsNeverReachInlineDecoder() {
        for (name in listOf("book.cbr", "book.cb7", "book.cbt", "book.mobi", "book.azw3",
            "book.fb2", "book.djvu", "book.docx", "book.odt", "book.txt", "book.rtf")) {
            assertTrue(name, ReadingFormats.accepts(name, "application/octet-stream"))
            assertFalse(name, ReadingFormats.canRenderInline(name, "application/octet-stream"))
        }
        assertFalse(ReadingFormats.canRenderInline("book", "image/vnd.djvu"))
    }

    // Ordinary archive backups must not clutter the book list or be interpreted as comic pages.
    @Test fun arbitraryArchivesAreNotBooks() {
        assertFalse(ReadingFormats.accepts("backup.zip", "application/zip"))
        assertFalse(ReadingFormats.accepts("backup.rar", "application/vnd.rar"))
    }

    // Preserve the already supported built-in formats and MIME-only file provider entries.
    @Test fun supportedInlineFormatsRemainAvailable() {
        assertTrue(ReadingFormats.canRenderInline("book.pdf", "application/octet-stream"))
        assertTrue(ReadingFormats.canRenderInline("chapter.cbz", "application/zip"))
        assertTrue(ReadingFormats.canRenderInline("page.png", "image/png"))
        assertTrue(ReadingFormats.accepts("opaque-id", "application/epub+zip"))
        assertTrue(ReadingFormats.canRenderInline("Book.EPUB", "application/octet-stream"))
    }
}
