package dev.dsh.pocket.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.concurrent.atomic.AtomicBoolean

/** How the pocket frames are reaching the desktop. */
enum class Route {
    /** Loopback / local-dev WebSocket only — not user-facing. */
    WsLocal,
    IrohDirect,
    IrohRelay,
    ;

    /** Short label for the connections chip (no emoji). */
    val label: String get() = when (this) {
        WsLocal -> "local"
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
 * Try factories in order; return the first that opens.
 * Primary path is iroh; WS is only used for loopback/dev.
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

/** OkHttp WebSocket frame transport (loopback/dev only). */
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
            routeHint: Route = Route.WsLocal,
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

        fun factory(url: String): TransportFactory = TransportFactory {
            open(url, Route.WsLocal)
        }
    }
}

/**
 * Ordered transports for a profile: **iroh first**, then loopback WS if configured for tests.
 */
fun transportPlan(
    nodeId: String?,
    irohFactory: TransportFactory?,
    loopbackWsUrl: String? = null,
): List<TransportFactory> {
    val list = mutableListOf<TransportFactory>()
    if (!nodeId.isNullOrBlank() && irohFactory != null) list += irohFactory
    if (!loopbackWsUrl.isNullOrBlank()) list += WsTransport.factory(loopbackWsUrl)
    return list
}
