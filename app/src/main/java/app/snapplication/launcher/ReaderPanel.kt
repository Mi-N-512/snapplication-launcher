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

/** Renders local PDFs, CBZ comics and images inside MainActivity; LauncherState stores the current page. */
class ReaderPanel(private val context: Context, private val state: LauncherState, private val ui: LauncherUi) {
    val view = ui.column()
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val status = ui.label("Choose a PDF, comic or image", 16f)
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

    init {
        view.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(status)
        val controls = ui.column(true)
        ui.addWeighted(controls, ui.button("Previous") { move(-1) })
        ui.addWeighted(controls, ui.button("Next") { move(1) })
        view.addView(controls)
    }

    /** Loads a selected document at its saved page and cancels stale display results. */
    fun load(selection: LibraryItem) {
        ready = false
        item = selection; page = state.progress(selection.uri).toInt().coerceAtLeast(0)
        state.remember(selection); render()
    }

    /** Moves within document bounds; every confirmed page change persists immediately. */
    fun move(delta: Int) {
        if (!ready) return
        page = (page + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
        item?.let { state.saveProgress(it.uri, page.toLong()) }; render()
    }

    /** Decodes off the UI thread and only publishes results belonging to the current selection. */
    private fun render() {
        val selection = item ?: return
        val request = ++generation
        val requestedPage = page
        ready = false
        status.text = "Loading ${selection.title}…"
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
                }.onFailure { status.text = "Cannot open this document. Check folder access or choose another file." }
            }
        }
    }

    /** Uses bounded temporary copies for seekable PDF/ZIP access and releases every descriptor. */
    private fun decode(selection: LibraryItem, requestedPage: Int): Pair<Bitmap, Int> {
        val uri = Uri.parse(selection.uri)
        val extension = selection.title.substringAfterLast('.', "").lowercase()
        if (selection.mime.startsWith("image/")) {
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
            if (selection.mime == "application/pdf" || extension == "pdf") {
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
            require(extension == "cbz")
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

    /** Caps decompressed input size before allocating an image to avoid archive expansion exhaustion. */
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer); if (n < 0) break
            require(out.size() + n <= limit) { "Image too large" }; out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Samples large images before decoding so comic pages cannot allocate unbounded bitmaps. */
    private fun decodeImage(data: ByteArray): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        require(options.outWidth > 0 && options.outHeight > 0)
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        return requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size, options))
    }

    /** Prevents background decoding from updating a detached screen and frees the displayed bitmap. */
    fun close() {
        closed = true; generation++
        worker.execute { cachedFile?.delete(); cachedFile = null }
        worker.shutdown()
        image.setImageDrawable(null); bitmap?.recycle(); bitmap = null
    }
}
