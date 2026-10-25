package app.snapplication.launcher

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/*
Builds the older static setup cards. The active introduction uses LauncherWalkthrough.

This class is a helper and does not inherit from another launcher class.

activity creates the controls, ui supplies colours and spacing, and reading chooses the reading
illustration. build selects a Page from pages using index and connects the navigation buttons to its
caller. Page holds the wording and action callback. This is an older card-based helper: the current
MainActivity uses LauncherWalkthrough instead. Neither helper inherits from MainActivity.
*/
class SetupGuide(private val activity: Activity, private val ui: LauncherUi, private val reading: Boolean = false) {
    /*
    One setup-card description supplied by MainActivity and rendered by SetupGuide. Holds the
    heading, explanation, status, action label and click callback. Add future mode-specific setup
    content through these fields; permission requests remain with the host activity.
    */
    data class Page(val title: String, val explanation: String, val action: String, val status: String,
                    val onAction: () -> Unit)

    // Creates a compact, remote-friendly walkthrough with persistent progress and navigation.
    fun build(index: Int, pages: List<Page>, onStep: (Int) -> Unit, onFinish: () -> Unit): View {
        require(pages.isNotEmpty())
        val step = index.coerceIn(pages.indices)
        val page = pages[step]
        val result = ui.column().apply { setBackgroundColor(ui.background) }
        val progress = ui.column(true).apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)) }
        pages.forEachIndexed { position, _ ->
            progress.addView(View(activity).apply {
                setBackgroundColor(if (position <= step) ui.accent else ui.border)
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
        heading.addView(ui.label("YOUR SETUP   ${step + 1} OF ${pages.size}", 12f),
            LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(heading)
        body.addView(ui.label(page.title, 27f).apply { setBackgroundColor(Color.TRANSPARENT) })
        body.addView(ui.label(page.explanation, 17f).apply { setBackgroundColor(Color.TRANSPARENT) })
        if (step == 0) {
            val sample = ui.column(true)
            listOf("Your apps", if (reading) "Your last page" else "Last moment  ▶", "Your apps").forEachIndexed { i, label ->
                sample.addView(ui.label(label, 14f).apply {
                    gravity = Gravity.CENTER; background = ui.homeSurface()
                }, LinearLayout.LayoutParams(0, ui.dp(64), if (i == 1) 3f else 1f))
            }
            body.addView(sample)
        }
        body.addView(ui.label(page.status, 14f).apply {
            setTextColor(ui.muted); background = ui.homeSurface()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        // Keep the permission/setup action reachable without scrolling on a television or small
        // phone.
        result.addView(ui.button(page.action, page.onAction), LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = ui.dp(12); marginEnd = ui.dp(12); topMargin = ui.dp(6)
        })
        val footer = ui.column(true)
        ui.addWeighted(footer, ui.button("Finish later", onFinish))
        if (step > 0) ui.addWeighted(footer, ui.button("Previous") { onStep(step - 1) })
        ui.addWeighted(footer, ui.button(if (step == pages.lastIndex) "Open Home" else "Next") {
            if (step == pages.lastIndex) onFinish() else onStep(step + 1)
        })
        result.addView(footer)
        return result
    }
}
