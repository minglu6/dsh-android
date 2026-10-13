package dev.dsh.pocket.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DiscoveredHost(
    val deviceName: String,
    val host: String,
    val port: Int,
    val instanceId: String,
)

/**
 * Bounded Android DNS-SD listener for metadata-only DSH Pocket advertisements.
 *
 * Adapted from dsh-mobile NsdDiscovery.kt
 * (https://github.com/saya-ch/dsh-mobile), Apache-2.0.
 */
object NsdDiscovery {
    fun scan(context: Context, timeoutMs: Long, canceled: AtomicBoolean? = null): List<DiscoveredHost> {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifi?.createMulticastLock("dsh-pocket-discovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val active = AtomicBoolean(true)
        val resolving = AtomicBoolean(false)
        val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
        val found = ConcurrentHashMap<String, DiscoveredHost>()
        val started = CountDownLatch(1)
        lateinit var resolveNext: () -> Unit
        resolveNext = {
            val service = pending.poll()
            if (!active.get() || service == null) {
                resolving.set(false)
            } else {
                @Suppress("DEPRECATION")
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving.set(false)
                        resolveNext()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        parse(serviceInfo)?.let { found[it.instanceId] = it }
                        resolving.set(false)
                        resolveNext()
                    }
                })
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = started.countDown()
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = started.countDown()
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!active.get()) return
                pending.add(serviceInfo)
                if (resolving.compareAndSet(false, true)) resolveNext()
            }
        }
        return try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            started.await(1, TimeUnit.SECONDS)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end && canceled?.get() != true) {
                Thread.sleep(200)
            }
            found.values.toList()
        } catch (_: Exception) {
            found.values.toList()
        } finally {
            active.set(false)
            runCatching { manager.stopServiceDiscovery(listener) }
            if (multicastLock?.isHeld == true) multicastLock.release()
        }
    }

    internal fun parse(info: NsdServiceInfo): DiscoveredHost? = runCatching {
        val attrs = info.attributes
        val protocol = attrs["protocol"]?.toString(Charsets.UTF_8)?.toIntOrNull()
        val instanceId = attrs["instanceId"]?.toString(Charsets.UTF_8).orEmpty()
        val deviceName = attrs["deviceName"]?.toString(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() && it.length <= 63 } ?: return@runCatching null
        val host = attrs["host"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            ?: info.host?.hostAddress
            ?: return@runCatching null
        val port = attrs["port"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: info.port
        if (protocol != 1 || !INSTANCE_ID.matches(instanceId) || port !in 1..65535) return@runCatching null
        DiscoveredHost(deviceName, host, port, instanceId)
    }.getOrNull()

    /** Parse TXT-style announcement JSON used in unit tests / UDP fallbacks. */
    internal fun parseAnnouncement(source: String, remoteAddress: String?): DiscoveredHost? = runCatching {
        val body = kotlinx.serialization.json.Json.parseToJsonElement(source).jsonObject
        // Reject secret-bearing payloads: only the five public discovery keys are allowed.
        if (body.keys != setOf("deviceName", "host", "port", "protocol", "instanceId")) return@runCatching null
        if (body["protocol"]?.jsonPrimitive?.intOrNull != 1) return@runCatching null
        val instanceId = body["instanceId"]?.jsonPrimitive?.content.orEmpty()
        if (!INSTANCE_ID.matches(instanceId)) return@runCatching null
        val deviceName = body["deviceName"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() && it.length <= 63 } ?: return@runCatching null
        val host = body["host"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val port = body["port"]?.jsonPrimitive?.intOrNull ?: return@runCatching null
        if (port !in 1..65535 || remoteAddress == null || host != remoteAddress) return@runCatching null
        DiscoveredHost(deviceName, host, port, instanceId)
    }.getOrNull()

    const val SERVICE_TYPE = "_dshpocket._tcp."
    val INSTANCE_ID = Regex("^[a-f0-9]{64}$")
}
