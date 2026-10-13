package dev.dsh.pocket.app

/**
 * Keeps LAN discovery usable when Android 13+ nearby-device access is declined.
 *
 * Adapted from dsh-mobile NearbyDiscoveryPermissionPolicy.kt
 * (https://github.com/saya-ch/dsh-mobile), Apache-2.0.
 */
object NearbyDiscoveryPermissionPolicy {
    fun shouldRequest(apiLevel: Int, granted: Boolean, previouslyDeclined: Boolean): Boolean =
        apiLevel >= 33 && !granted && !previouslyDeclined
}
