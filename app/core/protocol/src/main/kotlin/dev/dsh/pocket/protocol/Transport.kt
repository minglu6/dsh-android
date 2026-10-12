package dev.dsh.pocket.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.concurrent.atomic.AtomicBoolean

/** How the pocket frames are reaching the desktop. */
enum class Route {
    WsTailscale,
    WsLan,
    IrohDirect,
    IrohRelay,
    ;

    /** Short label for the connections chip (no emoji). */
    val label: String get() = when (this) {
        WsTailscale -> "Tailscale"
        WsLan -> "LAN"
        IrohDirect -> "iroh"
        IrohRelay -> "iroh relay"
    }

    val isRelay: Boolean get() = this == IrohRelay
}

/**
 * One bidirectional channel of pocket JSON text frames (WS text frames or
 * iroh newline-delimited lines). Auth (hello/pair tokens) is identical on every transport.
 */
interface FrameTransport {
    val route: Route
    suspend fun send(text: String)
    /** Incoming frames; completes when the transport closes. */
    val incoming: Flow<String>
    fun close()
    val closeReason: String?
}

/** Open a [FrameTransport] for a profile. Implementations may throw on failure. */
fun interface TransportFactory {
    suspend fun open(): FrameTransport
}

/**
 * Connection order: Tailscale/direct WS first, then iroh (path upgrades direct→relay).
 * Returns the first factory that opens successfully.
 */
suspend fun dialInOrder(factories: List<TransportFactory>): FrameTransport {
    require(factories.isNotEmpty()) { "no transports" }
    var last: Throwable? = null
    for (f in factories) {
        try {
            return f.open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            last = e
        }
    }
    throw last ?: PocketError("transport", "all transports failed")
}

fun classifyWsRoute(host: String): Route {
    if (host.endsWith(".ts.net")) return Route.WsTailscale
    val p = host.split('.').mapNotNull { it.toIntOrNull() }
    return if (p.size == 4 && p[0] == 100 && p[1] in 64..127) Route.WsTailscale else Route.WsLan
}

/** OkHttp WebSocket frame transport. */
class WsTransport private constructor(
    private val url: String,
    override val route: Route,
    private val http: OkHttpClient,
) : FrameTransport {
    private val ch = Channel<String>(capacity = 64)
    override val incoming: Flow<String> = ch.receiveAsFlow()
    private var ws: WebSocket? = null
    private val closed = AtomicBoolean(false)
    @Volatile override var closeReason: String? = null; private set

    override suspend fun send(text: String) {
        val socket = ws ?: throw PocketError("transport", "not connected")
        if (!socket.send(text)) throw PocketError("transport", "send failed")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ws?.close(1000, "bye"); ws = null
        ch.close()
    }

    private fun fail(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        ws = null
        ch.close(PocketError("transport", reason))
    }

    companion object {
        suspend fun open(
            url: String,
            routeHint: Route,
            http: OkHttpClient = PocketClient.defaultHttp(),
            timeoutMs: Long = 8_000,
        ): WsTransport = withTimeout(timeoutMs) {
            val t = WsTransport(url, routeHint, http)
            val opened = CompletableDeferred<Unit>()
            t.ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    t.ch.trySend(text)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { t.fail("closed $code $reason") }
                override fun onFailure(webSocket: WebSocket, th: Throwable, response: Response?) {
                    if (!opened.isCompleted) opened.completeExceptionally(PocketError("transport", th.message ?: th.toString()))
                    else t.fail(th.message ?: th.toString())
                }
            })
            opened.await()
            t
        }

        fun factory(url: String, host: String): TransportFactory = TransportFactory {
            open(url, classifyWsRoute(host))
        }
    }
}

/**
 * Builds the ordered transport list for a profile.
 * [irohFactory] is omitted when null (native lib missing or no node id).
 */
fun transportPlan(
    host: String,
    port: Int,
    nodeId: String?,
    irohFactory: TransportFactory?,
): List<TransportFactory> {
    val url = "ws://${if (':' in host) "[$host]" else host}:$port"
    val list = mutableListOf(WsTransport.factory(url, host))
    if (nodeId != null && irohFactory != null) list += irohFactory
    return list
}
