package dev.dsh.pocket.app.iroh

import dev.dsh.pocket.protocol.FrameTransport
import dev.dsh.pocket.protocol.PocketError
import dev.dsh.pocket.protocol.Route
import dev.dsh.pocket.protocol.TransportFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * iroh P2P transport. After connect, [waitPath] upgrades the reported route from
 * relay→direct when NAT hole-punch succeeds (same 8s window as tether/pocket-host).
 */
class IrohTransport private constructor(
    private val ep: Long,
    private val sess: Long,
    initialRoute: Route,
    private val ownsEndpoint: Boolean,
) : FrameTransport {
    @Volatile override var route: Route = initialRoute; private set
    private val ch = Channel<String>(capacity = 64)
    override val incoming: Flow<String> = ch.receiveAsFlow()
    private val closed = AtomicBoolean(false)
    @Volatile override var closeReason: String? = null; private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            while (isActive && !closed.get()) {
                val line = withContext(Dispatchers.IO) {
                    IrohNative.sessionRecv(sess, 500)
                }
                when {
                    line == null -> {
                        fail(IrohNative.lastError() ?: "iroh recv failed")
                        break
                    }
                    line.isEmpty() -> continue
                    else -> ch.send(line)
                }
            }
        }
        scope.launch {
            val kind = withContext(Dispatchers.IO) { IrohNative.sessionWaitPath(sess, 8_000) }
            if (!closed.get()) {
                route = when (kind) {
                    "direct" -> Route.IrohDirect
                    "relay" -> Route.IrohRelay
                    else -> route
                }
            }
        }
    }

    override suspend fun send(text: String) {
        val rc = withContext(Dispatchers.IO) { IrohNative.sessionSend(sess, text) }
        if (rc != 0) throw PocketError("transport", IrohNative.lastError() ?: "iroh send failed")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        IrohNative.sessionClose(sess)
        if (ownsEndpoint) IrohNative.endpointClose(ep)
        ch.close()
    }

    private fun fail(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        scope.cancel()
        runCatching { IrohNative.sessionClose(sess) }
        if (ownsEndpoint) runCatching { IrohNative.endpointClose(ep) }
        ch.close(PocketError("transport", reason))
    }

    companion object {
        fun factory(peerNodeId: String, dataDir: File, connectTimeoutMs: Long = 12_000): TransportFactory? {
            if (!IrohNative.available) return null
            return TransportFactory {
                open(peerNodeId, dataDir, connectTimeoutMs)
            }
        }

        suspend fun open(peerNodeId: String, dataDir: File, connectTimeoutMs: Long = 12_000): IrohTransport =
            withContext(Dispatchers.IO) {
                IrohNative.requireAvailable()
                dataDir.mkdirs()
                val ep = IrohNative.endpointCreate(dataDir.absolutePath)
                if (ep == 0L) error(IrohNative.lastError() ?: "endpoint create failed")
                val sess = IrohNative.endpointConnect(ep, peerNodeId, connectTimeoutMs)
                if (sess == 0L) {
                    IrohNative.endpointClose(ep)
                    error(IrohNative.lastError() ?: "iroh connect failed")
                }
                // Fresh connections usually start on relay until hole-punch upgrades.
                IrohTransport(ep, sess, Route.IrohRelay, ownsEndpoint = true)
            }
    }
}
