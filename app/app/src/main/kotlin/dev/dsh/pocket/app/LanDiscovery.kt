package dev.dsh.pocket.app

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concurrent passive NSD discovery for nearby pocket hosts.
 *
 * Adapted from dsh-mobile LanDiscovery.kt
 * (https://github.com/saya-ch/dsh-mobile), Apache-2.0 — NSD path only (no GMS).
 */
object LanDiscovery {
    fun scan(context: Context, timeoutMs: Long = 3_000L, canceled: AtomicBoolean? = null): List<DiscoveredHost> {
        if (canceled?.get() == true) return emptyList()
        return mergeByInstance(NsdDiscovery.scan(context, timeoutMs, canceled))
    }

    fun mergeByInstance(found: List<DiscoveredHost>): List<DiscoveredHost> =
        found.fold(linkedMapOf<String, DiscoveredHost>()) { entries, harness ->
            entries.putIfAbsent(harness.instanceId, harness)
            entries
        }.values.toList()

    /**
     * When a paired profile's instance appears with a new host:port, return an updated copy.
     * Does not change credentials — only the reachable address.
     */
    fun applyAddressUpdate(profile: Profile, discovered: DiscoveredHost): Profile? {
        if (profile.instanceId.isBlank() || profile.instanceId != discovered.instanceId) return null
        if (profile.host == discovered.host && profile.port == discovered.port) return null
        if (discovered.port !in 1..65535 || discovered.host.isBlank()) return null
        return profile.copy(host = discovered.host, port = discovered.port)
    }
}
