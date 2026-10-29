package app.snapplication.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.DragEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
import java.text.Collator

/*
Owns MainActivity's alphabetical app drawer, remote navigation and in-place favourite editing.
LauncherState stores Home choices; LauncherUi supplies styling; IconPacks supplies optional icons.
Navigation callbacks keep activity lifecycle and launching responsibilities in MainActivity.

This class is a helper and does not inherit from another launcher class.

entries contains the installed apps; visibleEntries contains the current search results. editing
changes a tap from opening an app to adding or removing a favourite. preview draws the current
favourite order, while sectionStarts and sectionCounts help the A-Z rail find each group.
MainActivity creates this panel and supplies its navigation functions. The panel uses LauncherState
to save favourites and IconPacks to load their pictures.

Used by or used with: MainActivity, LauncherState, LauncherUi and IconPacks. Screen controls:
Search, alphabetical rail, app rows, favourites editing and the live ordering preview. Changes
belong here: Change app browsing, focus navigation and favourite editing here. A future games filter
can reuse installed-app entries without changing the reader/player.
*/
class DrawerPanel(
    private val activity: Activity,
    private val state: LauncherState,
    private val ui: LauncherUi,
    private val onLaunch: (ComponentName) -> Unit,
    private val onSettings: () -> Unit,
    private val onHome: () -> Unit,
    private val guidedSetup: Boolean = false
) {
    /*
    One installed app entry owned by DrawerPanel. ComponentName identifies the launch target; label
    and ResolveInfo supply each app row. No independent UI. Add app-list metadata here only when
    drawer sorting, filtering or display needs it.
    */
    private data class Entry(val component: ComponentName, val label: String, val info: ResolveInfo)

    val view: LinearLayout = ui.column(true)
    private val alphabet = listOf("#", "0-9") + ('A'..'Z').map { it.toString() }
    private val entries = discoverApps()
    private var visibleEntries = entries
    private var editing = false
    // Lets MainActivity rebuild the drawer after an app change without leaving the editor.
    val isEditing: Boolean get() = editing
    private val hint = ui.label("Select opens · Hold for app info", 12f)
    private val search = EditText(activity)
    private val list = ListView(activity)
    private val adapter = AppAdapter()
    private val editButton = ui.button(if (state.separateFavourites) "${state.mode} favourites" else "Favourites") { toggleEditing() }
    private val preview = ui.column()
    private val sectionStarts = mutableMapOf<String, Int>()
    private val sectionCounts = mutableMapOf<String, Int>()
    private lateinit var alphabetRail: AlphabetRail

    init {
        val content = ui.column()
        view.addView(content, LinearLayout.LayoutParams(0, -1, 1f))
        view.addView(createAlphabetRail(), LinearLayout.LayoutParams(ui.dp(48), -1))
        val toolbar = ui.column(true)
        val home = ui.button("‹") { onHome() }.apply { contentDescription = "Home" }
        for (button in listOf(home, editButton)) {
            ui.flatFocus(button, ui.textSurface(), outline = false)
            button.textSize = 14f
        }
        toolbar.addView(home, LinearLayout.LayoutParams(ui.dp(48), ui.dp(52)))
        toolbar.addView(editButton, LinearLayout.LayoutParams(0, ui.dp(52), 1f))
        content.addView(toolbar)
        rebuildSections()
        configureSearch()
        content.addView(search, LinearLayout.LayoutParams(-1, ui.dp(52)))
        content.addView(hint)
        content.addView(preview)
        preview.visibility = View.GONE
        configureList()
        content.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(ui.row("Launcher Settings") { onSettings() }.apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, ui.dp(48)))
        // Opening the drawer should not summon a keyboard before the user chooses Search.
        list.requestFocus()
    }

    // Queries phone and TV launchers once, excludes this app and sorts labels using the user's
    // locale.
    private fun discoverApps(): List<Entry> {
        val manager = activity.packageManager
        val found = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER).flatMap { category ->
            manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
        }
        val collator = Collator.getInstance()
        return found.filter { it.activityInfo.packageName != activity.packageName && it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
            .distinctBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .map { Entry(ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadLabel(manager).toString(), it) }
            .sortedWith { first, second ->
                val group = alphabet.indexOf(sectionFor(first.label)).compareTo(alphabet.indexOf(sectionFor(second.label)))
                if (group != 0) group else collator.compare(first.label, second.label)
            }
    }

    // Filters labels and lets a remote's Down key leave text editing for the first matching app.
    private fun configureSearch() {
        search.apply {
            hint = "Search apps"
            textSize = 17f
            setSingleLine()
            setTextColor(ui.text); setHintTextColor(ui.muted)
            setBackgroundColor(ui.surface)
            setPadding(ui.dp(16), 0, ui.dp(16), 0)
            // In landscape the keyboard must not cover the results with its full-screen text box.
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // Move on release so the corresponding key-up cannot reach the new target.
                    if (event.action == KeyEvent.ACTION_UP) focusFirstResult()
                    true
                } else false
            }
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { focusFirstResult(); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                // Updates result data only for a changed search, never for favourite toggles.
                override fun afterTextChanged(text: Editable?) {
                    val query = text?.toString().orEmpty().trim()
                    visibleEntries = entries.filter { it.label.contains(query, ignoreCase = true) }
                    rebuildSections()
                    adapter.notifyDataSetChanged()
                    list.setSelection(0)
                    updateHint()
                }
                // No state is needed before text replacement.
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                // Filtering runs once after each completed text change.
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
    }

    // Uses ListView's native Up/Down selection and scrolling, with one selectable target per app.
    private fun configureList() {
        list.apply {
            adapter = this@DrawerPanel.adapter
            isFocusable = true
            itemsCanFocus = false
            divider = android.graphics.drawable.ColorDrawable(ui.border)
            dividerHeight = ui.dp(1)
            setSelector(GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT); setStroke(ui.dp(2), ui.accent) })
            setDrawSelectorOnTop(true)
            setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                // Keeps the index aligned with touch scrolling without fighting an active index
                // gesture.
                override fun onScroll(v: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                    if (!alphabetRail.hasFocus()) visibleEntries.getOrNull(first)?.let { alphabetRail.follow(it.label) }
                }
                // No work is needed when scrolling starts or stops.
                override fun onScrollStateChanged(v: android.widget.AbsListView?, state: Int) {}
            })
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                // Tracks remote-selected rows as well as the first visible touch-scrolled row.
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (!alphabetRail.hasFocus()) visibleEntries.getOrNull(position)?.let { alphabetRail.follow(it.label) }
                }
                // An empty selection leaves the last visible index marker intact.
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            setOnItemClickListener { _, _, position, _ ->
                visibleEntries.getOrNull(position)?.let { entry ->
                    if (editing) toggleFavourite(entry) else onLaunch(entry.component)
                }
            }
            setOnItemLongClickListener { _, _, position, _ ->
                visibleEntries.getOrNull(position)?.let { openAppInfo(it) }
                true
            }
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        alphabetRail.requestFocus()
                    }
                    true
                } else false
            }
        }
    }

    // Hides the keyboard and moves remote selection into the matching list, or to Edit if empty.
    private fun focusFirstResult() {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        if (visibleEntries.isEmpty()) editButton.requestFocus()
        else { list.requestFocus(); list.setSelection(0) }
    }

    // Switches Select between launching and Home membership without rebuilding rows or losing
    // scroll.
    // Opens Settings' favourites entry in the same selection and drag-reorder mode.
    fun beginFavouriteEditing() { if (!editing) toggleEditing() }

    private fun toggleEditing() {
        editing = !editing
        editButton.text = if (editing) "Done" else if (state.separateFavourites) "${state.mode} favourites" else "Favourites"
        updateHint()
        refreshVisibleRows()
        refreshPreview()
    }

    // Explains the active Select action, including the empty-search state.
    private fun updateHint() {
        hint.text = when {
            visibleEntries.isEmpty() -> "No matching apps"
            editing -> "Tap adds / removes Home · Drag preview to reorder"
            else -> "Select opens · Hold for app info"
        }
    }

    // Changes one Home entry while keeping query, scroll offset and remote selection intact.
    private fun toggleFavourite(entry: Entry) {
        state.toggleFavourite(entry.component.flattenToString())
        refreshVisibleRows()
        refreshPreview()
        val added = entry.component.flattenToString() in state.favourites()
        Toast.makeText(activity, "${entry.label} ${if (added) "added to" else "removed from"} Home", Toast.LENGTH_SHORT).show()
    }

    // Refreshes existing text only; off-screen rows receive current state when ListView recycles
    // them.
    private fun refreshVisibleRows() {
        for (index in 0 until list.childCount) {
            val entry = visibleEntries.getOrNull(list.firstVisiblePosition + index) ?: continue
            (list.getChildAt(index) as? TextView)?.let { bindText(it, entry) }
        }
    }

    // Opens Android app information directly when an app row is held.
    private fun openAppInfo(entry: Entry) {
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", entry.component.packageName, null)))
        } catch (_: RuntimeException) {
            Toast.makeText(activity, "App info is unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    // Keeps every alphabet group visible; touch scrubs the rail and remote arrows skip empty
    // groups.
    private fun createAlphabetRail(): View = AlphabetRail().also { alphabetRail = it }

    /*
    DrawerPanel's touch and remote alphabet selector. Draws the side index and moves focus to
    matching app rows. Edit letter grouping, hit targets and directional navigation here; keep
    selection in sync with the drawer list.

    AlphabetRail is a child of Android's View, which provides drawing and input callbacks.
    It belongs to DrawerPanel and reads the panel's letter groups and current selection.
    */
    private inner class AlphabetRail : View(activity) {
        private val letters = alphabet
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var selected = 0
        init {
            isFocusable = true; isClickable = true
            contentDescription = "App alphabet. Up and Down choose a letter; Left returns to apps."
            setBackgroundColor(ui.textSurface())
            setOnFocusChangeListener { _, focused ->
                if (focused) showSection() else updateHint()
                invalidate()
            }
        }
        // Tests the currently filtered app names so keyboard navigation avoids dead ends.
        private fun hasApps(index: Int): Boolean = sectionStarts.containsKey(letters[index])
        // Follows ordinary list movement while leaving an actively scrubbed index in control.
        fun follow(label: String) {
            val next = letters.indexOf(sectionFor(label)).coerceAtLeast(0)
            if (next != selected) { selected = next; invalidate() }
        }
        // Displays an easily read letter and count outside the compact rail.
        private fun showSection() {
            val letter = letters[selected]
            val count = sectionCounts[letter] ?: 0
            hint.text = "$letter  ·  $count ${if (count == 1) "app" else "apps"}  ·  Left or Select returns to apps"
            contentDescription = "App alphabet, $letter, $count apps. Up and Down choose; Left or Select opens the list."
        }
        // Centers every group within its own band, with a strong focus marker on the chosen letter.
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val band = height.toFloat() / letters.size
            letters.forEachIndexed { index, letter ->
                paint.textAlign = android.graphics.Paint.Align.CENTER
                paint.textSize = minOf(ui.dp(14).toFloat(), band * .82f)
                paint.isFakeBoldText = index == selected
                paint.color = if (hasApps(index)) ui.text else ui.muted
                paint.alpha = if (hasApps(index)) 255 else 85
                if (index == selected) {
                    paint.color = ui.accent; paint.alpha = if (hasFocus()) 90 else 35
                    canvas.drawRect(0f, index * band, width.toFloat(), (index + 1) * band, paint)
                    paint.color = ui.text; paint.alpha = 255
                }
                canvas.drawText(letter, width / 2f, (index + .5f) * band - (paint.ascent() + paint.descent()) / 2f, paint)
            }
        }
        // Scrubs directly to a letter without opening a modal picker or stealing list scrolling.
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); requestFocus() }
                android.view.MotionEvent.ACTION_UP -> { parent.requestDisallowInterceptTouchEvent(false); performClick(); return true }
                android.view.MotionEvent.ACTION_CANCEL -> { parent.requestDisallowInterceptTouchEvent(false); return true }
            }
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN || event.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                val next = (event.y / height.coerceAtLeast(1) * letters.size).toInt().coerceIn(letters.indices)
                if (selected != next || event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    selected = next
                    if (hasApps(selected)) jumpToLetter(letters[selected])
                    showSection(); invalidate()
                }
            }
            return true
        }
        // Exposes the same jump to accessibility services and remote Select.
        override fun performClick(): Boolean {
            super.performClick()
            sectionStarts[letters[selected]]?.let { position ->
                list.requestFocus(); list.setSelection(position)
            }
            return true
        }
        // Provides deterministic Up/Down movement and a direct route back to the app list.
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { list.requestFocus(); return true }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                var next = selected + direction
                while (next in letters.indices && !hasApps(next)) next += direction
                if (next !in letters.indices) return true
                selected = next; jumpToLetter(letters[selected]); showSection(); invalidate()
                announceForAccessibility(letters[selected])
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
    }

    // Groups accents under their base letter; digits and other scripts have predictable dedicated
    // groups.
    private fun sectionFor(label: String): String {
        val first = java.text.Normalizer.normalize(label.trim(), java.text.Normalizer.Form.NFD)
            .firstOrNull()?.uppercaseChar() ?: return "#"
        return when { first.isDigit() -> "0-9"; first in 'A'..'Z' -> first.toString(); else -> "#" }
    }

    // Indexes filtered data once, avoiding repeated app scans during every index draw and pointer
    // move.
    private fun rebuildSections() {
        sectionStarts.clear(); sectionCounts.clear()
        visibleEntries.forEachIndexed { index, entry ->
            val section = sectionFor(entry.label)
            sectionStarts.putIfAbsent(section, index)
            sectionCounts[section] = (sectionCounts[section] ?: 0) + 1
        }
        if (::alphabetRail.isInitialized) {
            visibleEntries.firstOrNull()?.let { alphabetRail.follow(it.label) }
            alphabetRail.invalidate()
        }
    }

    // Uses the cached first row for a group, keeping search and favourites mode intact.
    private fun jumpToLetter(letter: String) {
        sectionStarts[letter]?.let { list.setSelectionFromTop(it, 0) }
    }

    // Rebuilds the actual Home proportions and slot rules in miniature after each membership
    // change.
    private fun refreshPreview() {
        preview.visibility = if (editing) View.VISIBLE else View.GONE
        if (!editing) return
        val focused = preview.findFocus()?.tag as? String
        preview.removeAllViews()
        val ordered = state.favourites().mapNotNull { flat -> entries.find { it.component.flattenToString() == flat } }
        val shown = ordered.take(state.favouriteCount)
        val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val miniature = ui.column(landscape)
        val viewer = ui.label("Home viewer", 11f).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            background = ui.homeSurface()
        }
        if (landscape) {
            val total = if (state.dynamicBlocks) shown.size.coerceAtLeast(2) else state.favouriteCount
            val left = (total + 1) / 2
            val right = if (state.dynamicBlocks && state.asymmetricBlocks) (total - left).coerceAtLeast(1) else left
            miniature.addView(previewSlots(shown.take(left), false, left, shown), LinearLayout.LayoutParams(0, -1, .15f))
            miniature.addView(viewer, LinearLayout.LayoutParams(0, -1, .70f))
            miniature.addView(previewSlots(shown.drop(left), false, right, shown), LinearLayout.LayoutParams(0, -1, .15f))
        } else {
            miniature.addView(viewer, LinearLayout.LayoutParams(-1, 0, .57f))
            val count = if (state.dynamicBlocks) shown.size.coerceAtLeast(2) else state.favouriteCount
            miniature.addView(previewSlots(shown, true, count, shown), LinearLayout.LayoutParams(-1, 0, .43f))
        }
        // Keep room for selectable app rows while the landscape tutorial coach is visible.
        val previewHeight = (if (landscape && guidedSetup) 48 else if (landscape) 100 else 160) -
            (if (ordered.size > shown.size && !(landscape && guidedSetup)) 40 else 0)
        preview.addView(miniature, LinearLayout.LayoutParams(-1, ui.dp(previewHeight)))
        // Hidden entries remain editable without misrepresenting them as visible Home slots.
        if (ordered.size > shown.size) {
            preview.addView(ui.button("${ordered.size - shown.size} more favourites · reorder") {
                val overflow = ordered.drop(shown.size)
                AlertDialog.Builder(activity).setTitle("Outside visible Home slots")
                    .setItems(overflow.map { it.label }.toTypedArray()) { _, index -> chooseMove(overflow[index]) }
                    .setNegativeButton("Cancel", null).show()
            }, LinearLayout.LayoutParams(-1, ui.dp(40)))
        }
        if (focused != null) preview.findViewWithTag<View>(focused)?.requestFocus()
    }

    // Mirrors MainActivity's grid, side-column and asymmetric final-row calculations exactly.
    private fun previewSlots(items: List<Entry>, portrait: Boolean, slotCount: Int, shown: List<Entry>): View {
        val column = ui.column()
        val columns = if (portrait && (state.grid || state.dynamicBlocks)) minOf(4, (slotCount + 1) / 2).coerceAtLeast(1)
            else if (!portrait && slotCount > 6) 2 else 1
        val count = if (portrait && state.dynamicBlocks && !state.asymmetricBlocks)
            ((slotCount + columns - 1) / columns) * columns else slotCount
        List(count) { items.getOrNull(it) }.chunked(columns).forEach { group ->
            val row = ui.column(true)
            group.forEach { entry -> row.addView(previewTile(entry, shown), LinearLayout.LayoutParams(0, -1, 1f)) }
            column.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return column
    }

    // Creates a compact actual-slot drag target; Select retains accessible remote reorder controls.
    private fun previewTile(entry: Entry?, shown: List<Entry>): View {
        val flat = entry?.component?.flattenToString()
        return ui.button(if (entry == null) "+" else "") {
            if (entry == null) focusFirstResult() else chooseMove(entry)
        }.apply {
            tag = flat
            minHeight = 0; minimumHeight = 0
            minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
            contentDescription = if (entry == null) "Empty Home slot, choose an app below"
                else "Home position ${shown.indexOf(entry) + 1}, ${entry.label}. Select to reorder, hold to drag"
            ui.flatFocus(this, ui.textSurface())
            if (entry != null) {
                val icon = IconPacks.load(activity, entry.component) { entry.info.loadIcon(activity.packageManager) }
                foreground = android.graphics.drawable.LayerDrawable(arrayOf(icon)).apply {
                    setLayerSize(0, ui.dp(18), ui.dp(18)); setLayerGravity(0, Gravity.CENTER)
                }
                setOnLongClickListener {
                    startDragAndDrop(android.content.ClipData.newPlainText("Home shortcut", flat), View.DragShadowBuilder(this), flat, 0)
                }
            }
            setOnDragListener { target, event ->
                val moving = event.localState as? String
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> moving != null && moving in state.favourites()
                    DragEvent.ACTION_DRAG_ENTERED -> { target.alpha = .5f; true }
                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { target.alpha = 1f; true }
                    DragEvent.ACTION_DROP -> {
                        target.alpha = 1f
                        val destination = flat ?: shown.lastOrNull()?.component?.flattenToString()
                        if (moving != null && destination != null) { moveTo(moving, destination); true } else false
                    }
                    else -> true
                }
            }
        }
    }

    // Moves a dragged item to the target's stored position without losing hidden favourites.
    private fun moveTo(moving: String, target: String) {
        val order = state.favourites()
        val from = order.indexOf(moving)
        val to = order.indexOf(target)
        if (from < 0 || to < 0 || from == to) return
        state.moveFavourite(moving, to - from)
        refreshPreview()
    }

    // Provides the same ordering control to remote users who cannot drag.
    private fun chooseMove(entry: Entry) {
        val order = state.favourites()
        val flat = entry.component.flattenToString()
        val index = order.indexOf(flat)
        val choices = mutableListOf<Pair<String, Int>>()
        if (index > 0) choices.add("Move earlier" to -1)
        if (index >= 0 && index < order.lastIndex) choices.add("Move later" to 1)
        if (choices.isEmpty()) return
        AlertDialog.Builder(activity).setTitle(entry.label)
            .setItems(choices.map { it.first }.toTypedArray()) { _, selected ->
                state.moveFavourite(flat, choices[selected].second); refreshPreview()
            }.setNegativeButton("Cancel", null).show()
    }

    // Sets membership markers and a complete spoken label without creating another focus target.
    private fun bindText(row: TextView, entry: Entry) {
        val added = entry.component.flattenToString() in state.favourites()
        row.text = if (editing) "${if (added) "✓" else "＋"}  ${entry.label}" else entry.label
        row.contentDescription = if (editing) "${entry.label}, ${if (added) "on Home, select to remove" else "select to add to Home"}"
            else "${entry.label}, open app"
    }

    /*
    Connects DrawerPanel's installed entries to visible app rows and IconPacks artwork. Edit row
    binding, app labels and favourite state presentation here; keep launch and long-press behaviour
    consistent with the outer panel.

    AppAdapter is a child of Android's BaseAdapter. The list calls its functions to count
    apps and create or reuse each row. It reads the outer panel's filtered app list.
    */
    private inner class AppAdapter : BaseAdapter() {
        // Supplies the filtered result count to ListView.
        override fun getCount(): Int = visibleEntries.size
        // Returns the launchable entry at the current filtered position.
        override fun getItem(position: Int): Any = visibleEntries[position]
        // Identifies a row position without claiming stability across search changes.
        override fun getItemId(position: Int): Long = position.toLong()
        // Binds a non-focusable text row so ListView alone handles remote selection and scrolling.
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? TextView ?: ui.label("", 18f).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(ui.dp(20), ui.dp(12), ui.dp(16), ui.dp(12))
                minHeight = ui.dp(60)
                isFocusable = false; isClickable = false
                setBackgroundColor(ui.textSurface())
                setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
                compoundDrawablePadding = ui.dp(20)
            }
            val entry = visibleEntries[position]
            bindText(row, entry)
            val icon = IconPacks.load(activity, entry.component) { entry.info.loadIcon(activity.packageManager) }
            icon.setBounds(0, 0, ui.dp(30), ui.dp(30))
            row.setCompoundDrawablesRelative(icon, null, null, null)
            return row
        }
    }
}

