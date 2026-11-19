package app.snapplication.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/*
Remote diagnostics: a short, local history of what happened to each physical button press.

When a remote "sometimes does nothing", the press usually did arrive but was dropped somewhere on
the way: the accessibility service was not running, the launcher window owned focus, the remote
reconnected with a new identity so its saved buttons no longer matched, the button was left to
Android's own navigation, or the mapped action itself failed. RemoteActionService and MainActivity
call record() at each of those decision points, and the "Remote diagnostics" screen shows the log
with a plain-language reason.

Only key codes, input device names and action names are stored. No text, window contents or app
data is recorded. The log is kept in memory and saved (debounced) to private preferences so it
survives the service being restarted by the system. Nothing leaves the device unless the user
copies it.
*/
object RemoteDiagnostics {
    data class Entry(val time: Long, val source: String, val device: String, val key: String,
                     val mapped: String?, val outcome: String)

    private const val LIMIT = 80
    private val entries = ArrayDeque<Entry>()
    private val handler = Handler(Looper.getMainLooper())
    private var store: Context? = null
    private var loaded = false
    @Volatile var lastServiceEvent: String = "No service event recorded since the launcher started"
        private set
    @Volatile var lastServiceKeyAt: Long = 0L
        private set
    // Live listener for the diagnostics screen; called on the main thread.
    var listener: ((Entry) -> Unit)? = null

    private val persist = Runnable {
        val context = store ?: return@Runnable
        val array = JSONArray()
        synchronized(entries) {
            entries.forEach { e ->
                array.put(JSONObject().put("t", e.time).put("s", e.source).put("d", e.device).put("k", e.key)
                    .put("m", e.mapped ?: "").put("o", e.outcome))
            }
        }
        context.getSharedPreferences("remoteDiagnostics", Context.MODE_PRIVATE).edit()
            .putString("log", array.toString()).putString("service", lastServiceEvent).apply()
    }

    // Loads the saved log once; safe to call repeatedly.
    fun attach(context: Context) {
        if (loaded) return
        loaded = true
        store = context.applicationContext
        val prefs = context.getSharedPreferences("remoteDiagnostics", Context.MODE_PRIVATE)
        prefs.getString("service", null)?.let { lastServiceEvent = it }
        runCatching {
            val array = JSONArray(prefs.getString("log", "[]"))
            synchronized(entries) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    entries.addLast(Entry(o.getLong("t"), o.getString("s"), o.getString("d"), o.getString("k"),
                        o.optString("m").ifBlank { null }, o.getString("o")))
                }
            }
        }
    }

    // Records one decision for a key press. Only the initial press (and Back release) is logged so
    // the key-filter deadline is not spent on repeats.
    fun record(context: Context, source: String, event: KeyEvent, mapped: String?, outcome: String) {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return
        attach(context)
        if (source == "Remote support") lastServiceKeyAt = System.currentTimeMillis()
        val entry = Entry(System.currentTimeMillis(), source, event.device?.name ?: "Unknown device",
            KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_"), mapped, outcome)
        add(entry)
    }

    // Records an action result that happens after the key was accepted (for example Back failed).
    fun note(context: Context, source: String, outcome: String) {
        attach(context)
        add(Entry(System.currentTimeMillis(), source, "", "", null, outcome))
    }

    // Records accessibility service connect/disconnect, the most common cause of a silent remote.
    fun service(context: Context, text: String) {
        attach(context)
        lastServiceEvent = "$text · ${java.text.DateFormat.getTimeInstance().format(java.util.Date())}"
        add(Entry(System.currentTimeMillis(), "Remote support", "", "", null, text))
    }

    private fun add(entry: Entry) {
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > LIMIT) entries.removeFirst()
        }
        handler.post { listener?.invoke(entry) }
        handler.removeCallbacks(persist)
        handler.postDelayed(persist, 1500)
    }

    fun snapshot(): List<Entry> = synchronized(entries) { entries.toList() }

    fun clear(context: Context) {
        attach(context)
        synchronized(entries) { entries.clear() }
        handler.removeCallbacks(persist); handler.post(persist)
    }

    fun format(entry: Entry): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(entry.time))
        val key = if (entry.key.isBlank()) "" else " · ${entry.key}"
        val device = if (entry.device.isBlank()) "" else " · ${entry.device}"
        val mapped = entry.mapped?.let { " → ${RemoteControls.displayLabel(it)}" } ?: ""
        return "$time  ${entry.source}$key$device$mapped\n    ${entry.outcome}"
    }

    // Plain-language health summary shown above the log.
    fun health(context: Context): List<String> {
        val lines = mutableListOf<String>()
        val service = RemoteActionService.instance
        lines += if (service == null) "Remote support: NOT running. Buttons only work inside the launcher. Turn Snapplication off and on in Android accessibility settings."
            else "Remote support: running"
        if (service != null) {
            val flags = service.serviceInfo?.flags ?: 0
            lines += if (flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0)
                "Button filtering: allowed" else "Button filtering: NOT granted. Turn remote support off and on again."
        }
        lines += "Last remote support event: $lastServiceEvent"
        if (lastServiceKeyAt > 0) {
            val seconds = (System.currentTimeMillis() - lastServiceKeyAt) / 1000
            lines += "Last button seen outside the launcher: ${seconds}s ago"
        }
        val saved = runCatching {
            JSONObject(context.getSharedPreferences("remoteControls", Context.MODE_PRIVATE).getString("mappings", "{}")!!)
        }.getOrElse { JSONObject() }
        val savedNames = saved.keys().asSequence().mapNotNull { d ->
            val entries = saved.optJSONObject(d) ?: return@mapNotNull null
            entries.keys().asSequence().mapNotNull { entries.optJSONObject(it)?.optString("name") }.firstOrNull()?.let { d to it }
        }.toList()
        val keyDevices = InputDevice.getDeviceIds().asIterable().mapNotNull { InputDevice.getDevice(it) }
            .filter { !it.isVirtual && it.sources and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD }
        // Built-in phone keys (volume, power, fingerprint, headset jack) are not remotes; Android 10+
        // can tell them apart. Saved remotes are always shown.
        val devices = if (android.os.Build.VERSION.SDK_INT >= 29)
            keyDevices.filter { it.isExternal || savedNames.any { saved -> saved.first == it.descriptor } } else keyDevices
        if (devices.isEmpty()) lines += "Connected remotes: none detected. Pair or wake the remote, then tap Refresh."
        devices.forEach { device ->
            val exact = savedNames.any { it.first == device.descriptor }
            val byName = savedNames.any { it.second == device.name }
            val status = when {
                exact -> "saved buttons match"
                byName -> "same name as a saved remote but a new identity: matched only when it is the only one connected"
                else -> "no saved buttons"
            }
            lines += "Device: ${device.name} · $status"
        }
        return lines
    }
}
