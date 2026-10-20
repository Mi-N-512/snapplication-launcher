package app.snapplication.launcher

import org.junit.Assert.*
import org.junit.Test

/* Tests reconnect identity selection used by RemoteControls; no Android device or UI is mocked.
 * Includes old saved profiles, duplicate receivers and mismatched hardware to prevent wrong-key reuse. */
class RemoteDeviceMatchTest {
    private val old = RemoteDeviceMatch.Profile("old", "Receiver", 12, 34)
    private val now = old.copy(descriptor = "new")
    @Test fun reconnectRetainsProfile() { assertEquals("old", RemoteDeviceMatch.resolve(now, listOf(old), listOf(now))) }
    @Test fun legacyProfileReconnects() { assertEquals("old", RemoteDeviceMatch.resolve(now, listOf(old.copy(vendor = -1, product = -1)), listOf(now))) }
    @Test fun exactDescriptorWins() { assertEquals("new", RemoteDeviceMatch.resolve(now, listOf(old, now), listOf(now))) }
    @Test fun differentHardwareIsNotReused() { assertNull(RemoteDeviceMatch.resolve(now.copy(product = 99), listOf(old), listOf(now.copy(product = 99)))) }
    @Test fun twoReceiversAreAmbiguous() { assertNull(RemoteDeviceMatch.resolve(now, listOf(old), listOf(now, now.copy(descriptor = "second")))) }
    @Test fun twoSavedProfilesAreAmbiguous() { assertNull(RemoteDeviceMatch.resolve(now, listOf(old, old.copy(descriptor = "other")), listOf(now))) }
    @Test fun liveOriginalIsNotReused() { assertNull(RemoteDeviceMatch.resolve(now, listOf(old), listOf(now, old))) }
}
