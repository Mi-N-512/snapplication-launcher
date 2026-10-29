package app.snapplication.launcher

import java.net.URLDecoder

/*
Reads an EPUB's table of contents order so EpubPanel can show its chapters inside Home. An EPUB is
a ZIP of web pages: META-INF/container.xml names the package (.opf) file, whose manifest lists
every file and whose spine gives the reading order.

This class is a plain data holder and does not inherit from another launcher class.

title is the book's own title when it has one. chapters holds the ZIP paths of the spine pages in
reading order. mediaTypes maps every manifest path to its declared type so EpubPanel can serve
images and styles with the right type. parse reads these from a function that returns a ZIP entry's
bytes, so EpubBookTest can check it without a device.

Used by or used with: EpubPanel opens the ZIP and calls parse. Screen controls: none. Changes
belong here: EPUB packaging rules; rendering and page turning stay in EpubPanel.
*/
class EpubBook(val title: String?, val chapters: List<String>, val mediaTypes: Map<String, String>) {
    companion object {
        // Follows container.xml to the package file and returns its spine; throws on a broken book.
        fun parse(entry: (String) -> ByteArray?): EpubBook {
            val container = xml(requireNotNull(entry("META-INF/container.xml")) { "Not an EPUB: container.xml missing" })
            val opfPath = container.getElementsByTagNameNS("*", "rootfile").let { nodes ->
                (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("full-path") }
                    .firstOrNull { it.isNotBlank() }
            } ?: throw IllegalArgumentException("Not an EPUB: no package file")
            val opf = xml(requireNotNull(entry(opfPath)) { "Not an EPUB: package file missing" })
            val base = opfPath.substringBeforeLast('/', "")
            val manifest = mutableMapOf<String, String>()
            val types = mutableMapOf<String, String>()
            opf.getElementsByTagNameNS("*", "item").let { nodes ->
                for (index in 0 until nodes.length) {
                    val item = nodes.item(index) as org.w3c.dom.Element
                    val path = resolve(base, item.getAttribute("href"))
                    manifest[item.getAttribute("id")] = path
                    types[path] = item.getAttribute("media-type")
                }
            }
            val chapters = opf.getElementsByTagNameNS("*", "itemref").let { nodes ->
                (0 until nodes.length).mapNotNull { manifest[(nodes.item(it) as org.w3c.dom.Element).getAttribute("idref")] }
            }
            require(chapters.isNotEmpty()) { "This EPUB has no readable chapters" }
            val title = opf.getElementsByTagNameNS("*", "title").let { if (it.length > 0) it.item(0).textContent.trim().ifBlank { null } else null }
            return EpubBook(title, chapters, types)
        }

        // Turns a manifest href into a ZIP path relative to the package file, decoding %20 etc.
        fun resolve(base: String, href: String): String {
            val parts = (if (base.isEmpty()) emptyList() else base.split('/')).toMutableList()
            URLDecoder.decode(href.substringBefore('#'), "UTF-8").split('/').forEach { part ->
                when (part) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts.add(part) }
            }
            return parts.joinToString("/")
        }

        // Parses with external entities and DTDs disabled so a book cannot read other files.
        private fun xml(bytes: ByteArray): org.w3c.dom.Document {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                isExpandEntityReferences = false
            }
            return factory.newDocumentBuilder().parse(bytes.inputStream())
        }
    }
}
