package dev.dsh.pocket.app

/**
 * Applies reachability observations only to the authorization that requested them.
 *
 * Adapted from dsh-mobile PairedDeviceStatusPolicy.kt
 * (https://github.com/saya-ch/dsh-mobile), Apache-2.0.
 */
object PairedDeviceStatusPolicy {
    fun beginCheck(current: Dot?): Dot =
        if (current == Dot.Unauthorized) Dot.Unauthorized else Dot.Connecting

    fun mayApplyResult(current: Profile, requested: Profile): Boolean =
        statusOf(current) != Dot.Unauthorized &&
            current.id == requested.id &&
            current.host == requested.host &&
            current.port == requested.port &&
            current.instanceId == requested.instanceId &&
            (requested.deviceId.isNullOrEmpty() || current.deviceId == requested.deviceId)

    fun applyProbe(
        currentDot: Dot?,
        current: Profile,
        requested: Profile,
        reachable: Boolean,
        revoked: Boolean,
    ): Dot {
        if (!mayApplyResult(current, requested)) return currentDot ?: Dot.Offline
        return when {
            revoked -> Dot.Unauthorized
            reachable -> Dot.Online
            else -> Dot.Offline
        }
    }

    private fun statusOf(p: Profile): Dot =
        if (p.needsRepair) Dot.Unauthorized else Dot.Offline
}
