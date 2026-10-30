package app.snapplication.launcher

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/*
Shows EPUB books inside Home, a page at a time, so reading continues without another app.
MainActivity creates this panel instead of ReaderPanel when the saved item is an EPUB.

This class is a helper and does not inherit from another launcher class.

The EPUB is copied once into the cache (Android's file picker gives a stream, ZipFile needs a
file). book lists its chapters (EpubBook). page shows one chapter laid out in CSS columns exactly
one screen wide, so scrolling sideways by the view width shows the next page. chapter and pageIndex
are the reading position. Text reflows to a different page count on Home and full screen, so the
position is saved through LauncherState as chapter * 10000 plus how far through the chapter the
page is (0 to 9999), and reopens on the page holding that point in any layout. Chapter files,
styles and pictures are served from the ZIP under https://epub.local/; every other address gets an
empty answer, and JavaScript, file and network access are off, so a book cannot run code or reach
the internet.

Page turning matches ReaderPanel: tap the left third to go back and elsewhere to go forward, swipe
sideways, or press page/remote keys (PageTurn decides). With LauncherState.tapViewer on, the title
line and Previous/Next buttons are hidden and pageBadge briefly shows the position instead.
progressLine shows how far through the whole book you are.

Used by or used with: MainActivity creates it; EpubBook reads the package; PageTurn maps input;
LauncherState stores progress. Screen controls: embedded EPUB pages. Changes belong here: EPUB
display, pagination and its page-turn input.
*/
class EpubPanel(private val context: Context, private val state: LauncherState, private val ui: LauncherUi) {
    val view = ui.column()
    private val page = PageWebView(context)
    private val status = ui.label("Choose a book", 16f).apply { gravity = android.view.Gravity.CENTER }
    private val tapOnly = state.tapViewer
    private val progressLine = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(ui.focusAccent)
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = if (state.showProgress) android.view.View.VISIBLE else android.view.View.GONE
    }
    private val pageBadge = ui.label("", 14f).apply {
        setTextColor(Color.WHITE); setBackgroundColor(0xAA101010.toInt())
        setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); visibility = android.view.View.GONE
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val hideBadge = Runnable { pageBadge.visibility = android.view.View.GONE }
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var item: LibraryItem? = null
    private var zip: ZipFile? = null
    private var cachedFile: File? = null
    private var book: EpubBook? = null
    private var chapter = 0
    private var pageIndex = 0
    // After a chapter loads, go to this page; -1 means its last page (when turning backwards).
    private var pendingPage = 0
    // A saved point in the chapter (0 to 1) to reopen at; it wins over pendingPage until a page turns.
    private var pendingFraction: Double? = null
    private var loadGeneration = 0
    private var ready = false
    private var tapX = Float.MAX_VALUE

    init {
        val holder = FrameLayout(context)
        holder.addView(page, FrameLayout.LayoutParams(-1, -1))
        holder.addView(pageBadge, FrameLayout.LayoutParams(-2, -2,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(12) })
        holder.addView(progressLine, FrameLayout.LayoutParams(-1, ui.dp(3), android.view.Gravity.BOTTOM))
        view.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!tapOnly) view.addView(status)
        val controls = ui.column(true)
        ui.addWeighted(controls, ui.button("Previous") { move(-1) })
        ui.addWeighted(controls, ui.button("Next") { move(1) })
        if (state.readInLauncher && state.showReaderControls && !tapOnly) view.addView(controls)
        page.setBackgroundColor(0xFFFAF7F0.toInt())
        page.settings.apply {
            javaScriptEnabled = false; allowFileAccess = false; allowContentAccess = false
            blockNetworkImage = false; builtInZoomControls = false; setSupportZoom(false)
        }
        page.isVerticalScrollBarEnabled = false; page.isHorizontalScrollBarEnabled = false
        page.webViewClient = object : WebViewClient() {
            // Serves only files from inside the book; anything else gets an empty answer.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                serve(request.url) ?: WebResourceResponse("text/plain", "utf-8", 404, "Not in book", null, "".byteInputStream())
            // Links inside the book jump to that chapter; links out of the book are ignored.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = bookPath(request.url)
                book?.chapters?.indexOf(target)?.takeIf { it >= 0 }?.let { showChapter(it, 0) }
                return true
            }
            // Measures the new chapter's pages once Chromium has laid out its columns.
            override fun onPageFinished(view: WebView, url: String) {
                val load = ++loadGeneration
                page.postDelayed({ if (!closed && load == loadGeneration) settle(load, 0, -2) }, 120)
            }
        }
        attachPageGestures()
    }

    // Loads a book at its saved chapter and page; the copy and parsing run off the screen thread.
    fun load(selection: LibraryItem) {
        item = selection; ready = false
        state.remember(selection)
        val saved = state.progress(selection.uri)
        status.text = String.format(java.util.Locale.getDefault(), "Loading %s…", selection.title)
        worker.execute {
            val result = runCatching {
                val temp = File.createTempFile("epub-", ".zip", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(Uri.parse(selection.uri))!!.use { input ->
                        temp.outputStream().use { out ->
                            val buffer = ByteArray(65536); var total = 0L
                            while (true) {
                                check(!closed) { "Reader closed" }
                                val n = input.read(buffer); if (n < 0) break
                                total += n; require(total <= 512L * 1024 * 1024) { "Book exceeds 512 MB" }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    val opened = ZipFile(temp)
                    val parsed = runCatching { EpubBook.parse { name -> opened.getEntry(name)?.let { e -> opened.getInputStream(e).use { it.readBytes() } } } }
                        .onFailure { opened.close() }.getOrThrow()
                    Triple(temp, opened, parsed)
                } catch (error: Exception) { temp.delete(); throw error }
            }
            view.post {
                result.onSuccess { (file, opened, parsed) ->
                    if (closed) { opened.close(); file.delete(); return@onSuccess }
                    cachedFile = file; zip = opened; book = parsed
                    pendingFraction = (saved % 10000) / 10000.0
                    showChapter((saved / 10000).toInt().coerceIn(0, parsed.chapters.lastIndex), 0, keepFraction = true)
                }.onFailure { status.text = OPEN_FAILED }
            }
        }
    }

    // Moves one page; at a chapter's edge it opens the next or previous chapter.
    fun move(delta: Int) {
        val current = book ?: return
        if (!ready) return
        val pages = pageCount()
        pendingPage = pageIndex; pendingFraction = null
        when {
            delta > 0 && pageIndex < pages - 1 -> { pageIndex++; pendingPage = pageIndex; scrollToPage() }
            delta > 0 && chapter < current.chapters.lastIndex -> showChapter(chapter + 1, 0)
            delta < 0 && pageIndex > 0 -> { pageIndex--; pendingPage = pageIndex; scrollToPage() }
            delta < 0 && chapter > 0 -> showChapter(chapter - 1, -1)
        }
    }

    // No picture scaling applies to reflowed text; kept so MainActivity can treat both readers alike.
    fun applyContentScale() = Unit

    private fun showChapter(index: Int, startPage: Int, keepFraction: Boolean = false) {
        val current = book ?: return
        ready = false; chapter = index; pendingPage = startPage
        if (!keepFraction) pendingFraction = null
        page.loadUrl("https://epub.local/" + Uri.encode(current.chapters[index], "/"))
    }

    // Applies the pending page once the chapter's width is known, then records the position.
    // Pictures can finish loading after the text and add pages, so the count is checked again a
    // few times until it stops changing.
    private fun settle(load: Int, attempt: Int, lastCount: Int) {
        if (closed || load != loadGeneration) return
        val pages = pageCount()
        val fraction = pendingFraction
        pageIndex = if (fraction != null) (fraction * pages).toInt().coerceIn(0, pages - 1)
            else if (pendingPage < 0) pages - 1 else pendingPage.coerceIn(0, pages - 1)
        ready = true
        scrollToPage()
        if (pages != lastCount && attempt < 6) page.postDelayed({ settle(load, attempt + 1, pages) }, 250)
    }

    private fun pageCount(): Int = if (page.width <= 0) 1 else
        ((page.contentRange() + page.width - 1) / page.width).coerceAtLeast(1)

    private fun scrollToPage() {
        page.scrollTo(pageIndex * page.width, 0)
        val current = book ?: return
        val selection = item ?: return
        val pages = pageCount()
        // The middle of the page, so the same page comes back when the page count is unchanged.
        state.saveProgress(selection.uri, chapter * 10000L + ((pageIndex + 0.5) * 10000 / pages).toLong().coerceIn(0, 9999))
        val label = "${current.title ?: selection.title} · chapter ${chapter + 1} of ${current.chapters.size} · page ${pageIndex + 1} of $pages"
        status.text = label
        page.contentDescription = label
        progressLine.progress = (((chapter + (pageIndex + 1f) / pages) / current.chapters.size) * 1000).toInt()
        if (tapOnly) {
            pageBadge.text = String.format(java.util.Locale.getDefault(), "%d · %d / %d", chapter + 1, pageIndex + 1, pages)
            pageBadge.visibility = android.view.View.VISIBLE
            pageBadge.removeCallbacks(hideBadge); pageBadge.postDelayed(hideBadge, 1500)
        }
    }

    // Returns the ZIP path for an address inside the book, or null for anything else.
    private fun bookPath(url: Uri): String? =
        if (url.scheme == "https" && url.host == "epub.local") url.path.orEmpty().removePrefix("/") else null

    // Serves chapter pages with the page layout added; other book files are served unchanged.
    private fun serve(url: Uri): WebResourceResponse? {
        val path = bookPath(url) ?: return null
        val archive = zip ?: return null
        if (path == "__snap.css") return WebResourceResponse("text/css", "utf-8", PAGE_CSS.byteInputStream())
        val entry = archive.getEntry(path) ?: return null
        val type = book?.mediaTypes?.get(path) ?: "application/octet-stream"
        val bytes = archive.getInputStream(entry).use { it.readBytes() }
        if (path !in book?.chapters.orEmpty()) return WebResourceResponse(type, null, bytes.inputStream())
        val html = String(bytes, Charsets.UTF_8)
        val link = "<link rel=\"stylesheet\" href=\"https://epub.local/__snap.css\"/>"
        val styled = Regex("</head>", RegexOption.IGNORE_CASE).find(html)?.let { html.replaceRange(it.range, "$link</head>") } ?: (link + html)
        return WebResourceResponse("text/html", "utf-8", styled.byteInputStream())
    }

    // Lets the page itself turn pages by tap, sideways swipe, keys and screen-reader scroll actions.
    private fun attachPageGestures() {
        val minSwipe = ui.dp(48).toFloat()
        page.gestures = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            // Claims the gesture so taps and flings on the page are reported.
            override fun onDown(e: android.view.MotionEvent) = true
            // Records a completed tap; PageWebView turns it into a real click.
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { tapX = e.x; page.tapPending = true; return true }
            // A sideways fling turns one page; vertical flings are left to the drawer gesture.
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val direction = PageTurn.swipe(e2.x - start.x, e2.y - start.y, minSwipe)
                if (direction != 0) move(direction)
                return direction != 0
            }
        })
        page.isFocusable = true
        page.setOnClickListener {
            val direction = if (tapX == Float.MAX_VALUE) 1 else PageTurn.tap(tapX, page.width)
            tapX = Float.MAX_VALUE
            if (direction != 0) move(direction)
        }
        page.setOnKeyListener { _, keyCode, event ->
            // In landscape the favourites sit beside the page, so Left/Right keep moving focus.
            val sideways = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val direction = if (sideways && landscape) 0 else PageTurn.key(keyCode)
            if (direction != 0 && event.action == android.view.KeyEvent.ACTION_DOWN) move(direction)
            direction != 0
        }
        page.accessibilityDelegate = object : android.view.View.AccessibilityDelegate() {
            // Offers next/previous page as scroll actions to TalkBack and switch access.
            override fun onInitializeAccessibilityNodeInfo(host: android.view.View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            }
            // Turns the page for screen-reader scroll gestures.
            override fun performAccessibilityAction(host: android.view.View, action: Int, args: android.os.Bundle?): Boolean = when (action) {
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { move(1); true }
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { move(-1); true }
                else -> super.performAccessibilityAction(host, action, args)
            }
        }
    }

    // Stops loading, closes the book and deletes the cached copy when Home is rebuilt.
    fun close() {
        closed = true
        pageBadge.removeCallbacks(hideBadge)
        page.stopLoading(); (page.parent as? android.view.ViewGroup)?.removeView(page); page.destroy()
        val archive = zip; val file = cachedFile
        zip = null; cachedFile = null
        worker.execute { runCatching { archive?.close() }; file?.delete() }
        worker.shutdown()
    }

    // Book page that hands touches to EpubPanel's gestures instead of scrolling freely.
    private class PageWebView(context: Context) : WebView(context) {
        var gestures: android.view.GestureDetector? = null
        var tapPending = false
        // Only the gesture detector sees touches, so the page never drifts between pages.
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            val handled = gestures?.onTouchEvent(event) == true
            if (tapPending) { tapPending = false; performClick() }
            return handled
        }
        // Kept explicit so accessibility clicks and touch taps share one path.
        override fun performClick(): Boolean = super.performClick()
        // Total laid-out width of the chapter's columns, in device pixels.
        fun contentRange(): Int = computeHorizontalScrollRange()
    }

    private companion object {
        const val OPEN_FAILED = "Cannot open this book. Check folder access or choose another file."
        // Lays each chapter out in columns exactly one screen wide, like pages of a printed book.
        const val PAGE_CSS = """
            html { height: 100%; margin: 0; padding: 0; background: #faf7f0; }
            body { height: 100vh; margin: 0; padding: 5vh 0; box-sizing: border-box;
                   column-width: 100vw; column-gap: 0; column-fill: auto;
                   color: #222; font-size: 112%; line-height: 1.55; overflow-wrap: break-word; }
            body > * { margin-left: 6vw !important; margin-right: 6vw !important; }
            img, svg { max-width: 88vw; max-height: 86vh; object-fit: contain; }
        """
    }
}
