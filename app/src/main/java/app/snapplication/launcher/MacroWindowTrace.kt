package app.snapplication.launcher

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.util.concurrent.Executors

/*
Temporary debug-build diagnostic for Huawei projection windows during an explicit macro recording.
RemoteActionService supplies its own window list, so USB disconnection cannot interrupt collection.
Screen controls: No extra overlay or permission. It stores structural identifiers locally, never
labels, images, descriptions, typed values or gestures. It does not change which screens can be
recorded or replayed. Edit or remove here after the hub issue is resolved; do not enable this
collector in release builds.

This class is a helper and does not inherit from another launcher class.

output is the local diagnostic file. writer saves data away from the main thread; previous avoids
duplicate records; samples and deadline limit the recording. RemoteActionService calls capture with
the visible windows. describe writes structural details, and close stops accepting new file work.
The class implements AutoCloseable so its owner can release that writer.
*/
internal class MacroWindowTrace(context: Context) : AutoCloseable {
    private val output = File(context.getExternalFilesDir(null) ?: context.filesDir, "macro-window-trace.txt")
    private val writer = Executors.newSingleThreadExecutor()
    private var nextSample = 0L
    private var previous = ""
    private var samples = 0
    private val deadline = SystemClock.elapsedRealtime() + 180_000

    init { writer.execute { runCatching { output.writeText("Macro window identifiers only\n") } } }

    // Samples at most once per second for three minutes, with a strict total output bound.
    fun capture(windows: List<AccessibilityWindowInfo>, activeRoot: AccessibilityNodeInfo?) {
        val now = SystemClock.elapsedRealtime()
        if (now < nextSample || now > deadline || samples >= 60) return
        nextSample = now + 1000
        val text = StringBuilder()
        val remaining = intArrayOf(120)
        // Compare the active-root query with window-list roots when vendor transitions disagree.
        if (activeRoot != null) {
            text.append("activeRoot window=${activeRoot.windowId} refreshed=${activeRoot.refresh()}\n")
            describe(activeRoot, text, remaining, 0)
        }
        for (window in windows.take(12)) {
            val box = Rect().also { window.getBoundsInScreen(it) }
            text.append("window type=${window.type} layer=${window.layer} active=${window.isActive} focused=${window.isFocused} bounds=$box\n")
            val root = window.root
            try {
                if (root != null) {
                    text.append("root refreshed=${root.refresh()}\n")
                    describe(root, text, remaining, 0)
                }
            } finally { root?.recycle() }
        }
        val snapshot = text.toString().take(12_000)
        if (snapshot == previous) return
        previous = snapshot
        samples++
        writer.execute { runCatching { output.appendText("time=$now\n$snapshot") } }
    }

    // Includes only node structure; editable and password subtrees are never traversed.
    private fun describe(node: AccessibilityNodeInfo, text: StringBuilder, remaining: IntArray, depth: Int) {
        if (depth > 20 || remaining[0]-- <= 0 || !node.isVisibleToUser) return
        val box = Rect().also { node.getBoundsInScreen(it) }
        text.append("$depth ${node.packageName} ${node.viewIdResourceName} ${node.className} $box clickable=${node.isClickable}\n")
        if (node.isPassword || node.isEditable) return
        for (i in 0 until node.childCount.coerceAtMost(60)) {
            val child = node.getChild(i) ?: continue
            try { describe(child, text, remaining, depth + 1) } finally { child.recycle() }
        }
    }

    // Finishes already queued writes without retaining a worker after recording ends.
    override fun close() { writer.shutdown() }
}
