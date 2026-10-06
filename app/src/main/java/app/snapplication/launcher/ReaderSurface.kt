package app.snapplication.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo

/** Finds visible document pages for RemoteActionService; rejects generic web views and library grids.
 * PDF, EPUB and comics belong to their reader app: this inspects its displayed page, never its files. */
object ReaderSurface {
    private val documentHandlers = mutableMapOf<String, Pair<Long, Boolean>>()
    private val pageIds = setOf("pdfview", "pdf_view", "pdf_reader", "epub_view", "epub_reader",
        "reader_view", "readerview", "page_view", "pageview", "document_view", "documentview",
        "reading_view", "book_view", "reader_pager", "reader_container")
    private val readerActivities = setOf("readeractivity", "readactivity", "readingactivity",
        "bookreaderactivity", "pdfactivity", "pdfvieweractivity", "epubreaderactivity", "documentactivity", "activitytxt")

    /** Uses substantial named page surfaces first, then a reader activity with its controls hidden. */
    fun find(context: Context, root: AccessibilityNodeInfo, activity: String?): Rect? {
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        if (bounds.isEmpty) return null
        val reader = activity?.substringAfterLast('.')?.lowercase() in readerActivities
        // A generic page_view name also occurs in shopping and gallery apps. Require a reader
        // activity or a declared document handler before treating such a surface as a book.
        if (!reader && !handlesDocuments(context, root.packageName?.toString().orEmpty())) return null
        val minimum = bounds.width().toLong() * bounds.height() / 3
        scan(root, minimum, 0)?.let { return it }
        return bounds.takeIf { reader && !hasControls(root, 0) }
    }

    /** Checks public document-opening capabilities without opening files or enumerating their contents. */
    private fun handlesDocuments(context: Context, owner: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        documentHandlers[owner]?.takeIf { now - it.first < 300_000 }?.let { return it.second }
        val supported = listOf("application/pdf", "application/epub+zip", "application/vnd.comicbook+zip",
            "application/x-cbz").any { mime ->
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://app.snapplication.document/sample"), mime).setPackage(owner)
            runCatching { context.packageManager.queryIntentActivities(intent, 0).isNotEmpty() }.getOrDefault(false)
        }
        documentHandlers[owner] = now to supported
        return supported
    }

    /** A page-sized, explicit reader widget is evidence; thumbnails, lists and arbitrary WebViews are not. */
    private fun scan(node: AccessibilityNodeInfo, minimum: Long, depth: Int): Rect? {
        if (depth > 30 || !node.isVisibleToUser) return null
        val id = node.viewIdResourceName.orEmpty().substringAfterLast('/').lowercase()
        val kind = node.className?.toString().orEmpty().substringAfterLast('.').lowercase()
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if ((id in pageIds || kind in setOf("pdfview", "readerview", "documentview", "pageview", "zlandroidwidget")) &&
            bounds.width().toLong() * bounds.height() >= minimum && !hasControls(node, 0)) return bounds
        for (index in 0 until node.childCount.coerceAtMost(150)) {
            val child = node.getChild(index) ?: continue
            val found = try { scan(child, minimum, depth + 1) } finally { child.recycle() }
            if (found != null) return found
        }
        return null
    }

    /** Keeps the previous page when menus, search, page sliders or navigation buttons cover the page. */
    private fun hasControls(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 30) return true
        if (!node.isVisibleToUser) return false
        val kind = node.className?.toString().orEmpty()
        if (node.isEditable || kind.endsWith("Button") || kind.endsWith("SeekBar") ||
            kind.endsWith("Spinner") || (node.isClickable && kind.endsWith("ImageView"))) return true
        for (index in 0 until node.childCount.coerceAtMost(150)) {
            val child = node.getChild(index) ?: continue
            val controls = try { hasControls(child, depth + 1) } finally { child.recycle() }
            if (controls) return true
        }
        return false
    }
}
