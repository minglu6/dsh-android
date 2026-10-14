package dev.dsh.pocket.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.concurrent.atomic.AtomicLong

/** How the pocket frames are reaching the desktop. */
enum class Route {
    /** Loopback / local-dev WebSocket only — not user-facing. */
    WsLocal,
    /** Remote WebSocket over Tailscale (MagicDNS or CGNAT 100.x). */
    WsTailscale,
    /** Remote WebSocket that is not clearly Tailscale (LAN / other). */
    WsLan,
    ;

    /** Short label for the connections chip (no emoji). */
    val label: String get() = when (this) {
        WsLocal -> "local"
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
    private val ch = Channel<String>(capacity = MAX_QUEUED_FRAMES)
    private val queuedBytes = AtomicLong()
    private val lock = Any()
    override val incoming: Flow<String> = flow {
        for (text in ch) {
            queuedBytes.addAndGet(-text.length.toLong() * 2)
            emit(text)
        }
    }
    @Volatile private var ws: WebSocket? = null
    private var closed = false
    @Volatile override var closeReason: String? = null; private set
    /** Negotiated Sec-WebSocket-Extensions from the upgrade response (e.g. permessage-deflate). */
    @Volatile var negotiatedExtensions: String? = null; private set

    override suspend fun send(text: String) {
        val socket = ws ?: throw PocketError("transport", "not connected")
        if (!socket.send(text)) throw PocketError("transport", "send failed")
    }

    override fun close() = finish(null)

    private fun fail(reason: String) = finish(reason)

    private fun finish(reason: String?) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        closeReason = reason
        val socket = ws
        ws = null
        ch.close(reason?.let { PocketError("transport", it) })
        // A failed connection cannot supply a contiguous stream. Release its backlog now.
        while (true) {
            val text = ch.tryReceive().getOrNull() ?: break
            queuedBytes.addAndGet(-text.length.toLong() * 2)
        }
        if (reason == null) socket?.close(1000, "bye") else socket?.cancel()
        Unit
    }

    private fun receive(text: String) = synchronized(lock) {
        if (closed) return@synchronized
        val bytes = text.length.toLong() * 2
        if (bytes > MAX_QUEUED_BYTES || queuedBytes.addAndGet(bytes) > MAX_QUEUED_BYTES) {
            if (bytes <= MAX_QUEUED_BYTES) queuedBytes.addAndGet(-bytes)
            fail("incoming buffer overflow; reconnect required")
        } else if (ch.trySend(text).isFailure) {
            queuedBytes.addAndGet(-bytes)
            fail("incoming buffer overflow; reconnect required")
        }
    }

    companion object {
        /** OkHttp offers permessage-deflate by default; match pocket server threshold (~1 KiB). */
        const val MIN_MESSAGE_TO_COMPRESS = 1024L
        /** Absorb brief Android scheduling pauses without abandoning a healthy high-rate stream. */
        internal const val MAX_QUEUED_FRAMES = 512
        /** UTF-16 payload budget, in addition to the frame-count bound. */
        internal const val MAX_QUEUED_BYTES = 16L * 1024 * 1024

        suspend fun open(
            url: String,
            routeHint: Route = Route.WsLocal,
            http: OkHttpClient = PocketClient.defaultHttp(),
            timeoutMs: Long = 120_000,
        ): WsTransport = withTimeout(timeoutMs) {
            val t = WsTransport(url, routeHint, http)
            val opened = CompletableDeferred<Unit>()
            val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    t.negotiatedExtensions = response.header("Sec-WebSocket-Extensions")
                    opened.complete(Unit)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    t.receive(text)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val message = "closed $code $reason"
                    t.fail(message)
                    opened.completeExceptionally(PocketError("transport", message))
                }
                override fun onFailure(webSocket: WebSocket, th: Throwable, response: Response?) {
                    val message = th.message ?: th.toString()
                    t.fail(message)
                    opened.completeExceptionally(PocketError("transport", message))
                }
            })
            synchronized(t.lock) {
                if (t.closed) socket.cancel() else t.ws = socket
            }
            try {
                opened.await()
                synchronized(t.lock) {
                    if (t.closed) throw PocketError("transport", t.closeReason ?: "closed")
                }
                t
            } catch (e: Throwable) {
                t.close()
                throw e
            }
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
