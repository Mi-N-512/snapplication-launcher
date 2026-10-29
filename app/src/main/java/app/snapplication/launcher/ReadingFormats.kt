package app.snapplication.launcher

/*
Identifies book files offered by a user-selected folder and distinguishes the formats our small
reader can draw from formats that need another installed reading application. Used by or used with:
LocalLibrary lists recognised files; MainActivity chooses an external viewer or ReaderPanel.
LibraryItem retains the source URI and document name. Screen controls: No controls; this determines
which books appear in Library and how Open book routes. Changes belong here: Add a format with its
real MIME type here; adding recognition does not add a parser or guarantee that the device has an
application which can read that format.

This object is a shared helper and does not inherit from another launcher class.

types maps file extensions to document types and images lists recognised picture extensions. accepts
decides whether a file belongs in the reading library; inlineKind selects a built-in decoder;
viewingLabel explains the actual opening route. LocalLibrary uses these checks when listing files,
and MainActivity and ReaderPanel use them when opening a selection.
*/
object ReadingFormats {
    private val types = mapOf(
        "pdf" to "application/pdf", "epub" to "application/epub+zip",
        "cbz" to "application/vnd.comicbook+zip", "cbr" to "application/vnd.comicbook-rar",
        "cb7" to "application/x-cb7", "cbt" to "application/x-cbt",
        "fb2" to "application/x-fictionbook+xml", "mobi" to "application/x-mobipocket-ebook",
        "azw" to "application/vnd.amazon.ebook", "azw3" to "application/vnd.amazon.ebook",
        "djvu" to "image/vnd.djvu", "djv" to "image/vnd.djvu",
        "txt" to "text/plain", "rtf" to "application/rtf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "odt" to "application/vnd.oasis.opendocument.text", "html" to "text/html", "htm" to "text/html"
    )
    private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    // Resolves common generic provider MIME types from the filename, without opening the file.
    fun mime(name: String, supplied: String): String {
        val ext = extension(name)
        return types[ext] ?: if (ext in images) "image/${if (ext == "jpg") "jpeg" else ext}" else supplied
    }

    // Excludes arbitrary ZIP/RAR archives: only explicitly named comic archives belong in Library.
    fun accepts(name: String, supplied: String): Boolean = extension(name) in types ||
        extension(name) in images || supplied in types.values || supplied == "application/x-cbz" || supplied.startsWith("image/")

    // Explains the actual route without claiming a parser exists for every recognised format.
    fun viewingLabel(name: String, supplied: String, inlineEnabled: Boolean): String =
        if (canRenderInline(name, supplied)) {
            if (inlineEnabled) "Opens inside launcher" else "Opens in a reading app · built-in viewing available"
        } else "Requires a compatible reading app"

    // EPUB has its own panel; other document formats must never fall through to the CBZ decoder.
    fun canRenderInline(name: String, supplied: String): Boolean {
        return inlineKind(name, supplied) != null
    }

    // Shares the exact decoder choice with ReaderPanel so generic provider MIME types still work.
    fun inlineKind(name: String, supplied: String): InlineKind? {
        val resolved = mime(name, supplied)
        return when {
            resolved == "application/pdf" -> InlineKind.PDF
            resolved == "application/epub+zip" -> InlineKind.EPUB
            resolved in setOf("application/vnd.comicbook+zip", "application/x-cbz") -> InlineKind.COMIC
            extension(name) in images || (resolved.startsWith("image/") && resolved != "image/vnd.djvu") -> InlineKind.IMAGE
            else -> null
        }
    }

    /*
    Decoder choices shared by file routing, ReaderPanel and EpubPanel; additional parsers belong here
    only once implemented.
    */
    enum class InlineKind { PDF, COMIC, IMAGE, EPUB }

    // Uses locale-independent matching for names received from Android document providers.
    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
}
