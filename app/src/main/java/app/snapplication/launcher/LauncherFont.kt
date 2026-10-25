package app.snapplication.launcher

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/*
Imports a user-selected TTF/OTF into private app storage and supplies a cached typeface.
MainActivity owns the picker and settings row; LauncherUi and page roots apply the font. Screen
controls: no controls here. A failed or oversized import preserves the previous font; reset returns
to Android's default. Fonts are local only and are never bundled or uploaded. Extend accepted
formats only after validating them through Android's font loader.

This object is a shared helper and does not inherit from another launcher class.

cachedPath identifies the private font file already loaded, and cached holds the reusable Typeface.
importFont first copies and checks a temporary file, then replaces the active font. get loads it
when needed; apply visits text controls and assigns it. MainActivity handles the picker and
LauncherUi uses the result. These classes use this shared helper rather than inherit from it.
*/
object LauncherFont {
    private var cachedPath: String? = null
    private var cached: Typeface? = null

    // Copies a limited file, validates it, then replaces the active font atomically.
    @Synchronized fun importFont(context: Context, uri: Uri) {
        val stage = File(context.filesDir, "launcher-font-import.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                stage.outputStream().use { output ->
                    val bytes = ByteArray(8192)
                    var size = 0
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        size += count
                        require(size <= 8 * 1024 * 1024) { "Choose a font smaller than 8 MB." }
                        output.write(bytes, 0, count)
                    }
                    require(size > 0) { "The font file is empty." }
                }
            } ?: error("Cannot open this file.")
            requireNotNull(Typeface.Builder(stage).build()) { "Invalid font file." }
            val target = File(context.filesDir, "launcher-font.ttf")
            java.nio.file.Files.move(stage.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            cachedPath = null; cached = null
        } finally { stage.delete() }
    }

    // Keeps system text available if a previously imported font cannot be loaded.
    @Synchronized fun get(context: Context): Typeface? {
        val file = File(context.filesDir, "launcher-font.ttf")
        if (!file.exists()) return null
        if (cachedPath != file.absolutePath) {
            cached = runCatching { Typeface.createFromFile(file) }.getOrNull()
            cachedPath = file.absolutePath
        }
        return cached
    }

    // Restores Android's default without affecting theme, wallpaper or saved content.
    @Synchronized fun reset(context: Context) {
        File(context.filesDir, "launcher-font.ttf").delete(); cachedPath = null; cached = null
    }

    // Includes custom panel controls and search fields, not just LauncherUi's shared labels.
    fun apply(view: View) {
        val font = get(view.context) ?: return
        fun visit(node: View) {
            if (node is TextView && node.typeface !== font) node.typeface = font
            if (node is ViewGroup) for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        visit(view)
    }
}
