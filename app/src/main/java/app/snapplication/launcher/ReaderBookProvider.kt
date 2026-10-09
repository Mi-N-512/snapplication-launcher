package app.snapplication.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder

/*
 * Hands Meebook Read its own last captured book location through a temporary content URI.
 * Read's verified public opener queries the legacy _data column, then reads the book using
 * its own storage access. This provider never opens files or grants access to book contents.
 * Related: MeebookReaderBridge creates the URI; ReadingSnapshots commits it with the page;
 * ExternalPreview grants Read temporary metadata access when the user chooses Continue.
 * UI: None. Keep access limited to the verified vendor and the current saved page identity.
 */
class ReaderBookProvider : ContentProvider() {
    // Initialises no resources; the provider only exposes the current resume metadata.
    override fun onCreate() = true

    // Returns the saved path only to the verified reader, never a caller-supplied arbitrary path.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = requireNotNull(context)
        val vendor = ctx.packageManager.getApplicationInfo(MeebookReaderBridge.OWNER, 0)
        check(Binder.getCallingUid() == vendor.uid && MeebookReaderBridge.isSupported(ctx))
        val saved = ctx.getSharedPreferences("externalPreview", 0)
            .getString("resumeUri:Read:${MeebookReaderBridge.OWNER}", null)
        check(uri.toString() == saved)
        val path = bookPath(ctx.packageName, uri) ?: throw IllegalArgumentException("Invalid book identity")
        val columns = projection ?: arrayOf("_data")
        require(columns.all { it == "_data" })
        return MatrixCursor(columns).apply { addRow(columns.map { path }) }
    }

    // A generic type avoids exposing book metadata through unauthenticated MIME queries.
    override fun getType(uri: Uri) = "application/octet-stream"
    // Resume metadata is read-only; no caller can insert, change or delete it.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()

    companion object {
        // Builds a non-document URI for the vendor's verified _data lookup, including SD-card books.
        fun bookUri(applicationId: String, path: String): Uri? = if (!ReaderBookPath.valid(path)) null else
            Uri.Builder().scheme("content").authority("$applicationId.readerbook").appendPath("book").appendPath(path).build()

        // Validates the full URI shape before the bridge or provider accepts a stored identity.
        fun bookPath(applicationId: String, uri: Uri): String? {
            if (uri.scheme != "content" || uri.authority != "$applicationId.readerbook" ||
                uri.query != null || uri.fragment != null || uri.pathSegments.size != 2 || uri.pathSegments[0] != "book") return null
            return uri.pathSegments[1].takeIf(ReaderBookPath::valid)
        }
    }
}
