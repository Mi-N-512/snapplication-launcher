package app.snapplication.launcher

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.view.Display
import android.view.InputDevice
import java.util.Locale

/*
 * Reads public Android hardware information for MainActivity; does not alter power, display or remote settings.
 *
 * Related classes: MainActivity displays the snapshot returned by this helper.
 * UI: No controls of its own; supplies text for the device/display information screen.
 * Edit or extend: Add public hardware diagnostics here; this does not implement mirroring or control display power.
 */
class DeviceDiagnostics(private val context: Context) {
    // Captures current values on demand so unplugged displays and remote devices disappear after refresh.
    fun snapshot(): String = buildString {
        appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
        appendLine()
        appendLine("Battery and temperature")
        appendLine(battery())
        appendLine(thermal())
        appendLine()
        appendLine("Displays visible to this app")
        appendLine(displays())
        appendLine()
        appendLine("Input devices")
        appendLine(inputs())
    }

    // Distinguishes unavailable battery data from a real zero reading; temperature is the battery sensor only.
    private fun battery(): String = runCatching {
        val data = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "Battery data unavailable"
        if (!data.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return "No battery reported"
        val level = data.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = data.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) "${(level.toLong() * 100 / scale).coerceIn(0, 100)}%" else "Level unavailable"
        val temperature = if (data.hasExtra(BatteryManager.EXTRA_TEMPERATURE))
            String.format(Locale.getDefault(), "%.1f °C battery temperature", data.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f)
        else "Battery temperature unavailable"
        val charge = when (data.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Charge status unavailable"
        }
        "$percent · $charge\n$temperature"
    }.getOrDefault("Battery data unavailable")

    // Reports Android's thermal assessment where supported without interpreting missing support as a cool device.
    private fun thermal(): String {
        if (Build.VERSION.SDK_INT < 29) return "System thermal status requires Android 10 or newer."
        return runCatching {
            val power = context.getSystemService(PowerManager::class.java)
                ?: return "System thermal status unavailable"
            val status = power.currentThermalStatus
            val label = when (status) {
                PowerManager.THERMAL_STATUS_NONE -> "No throttling reported"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light throttling"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate throttling"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe throttling"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown requested"
                else -> "Unknown"
            }
            "System thermal status: $label." +
                if (status in PowerManager.THERMAL_STATUS_SEVERE..PowerManager.THERMAL_STATUS_SHUTDOWN)
                    "\nAndroid reports significant overheating. Pause demanding activity and let the device cool."
                else "\nSensor support varies by device; this is not a CPU temperature reading."
        }.getOrDefault("System thermal status unavailable")
    }

    // Lists each app-accessible display's current mode and advertised modes, without claiming HDMI or CEC support.
    private fun displays(): String = runCatching {
        val manager = context.getSystemService(DisplayManager::class.java)
        manager?.displays?.joinToString("\n\n") { display ->
            val mode = display.mode
            val role = if (display.displayId == Display.DEFAULT_DISPLAY) "Default" else "Additional"
            val modes = display.supportedModes.map {
                "${it.physicalWidth} × ${it.physicalHeight} @ ${String.format(Locale.getDefault(), "%.1f", it.refreshRate)} Hz"
            }.distinct().joinToString("; ")
            "$role · ${display.name}\nCurrent: ${mode.physicalWidth} × ${mode.physicalHeight} @ ${String.format(Locale.getDefault(), "%.1f", mode.refreshRate)} Hz\nAvailable: $modes"
        }?.ifEmpty { "No displays reported" } ?: "Display data unavailable"
    }.getOrDefault("Display data unavailable")

    // Lists Android input sources and virtual-device markers instead of guessing a remote's hardware model.
    private fun inputs(): String = runCatching {
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.joinToString("\n") { device ->
            val sources = buildList {
                if (device.supportsSource(InputDevice.SOURCE_KEYBOARD)) add("keyboard")
                if (device.supportsSource(InputDevice.SOURCE_DPAD)) add("directional keys")
                if (device.supportsSource(InputDevice.SOURCE_MOUSE)) add("mouse")
                if (device.supportsSource(InputDevice.SOURCE_GAMEPAD)) add("gamepad")
                if (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) add("touchscreen")
                if (device.supportsSource(InputDevice.SOURCE_JOYSTICK)) add("joystick")
            }.ifEmpty { listOf("other input") }.joinToString(", ")
            "${device.name} · $sources${if (device.isVirtual) " · virtual" else ""}"
        }.ifEmpty { "No input devices reported" }
    }.getOrDefault("Input device data unavailable")
}

