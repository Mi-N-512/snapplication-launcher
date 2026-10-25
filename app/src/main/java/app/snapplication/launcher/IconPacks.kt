package app.snapplication.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader

/*
Resolves optional installed icon packs for MainActivity's favourites and app drawer. Remote
resources remain data only; missing or unsupported icons use the supplied native icon.

This object is a shared helper and does not inherit from another launcher class.

themeActions lists the Android actions used to discover installed packs. cache keeps their parsed
icon indexes, and drawableNamePattern checks resource names before lookup. load looks for an icon
matching the app component and calls fallback when none is available. MainActivity and DrawerPanel
use this shared helper; installed packs supply resources rather than executable launcher code.

Used by or used with: MainActivity and DrawerPanel request icons; LauncherState retains launcher
preferences. Screen controls: No screen; supplies themed drawables to favourite tiles and app rows.
Changes belong here: Extend supported icon-pack lookup formats and fallbacks here; preserve normal
app icons when a pack has no match.
*/
object IconPacks {
    private val themeActions = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme")
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 50_000
    private const val MAX_TOKENS = 300_000
    private val cache = linkedMapOf<String, PackIndex>()
    private val drawableNamePattern = Regex("[a-zA-Z0-9_]{1,160}")

    /*
    Cached icon-pack lookup data owned by IconPacks. Version/update fields invalidate stale
    mappings; icons maps components to drawable names. No UI. Add cache metadata here when
    supporting another pack format.
    */
    private data class PackIndex(val version: Long, val updated: Long, val icons: Map<String, String>)

    // Lists only installed packs advertising a supported theme intent; never installs or contacts a
    // provider.
    fun listInstalled(context: Context): List<Pair<String, String>> {
        val manager = context.packageManager
        val discovered = linkedMapOf<String, String>()
        for (action in themeActions) {
            val activities = try { manager.queryIntentActivities(Intent(action), 0) } catch (_: RuntimeException) { emptyList() }
            for (activity in activities) {
                val info = activity.activityInfo ?: continue
                if (!info.enabled || !info.applicationInfo.enabled) continue
                val label = try { info.applicationInfo.loadLabel(manager).toString() } catch (_: RuntimeException) { info.packageName }
                discovered[info.packageName] = label
            }
        }
        return discovered.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }

    // Names the stored selection so Settings makes fallback versus pack selection visible.
    fun selectedLabel(context: Context): String {
        val selected = context.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("iconPack", "").orEmpty()
        if (selected.isBlank()) return "System icons"
        return runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(selected, 0)).toString() }
            .getOrDefault("Selected pack is unavailable")
    }

    // Returns the selected pack's exact component icon or invokes the original native-icon loader.
    @SuppressLint("DiscouragedApi")
    fun load(context: Context, component: ComponentName, fallback: () -> Drawable): Drawable {
        val selected = context.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("iconPack", "").orEmpty()
        if (selected.isBlank()) return fallback()
        val themed = try {
            val manager = context.packageManager
            val info = manager.getPackageInfo(selected, 0)
            val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            val resources = manager.getResourcesForApplication(selected)
            val index = synchronized(cache) {
                val existing = cache[selected]
                if (existing != null && existing.version == version && existing.updated == info.lastUpdateTime) existing
                else {
                    val replacement = PackIndex(version, info.lastUpdateTime, readIndex(resources, selected))
                    cache[selected] = replacement
                    // A small bound avoids retaining old maps after repeatedly changing packs.
                    while (cache.size > 4) cache.remove(cache.keys.first())
                    replacement
                }
            }
            val drawableName = index.icons[component.flattenToString()]
            val resourceId = drawableName?.let { resources.getIdentifier(it, "drawable", selected) } ?: 0
            if (resourceId == 0) null else resources.getDrawable(resourceId, null)
        } catch (_: Exception) { null }
        return themed ?: fallback()
    }

    // Reads a compiled appfilter resource first, or a limited asset; malformed packs get an empty
    // index.
    @SuppressLint("DiscouragedApi")
    private fun readIndex(resources: Resources, packageName: String): Map<String, String> {
        var compiled: XmlResourceParser? = null
        try {
            val id = resources.getIdentifier("appfilter", "xml", packageName)
            if (id != 0) {
                compiled = resources.getXml(id)
                return parse(compiled)
            }
            val text = resources.assets.open("appfilter.xml").use { readBounded(it) }.toString(Charsets.UTF_8)
            // Refuse DTDs/entities before giving text to the parser; no network/entity resolver is
            // used.
            if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true)) return emptyMap()
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setInput(StringReader(text))
            }
            return parse(parser)
        } catch (_: Exception) { return emptyMap() }
        finally { compiled?.close() }
    }

    // Bounds both retained mappings and parser work; accepts only exact ComponentInfo identifiers.
    private fun parse(parser: XmlPullParser): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var entries = 0
        var tokens = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (++tokens > MAX_TOKENS) return emptyMap()
            if (parser.eventType == XmlPullParser.DOCDECL || parser.eventType == XmlPullParser.ENTITY_REF) return emptyMap()
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "item") {
                if (++entries > MAX_ENTRIES) return emptyMap()
                val raw = parser.getAttributeValue(null, "component").orEmpty()
                val drawable = parser.getAttributeValue(null, "drawable").orEmpty()
                if (raw.length <= 600 && raw.startsWith("ComponentInfo{") && raw.endsWith("}") &&
                    drawable.matches(drawableNamePattern)) {
                    val component = ComponentName.unflattenFromString(raw.removePrefix("ComponentInfo{").removeSuffix("}"))
                    if (component != null) result[component.flattenToString()] = drawable
                }
            }
            parser.nextToken()
        }
        return result
    }

    // Rejects oversized assets before allocation can grow beyond the fixed appfilter budget.
    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            require(output.size() + count <= MAX_BYTES) { "Icon filter exceeds size limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
