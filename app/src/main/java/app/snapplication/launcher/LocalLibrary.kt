package app.snapplication.launcher

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.ArrayDeque

/*
Reads only user-granted document trees; MainActivity runs scans off the UI thread.

This class is a helper and does not inherit from another launcher class.

context provides the document access that the user granted. scan walks the chosen folder, checks
each file for the requested mode, and returns LibraryItem records in NaturalOrder. MainActivity runs
this work away from the screen thread so the launcher remains responsive. This helper supplies data
to the activity; it is not a child of MainActivity.

Used by or used with: MainActivity runs scans; LibraryItem carries results; NaturalOrder sorts
titles. Screen controls: none; feeds the local folder/content list. Changes belong here: Add file
recognition here only with a reader/player or external-open path. Access stays inside user-granted
document folders.
*/
class LocalLibrary(private val context: Context) {
    // Enumerates supported content recursively, bounding traversal to avoid pathological trees.
    fun scan(tree: Uri, mode: String): List<LibraryItem> {
        val queue = ArrayDeque<String>()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<LibraryItem>()
        queue.add(DocumentsContract.getTreeDocumentId(tree))
        while (queue.isNotEmpty() && seen.size < 2000 && result.size < 10000) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            context.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val child = cursor.getString(0)
                    val name = cursor.getString(1) ?: "Untitled"
                    val mime = cursor.getString(2) ?: "application/octet-stream"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(child)
                    else if (accepts(name, mime, mode)) result.add(LibraryItem(
                        DocumentsContract.buildDocumentUriUsingTree(tree, child).toString(), name, mime, mode
                    ))
                }
            }
        }
        return result.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
    }

    // Classifies files without requesting broad storage access or reading other apps' private
    // files.
    private fun accepts(name: String, mime: String, mode: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return if (mode == "Watch") mime.startsWith("video/") || extension in setOf("mp4", "mkv", "webm", "m4v", "mov")
        else ReadingFormats.accepts(name, mime)
    }
}
