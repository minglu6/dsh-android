package dev.dsh.pocket.app

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Deduplicates per-device probes without queuing another network job on every list tick.
 * At most two probes occupy the executor, leaving capacity for an explicit connection.
 *
 * Adapted from dsh-mobile DeviceStatusPoller.kt
 * (https://github.com/saya-ch/dsh-mobile), Apache-2.0.
 */
class DeviceStatusPoller(
    private val executor: Executor,
    private val dispatch: (() -> Unit) -> Unit,
    private val probe: (Profile) -> ProbeOutcome,
    private val onResult: (Profile, Result<ProbeOutcome>) -> Unit,
    private val maxConcurrent: Int = 2,
) {
    data class ProbeOutcome(val reachable: Boolean, val revoked: Boolean = false)

    private class Request(val device: Profile, val owner: Long)

    private val pending = linkedMapOf<String, Profile>()
    private val desired = linkedMapOf<String, Profile>()
    private val running = mutableSetOf<Request>()
    private var owner = 0L
    private var active = false

    init { require(maxConcurrent in 1..2) }

    /** Refresh pending snapshots while retaining current slow probes and their result owners. */
    fun refresh(devices: List<Profile>) {
        synchronized(this) {
            active = true
            val eligible = devices.filter { !it.needsRepair }.associateBy { it.id }
            desired.clear()
            desired.putAll(eligible)
            pending.keys.retainAll(eligible.keys)
            for (device in eligible.values) {
                val current = running.any { request ->
                    request.owner == owner && PairedDeviceStatusPolicy.mayApplyResult(device, request.device)
                }
                if (current) pending.remove(device.id) else pending[device.id] = device
            }
        }
        pump()
    }

    /** Remove waiting work and withdraw callbacks; running network operations retain their slots. */
    fun stop() {
        synchronized(this) {
            active = false
            owner += 1
            pending.clear()
            desired.clear()
        }
    }

    private fun pump() {
        val requests = synchronized(this) {
            buildList {
                while (active && running.size < maxConcurrent && pending.isNotEmpty()) {
                    val entry = pending.entries.firstOrNull { entry ->
                        running.none { it.device.id == entry.key }
                    } ?: break
                    pending.remove(entry.key)
                    val request = Request(entry.value, owner)
                    running.add(request)
                    add(request)
                }
            }
        }
        for (request in requests) {
            try {
                executor.execute {
                    val stillOwned = synchronized(this) { owns(request) }
                    val result = if (stillOwned) runCatching { probe(request.device) } else null
                    finished(request, result)
                }
            } catch (failure: RejectedExecutionException) {
                finished(request, Result.failure(failure))
            }
        }
    }

    private fun finished(request: Request, result: Result<ProbeOutcome>?) {
        synchronized(this) { running.remove(request) }
        if (result != null) dispatch {
            if (synchronized(this) { owns(request) }) onResult(request.device, result)
        }
        pump()
    }

    private fun owns(request: Request): Boolean = active && request.owner == owner &&
        desired[request.device.id]?.let { PairedDeviceStatusPolicy.mayApplyResult(it, request.device) } == true
}
