package app.snapplication.launcher

/*
Converts Android's documented thermal status codes to notices consumed by ThermalMonitor and
MainActivity.

This object is a shared helper and does not inherit from another launcher class.

status is Android's reported thermal severity. forStatus returns a warning for severe states and
null when no recognised warning applies. ThermalMonitor calls this shared helper and sends its text
to MainActivity. It has no changing state and does not measure temperature or control cooling.

Used by or used with: ThermalMonitor, MainActivity and ThermalNoticeTest. Screen controls: none;
provides optional text for the visible thermal banner. Changes belong here: Edit wording/severity
mapping here and retain tests for unknown or unsupported status values.
*/
object ThermalNotice {
    // Warns only for severe through shutdown states; missing or future unknown values remain
    // unclassified.
    fun forStatus(status: Int?): String? = when (status) {
        3 -> "Device is overheating. Pause demanding activity and let it cool."
        4, 5 -> "Device temperature is critical. Stop demanding activity and let it cool."
        6 -> "Android reports an imminent thermal shutdown. Let the device cool."
        else -> null
    }
}
