package app.snapplication.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/*
Renders local PDFs, CBZ comics and images inside MainActivity; LauncherState stores the current
page.

This class is a helper and does not inherit from another launcher class.

item is the selected document; page and count track its current page and page count. bitmap holds
the displayed image. worker loads pages away from the screen thread, and generation prevents an
older load from replacing a newer page. cachedUri and cachedFile reuse the local copy needed by the
decoder. MainActivity creates this panel and calls load; move changes pages; close releases its
worker and image.

The page itself turns pages: tap the left third to go back and elsewhere to go forward, swipe
sideways, or press a remote, keyboard or page-turner key. PageTurn decides the direction. With
LauncherState.tapViewer on, the title line and Previous/Next buttons are hidden and pageBadge
briefly shows the page number after each turn instead. progressLine shows how far through the
document you are.

Used by or used with: MainActivity supplies LibraryItem content; LauncherState stores progress;
LauncherUi styles controls; PageTurn maps taps, swipes and keys. Screen controls: Embedded PDF, CBZ
and image pages with navigation and fit behaviour. Changes belong here: Add supported local
rendering formats here. EPUB currently opens an external reader; it is not rendered by this panel.
*/
class ReaderPanel(private val context: Context, private val state: LauncherState, private val ui: LauncherUi) {
    val view = ui.column()
    private val image = PageImageView(context)
    private val status = ui.label("Choose a PDF, comic or image", 16f).apply { gravity = android.view.Gravity.CENTER }
    private val worker = Executors.newSingleThreadExecutor()
    private var item: LibraryItem? = null
    private var page = 0
    private var count = 1
    private var generation = 0
    @Volatile private var closed = false
    private var bitmap: Bitmap? = null
    private var ready = false
    private var cachedUri: String? = null
    private var cachedFile: File? = null
    private val tapOnly = state.tapViewer
    private val pageBadge = ui.label("", 14f).apply {
        setTextColor(Color.WHITE); setBackgroundColor(0xAA101010.toInt())
        setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)); visibility = android.view.View.GONE
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val hideBadge = Runnable { pageBadge.visibility = android.view.View.GONE }
    private val progressLine = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(ui.focusAccent)
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = if (state.showProgress) android.view.View.VISIBLE else android.view.View.GONE
    }
    // Last touch position for the click that follows it; keyboard and accessibility clicks go
    // forward.
    private var tapX = Float.MAX_VALUE

    init {
        applyContentScale()
        val holder = android.widget.FrameLayout(context)
        holder.addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
        holder.addView(pageBadge, android.widget.FrameLayout.LayoutParams(-2, -2,
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(12) })
        holder.addView(progressLine, android.widget.FrameLayout.LayoutParams(-1, ui.dp(3), android.view.Gravity.BOTTOM))
        view.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!tapOnly) view.addView(status)
        val controls = ui.column(true)
        ui.addWeighted(controls, ui.button("Previous") { move(-1) })
        ui.addWeighted(controls, ui.button("Next") { move(1) })
        if (state.readInLauncher && state.showReaderControls && !tapOnly) view.addView(controls)
        attachPageGestures()
    }

    // Lets the page itself turn pages by tap, sideways swipe, keys and screen-reader scroll actions.
    private fun attachPageGestures() {
        val minSwipe = ui.dp(48).toFloat()
        val detector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            // Claims the gesture so taps and flings on the page are reported.
            override fun onDown(e: android.view.MotionEvent) = true
            // Records a completed tap; PageImageView turns it into a real click.
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean { tapX = e.x; image.tapPending = true; return true }
            // A sideways fling turns one page; vertical flings are left to the drawer gesture.
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val direction = PageTurn.swipe(e2.x - start.x, e2.y - start.y, minSwipe)
                if (direction != 0) move(direction)
                return direction != 0
            }
        })
        image.isFocusable = true
        image.isClickable = true
        image.gestures = detector
        image.setOnClickListener {
            val direction = if (tapX == Float.MAX_VALUE) 1 else PageTurn.tap(tapX, image.width)
            tapX = Float.MAX_VALUE
            if (direction != 0) move(direction)
        }
        image.setOnKeyListener { _, keyCode, event ->
            // In landscape the favourites sit beside the page, so Left/Right keep moving focus
            // there; page and media keys still turn pages.
            val sideways = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val direction = if (sideways && landscape) 0 else PageTurn.key(keyCode)
            if (direction != 0 && event.action == android.view.KeyEvent.ACTION_DOWN) move(direction)
            direction != 0
        }
        image.accessibilityDelegate = object : android.view.View.AccessibilityDelegate() {
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

    // Resizes the already-decoded page without copying the source or losing reading position.
    fun applyContentScale() {
        image.scaleType = when (state.contentScale) {
            "Fill" -> ImageView.ScaleType.CENTER_CROP
            "Stretch" -> ImageView.ScaleType.FIT_XY
            else -> ImageView.ScaleType.FIT_CENTER
        }
    }

    // Loads a selected document at its saved page and cancels stale display results.
    fun load(selection: LibraryItem) {
        ready = false
        item = selection; page = state.progress(selection.uri).toInt().coerceAtLeast(0)
        state.remember(selection); render()
    }

    // Moves within document bounds; every confirmed page change saves immediately.
    fun move(delta: Int) {
        if (!ready) return
        page = (page + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
        item?.let { state.saveProgress(it.uri, page.toLong()) }; render()
    }

    // Decodes off the UI thread and only publishes results belonging to the current selection.
    private fun render() {
        val selection = item ?: return
        val request = ++generation
        val requestedPage = page
        ready = false
        // Page turns keep the current title line; only the first load says it is loading.
        if (bitmap == null) status.text = "Loading ${selection.title}…"
        worker.execute {
            if (closed) return@execute
            val result = runCatching { decode(selection, requestedPage) }
            view.post {
                if (closed || request != generation) { result.getOrNull()?.first?.recycle(); return@post }
                result.onSuccess { (rendered, total) ->
                    count = total; page = requestedPage.coerceIn(0, total - 1)
                    ready = true
                    image.setImageBitmap(rendered)
                    bitmap?.recycle(); bitmap = rendered
                    image.contentDescription = "${selection.title}, page ${page + 1} of $count"
                    status.text = "${selection.title} · ${page + 1} / $count"
                    state.saveProgress(selection.uri, page.toLong())
                    progressLine.progress = (page + 1) * 1000 / count
                    if (tapOnly) {
                        pageBadge.text = String.format(java.util.Locale.getDefault(), "%d / %d", page + 1, count)
                        pageBadge.visibility = android.view.View.VISIBLE
                        pageBadge.removeCallbacks(hideBadge); pageBadge.postDelayed(hideBadge, 1500)
                    }
                }.onFailure { status.text = "Cannot open this document. Check folder access or choose another file." }
            }
        }
    }

    // Uses limited temporary copies for seekable PDF/ZIP access and releases every descriptor.
    private fun decode(selection: LibraryItem, requestedPage: Int): Pair<Bitmap, Int> {
        val uri = Uri.parse(selection.uri)
        val kind = requireNotNull(ReadingFormats.inlineKind(selection.title, selection.mime))
        if (kind == ReadingFormats.InlineKind.IMAGE) {
            val data = context.contentResolver.openInputStream(uri)!!.use { it.readBytesBounded(32 * 1024 * 1024) }
            return decodeImage(data) to 1
        }
        val temp = cachedFile?.takeIf { cachedUri == selection.uri && it.exists() }
            ?: File.createTempFile("reader-", ".data", context.cacheDir)
        if (temp != cachedFile) try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        check(!closed && !Thread.currentThread().isInterrupted) { "Reader closed" }
                        val n = input.read(buffer); if (n < 0) break
                        total += n; require(total <= 512L * 1024 * 1024) { "Document exceeds 512 MB" }
                        out.write(buffer, 0, n)
                    }
                }
            }
            cachedFile?.delete(); cachedFile = temp; cachedUri = selection.uri
        } catch (error: Exception) { temp.delete(); throw error }
        run {
            if (kind == ReadingFormats.InlineKind.PDF) {
                PdfRenderer(ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                    require(pdf.pageCount > 0)
                    pdf.openPage(requestedPage.coerceIn(0, pdf.pageCount - 1)).use { p ->
                        val scale = 1600f / maxOf(p.width, p.height)
                        val result = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1),
                            (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        result.eraseColor(Color.WHITE)
                        p.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return result to pdf.pageCount
                    }
                }
            }
            require(kind == ReadingFormats.InlineKind.COMIC)
            ZipFile(temp).use { zip ->
                val pages = zip.entries().asSequence().filter { !it.isDirectory &&
                    it.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp") }
                    .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }.take(20000).toList()
                require(pages.isNotEmpty())
                val entry = pages[requestedPage.coerceIn(0, pages.size - 1)]
                val data = zip.getInputStream(entry).use { it.readBytesBounded(32 * 1024 * 1024) }
                return decodeImage(data) to pages.size
            }
        }
    }

    // Caps decompressed input size before allocating an image to avoid archive expansion
    // exhaustion.
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer); if (n < 0) break
            require(out.size() + n <= limit) { "Image too large" }; out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // Samples large images before decoding so comic pages cannot allocate unlimited bitmaps.
    private fun decodeImage(data: ByteArray): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        require(options.outWidth > 0 && options.outHeight > 0)
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        return requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size, options))
    }

    // Page picture that passes touches to ReaderPanel's gesture detector; taps arrive as clicks.
    private class PageImageView(context: Context) : ImageView(context) {
        var gestures: android.view.GestureDetector? = null
        var tapPending = false
        // Lets the detector see the whole gesture; a recognised tap becomes a click so
        // accessibility services and the click listener share one path.
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            val handled = gestures?.onTouchEvent(event) == true
            if (tapPending) { tapPending = false; performClick() }
            return handled || super.onTouchEvent(event)
        }
        // Kept explicit so accessibility clicks and touch taps share one path.
        override fun performClick(): Boolean = super.performClick()
    }

    // Prevents background decoding from updating a detached screen and frees the displayed bitmap.
    fun close() {
        closed = true; generation++
        pageBadge.removeCallbacks(hideBadge)
        worker.execute { cachedFile?.delete(); cachedFile = null }
        worker.shutdown()
        image.setImageDrawable(null); bitmap?.recycle(); bitmap = null
    }
}
