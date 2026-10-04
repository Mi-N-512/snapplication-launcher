package app.snapplication.launcher

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Builds MainActivity's guided setup cards; permission requests remain owned by Android and the activity. */
class SetupGuide(private val activity: Activity, private val ui: LauncherUi) {
    /** Describes one setup step, its live status and the explicit action available to the user. */
    data class Page(val title: String, val explanation: String, val action: String, val status: String,
                    val onAction: () -> Unit)

    /** Creates a compact, remote-friendly walkthrough with persistent progress and navigation. */
    fun build(index: Int, pages: List<Page>, onStep: (Int) -> Unit, onFinish: () -> Unit): View {
        val page = pages[index]
        val result = ui.column()
        val progress = ui.column(true).apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        pages.forEachIndexed { position, _ ->
            progress.addView(View(activity).apply {
                setBackgroundColor(if (position <= index) ui.accent else ui.border)
            }, LinearLayout.LayoutParams(0, ui.dp(3), 1f).apply { marginEnd = ui.dp(4) })
        }
        result.addView(progress)
        val body = ui.column().apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(12)) }
        result.addView(ScrollView(activity).apply { addView(body); isFillViewport = true },
            LinearLayout.LayoutParams(-1, 0, 1f))
        val heading = ui.column(true).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_snap_foreground)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)))
        heading.addView(ui.label("SET UP YOUR SPACE   ${index + 1} / ${pages.size}", 12f),
            LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(heading)
        body.addView(ui.label(page.title, 27f).apply { setBackgroundColor(Color.TRANSPARENT) })
        body.addView(ui.label(page.explanation, 17f).apply { setBackgroundColor(Color.TRANSPARENT) })
        if (index == 0) {
            val sample = ui.column(true)
            listOf("Your apps", "Last moment  ▶", "Your apps").forEachIndexed { i, label ->
                sample.addView(ui.label(label, 14f).apply {
                    gravity = Gravity.CENTER; background = ui.homeSurface()
                }, LinearLayout.LayoutParams(0, ui.dp(64), if (i == 1) 3f else 1f))
            }
            body.addView(sample)
        }
        body.addView(ui.label(page.status, 14f).apply {
            setTextColor(ui.muted); background = ui.homeSurface()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        // Keep the permission/setup action reachable without scrolling on a television or small phone.
        result.addView(ui.button(page.action, page.onAction), LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = ui.dp(12); marginEnd = ui.dp(12); topMargin = ui.dp(6)
        })
        val footer = ui.column(true)
        ui.addWeighted(footer, ui.button("Skip setup", onFinish))
        if (index > 0) ui.addWeighted(footer, ui.button("Previous") { onStep(index - 1) })
        ui.addWeighted(footer, ui.button(if (index == pages.lastIndex) "Start using Snapplication" else "Next") {
            if (index == pages.lastIndex) onFinish() else onStep(index + 1)
        })
        result.addView(footer)
        return result
    }
}
