package app.snapplication.launcher

/*
Validates the external book location reported by Meebook Read, including removable cards.
MeebookReaderBridge and ReaderBookProvider share this check before returning a path to Read. No UI
or file access lives here. Extend accepted locations only after verifying the vendor contract;
private app files and path traversal must never become resume targets.

This object is a shared helper and does not inherit from another launcher class.

path is the book location reported by the supported reader. valid checks its storage location and
rejects private paths or attempts to escape the allowed location. MeebookReaderBridge and
ReaderBookProvider call this shared check before returning a book identity. It does not open the
file or keep state between calls.
*/
object ReaderBookPath {
    // Accepts a complete external-storage file path without normalising traversal segments.
    fun valid(path: String): Boolean {
        if (path.length > 4096 || path.any { it == '\u0000' || it == '\n' || it == '\r' }) return false
        val parts = path.split('/')
        if (parts.drop(1).any { it.isBlank() || it == "." || it == ".." }) return false
        return (path.startsWith("/sdcard/") && parts.size >= 3) ||
            (path.startsWith("/storage/emulated/0/") && parts.size >= 5) ||
            (parts.size >= 4 && parts[0].isEmpty() && parts[1] == "storage" &&
                parts[2].matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")))
    }
}
