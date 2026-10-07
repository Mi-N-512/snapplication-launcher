package app.snapplication.launcher

/*
 * Converts Android's documented thermal status codes to notices consumed by ThermalMonitor and MainActivity.
 *
 * Related classes: ThermalMonitor, MainActivity and ThermalNoticeTest.
 * UI: No UI; provides optional text for the visible thermal banner.
 * Edit or extend: Edit wording/severity mapping here and retain tests for unknown or unsupported status values.
 */
object ThermalNotice {
    // Warns only for severe through shutdown states; missing or future unknown values remain unclassified.
    fun forStatus(status: Int?): String? = when (status) {
        3 -> "Device is overheating. Pause demanding activity and let it cool."
        4, 5 -> "Device temperature is critical. Stop demanding activity and let it cool."
        6 -> "Android reports an imminent thermal shutdown. Let the device cool."
        else -> null
    }
}
