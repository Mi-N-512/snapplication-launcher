package app.snapplication.launcher

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/** Samples Android thermal status while MainActivity is visible and sends notice text to its banner. */
class ThermalMonitor(context: Context, private val onNotice: (String?) -> Unit) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastNotice: String? = null
    /** Performs one guarded sample and schedules another only while the activity is visible. */
    private val sample = object : Runnable {
        /** Treats unavailable sensors as unknown, and never displays a false healthy-device claim. */
        override fun run() {
            if (!running) return
            val status = if (Build.VERSION.SDK_INT >= 29) runCatching { power?.currentThermalStatus }.getOrNull() else null
            val notice = ThermalNotice.forStatus(status)
            if (notice != lastNotice) { lastNotice = notice; onNotice(notice) }
            handler.postDelayed(this, 15_000)
        }
    }

    /** Begins with an immediate sample; repeated lifecycle starts cannot create duplicate polling loops. */
    fun start() {
        if (running) return
        running = true
        sample.run()
    }

    /** Removes callbacks and stale warning text when the launcher leaves the foreground. */
    fun stop() {
        running = false
        handler.removeCallbacks(sample)
        lastNotice = null
        onNotice(null)
    }
}
