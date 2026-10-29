package app.snapplication.launcher

import org.junit.Assert.*
import org.junit.Test

/*
Regression checks for EpubBook; run by the local Gradle unit-test tasks. Related code: EpubBook and
EpubPanel. No UI or Android device is created here; the books are small in-memory file maps. Add
packaging cases here when EPUBs from a new source fail to open.
*/
class EpubBookTest {
    private val container = """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
        <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    private val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Alice</dc:title></metadata>
        <manifest><item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/>
        <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
        <item id="img" href="../images/rabbit.png" media-type="image/png"/></manifest>
        <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""

    // The spine decides the order, hrefs resolve against the package folder, and the title is read.
    @Test fun readsSpineInOrder() {
        val files = mapOf("META-INF/container.xml" to container, "OEBPS/content.opf" to opf)
        val book = EpubBook.parse { files[it]?.toByteArray() }
        assertEquals("Alice", book.title)
        assertEquals(listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch 2.xhtml"), book.chapters)
        assertEquals("image/png", book.mediaTypes["images/rabbit.png"])
    }

    // Relative paths, fragments and parent folders resolve to ZIP entry names.
    @Test fun resolvesRelativePaths() {
        assertEquals("OEBPS/a.xhtml", EpubBook.resolve("OEBPS", "./a.xhtml#top"))
        assertEquals("img/x.png", EpubBook.resolve("OEBPS", "../img/x.png"))
        assertEquals("a b.xhtml", EpubBook.resolve("", "a%20b.xhtml"))
    }

    // A ZIP that is not an EPUB is rejected instead of showing a blank page.
    @Test fun rejectsNonEpub() {
        assertThrows(IllegalArgumentException::class.java) { EpubBook.parse { null } }
    }

    // A document type declaration is refused so a book cannot pull in outside files.
    @Test fun refusesDoctype() {
        val evil = """<?xml version="1.0"?><!DOCTYPE c [<!ENTITY x SYSTEM "file:///etc/hosts">]><container>&x;</container>"""
        assertThrows(Exception::class.java) { EpubBook.parse { if (it == "META-INF/container.xml") evil.toByteArray() else null } }
    }
}
