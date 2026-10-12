package dev.dsh.pocket.app

/**
 * Adapted from saya-ch/dsh-mobile (Apache-2.0) ConnectionRestorePolicy.kt.
 * Decides whether a network-available signal should force an immediate reconnect.
 */
object ConnectionRestorePolicy {
    /** Retry only when the active profile is offline or stuck connecting — never tear down a live Online socket. */
    fun shouldReconnect(dot: Dot?): Boolean = when (dot) {
        Dot.Offline, Dot.Connecting -> true
        Dot.Online, Dot.Unauthorized, null -> false
    }
}
