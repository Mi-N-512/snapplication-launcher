package app.snapplication.launcher

/*
 * Resolves a reconnected remote against saved assignments without relying on its temporary input ID.
 * RemoteControls supplies persisted hardware identity and Android's connected devices. No UI lives here.
 * Exact descriptors win. A changed descriptor may reuse one unambiguous same-name hardware profile;
 * legacy assignments have no vendor/product IDs and retain a conservative unique-name fallback.
 * Extend identity checks here rather than assigning an unknown remote another device's shortcuts.
 */
internal object RemoteDeviceMatch {
    data class Profile(val descriptor: String, val name: String, val vendor: Int = -1, val product: Int = -1)

    // Never guess between two saved profiles or two connected devices with the same identity.
    fun resolve(current: Profile, saved: List<Profile>, connected: List<Profile>): String? {
        if (saved.any { it.descriptor == current.descriptor }) return current.descriptor
        if (current.name.isBlank()) return null
        fun compatible(other: Profile): Boolean = other.name == current.name &&
            (other.vendor < 0 || (other.vendor == current.vendor && other.product == current.product))
        if (connected.count { it.name == current.name && it.vendor == current.vendor && it.product == current.product } != 1) return null
        return saved.filter { compatible(it) && connected.none { live -> live.descriptor == it.descriptor } }
            .singleOrNull()?.descriptor
    }
}
