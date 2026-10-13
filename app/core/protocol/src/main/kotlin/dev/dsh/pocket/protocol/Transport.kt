package dev.dsh.pocket.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.concurrent.atomic.AtomicBoolean

/** How the pocket frames are reaching the desktop. */
enum class Route {
    /** Loopback WebSocket — Termux sidecar on-device, or local-dev. */
    WsLocal,
    /** Remote WebSocket over Tailscale (MagicDNS or CGNAT 100.x). */
    WsTailscale,
    /** Remote WebSocket that is not clearly Tailscale (LAN / other). */
    WsLan,
    ;

    /** Short label for the connections chip (no emoji). */
    val label: String get() = when (this) {
        WsLocal -> "本机"
        WsTailscale -> "Tailscale"
        WsLan -> "LAN"
    }
}

/**
 * One bidirectional channel of pocket JSON text frames (WS text frames).
 * Auth (hello/pair tokens) is identical on every path.
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
 * Try factories in order; return the first that opens.
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

fun isLoopbackHost(host: String): Boolean =
    host == "127.0.0.1" || host == "localhost" || host == "::1" || host.equals("0:0:0:0:0:0:0:1", ignoreCase = true)

fun isTailscaleHost(host: String): Boolean {
    if (host.endsWith(".ts.net")) return true
    val p = host.split('.').mapNotNull { it.toIntOrNull() }
    return p.size == 4 && p[0] == 100 && p[1] in 64..127
}

fun classifyWsRoute(host: String): Route {
    if (isLoopbackHost(host)) return Route.WsLocal
    if (isTailscaleHost(host)) return Route.WsTailscale
    return Route.WsLan
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
    /** Negotiated Sec-WebSocket-Extensions from the upgrade response (e.g. permessage-deflate). */
    @Volatile var negotiatedExtensions: String? = null; private set

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
        /** OkHttp offers permessage-deflate by default; match pocket server threshold (~1 KiB). */
        const val MIN_MESSAGE_TO_COMPRESS = 1024L

        suspend fun open(
            url: String,
            routeHint: Route = Route.WsLocal,
            http: OkHttpClient = PocketClient.defaultHttp(),
            timeoutMs: Long = 120_000,
        ): WsTransport = withTimeout(timeoutMs) {
            val t = WsTransport(url, routeHint, http)
            val opened = CompletableDeferred<Unit>()
            t.ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    t.negotiatedExtensions = response.header("Sec-WebSocket-Extensions")
                    opened.complete(Unit)
                }
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

        /** Loopback / local-dev factory. */
        fun factory(url: String): TransportFactory = TransportFactory {
            open(url, Route.WsLocal)
        }

        /** Remote WS factory (Tailscale / LAN); route label from [host]. */
        fun factory(url: String, host: String): TransportFactory = TransportFactory {
            open(url, classifyWsRoute(host))
        }
    }
}

/** Build a single host:port WebSocket factory (Tailscale or LAN). */
fun transportPlan(host: String?, port: Int): List<TransportFactory> {
    if (host.isNullOrBlank() || port !in 1..65535) return emptyList()
    val url = "ws://${if (':' in host) "[$host]" else host}:$port"
    return listOf(WsTransport.factory(url, host))
}
