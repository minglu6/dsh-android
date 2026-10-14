package dev.dsh.pocket.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportTest {
    private class FakeTransport(override val route: Route) : FrameTransport {
        override suspend fun send(text: String) {}
        override val incoming = kotlinx.coroutines.flow.emptyFlow<String>()
        override fun close() {}
        override val closeReason: String? = null
    }

    private suspend fun withSocket(
        block: suspend (WsTransport, WebSocket, CompletableDeferred<Unit>) -> Unit,
    ) {
        val server = MockWebServer()
        val peer = CompletableDeferred<WebSocket>()
        val disconnected = CompletableDeferred<Unit>()
        val http = PocketClient.defaultHttp()
        var transport: WsTransport? = null
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { peer.complete(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                disconnected.complete(Unit)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                disconnected.complete(Unit)
            }
        }))
        server.start()
        try {
            val connected = WsTransport.open(server.url("/").toString().replace("http://", "ws://"), http = http)
            transport = connected
            block(connected, withTimeout(5_000) { peer.await() }, disconnected)
        } finally {
            transport?.close()
            if (peer.isCompleted) peer.await().close(1000, "test complete")
            server.shutdown()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test fun stalledConsumerOverflowClosesSocketAndFailsInsteadOfLosingTerminal() = runBlocking {
        withSocket { transport, peer, disconnected ->
            val first = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val received = mutableListOf<String>()
            val consumer = async {
                assertFailsWith<PocketError> {
                    transport.incoming.collect {
                        received += it
                        first.complete(Unit)
                        release.await()
                    }
                }
            }
            try {
                assertTrue(peer.send("first"))
                withTimeout(5_000) { first.await() }
                repeat(WsTransport.MAX_QUEUED_FRAMES) { assertTrue(peer.send("chunk-$it")) }
                assertTrue(peer.send("turn/end"))
                withTimeout(5_000) { disconnected.await() }
                assertTrue(transport.closeReason.orEmpty().contains("overflow"))
                release.complete(Unit)
                assertEquals("transport", withTimeout(5_000) { consumer.await() }.code)
                assertEquals(listOf("first"), received)
                assertFailsWith<PocketError> { transport.send("after overflow") }
            } finally {
                release.complete(Unit)
                consumer.cancel()
            }
        }
    }

    @Test fun startupBacklogHasByteBudgetAsWellAsFrameLimit() = runBlocking {
        withSocket { transport, peer, disconnected ->
            // Only nine frames: comfortably below the count limit, above the UTF-16 budget.
            val frame = "x".repeat(1024 * 1024)
            repeat(9) { assertTrue(peer.send(frame)) }
            withTimeout(5_000) { disconnected.await() }
            val error = assertFailsWith<PocketError> { transport.incoming.toList() }
            assertEquals("transport", error.code)
            assertTrue(error.message.orEmpty().contains("overflow"))
        }
    }

    @Test fun isLoopback() {
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("localhost"))
        assertTrue(isLoopbackHost("::1"))
        assertEquals(false, isLoopbackHost("100.64.1.2"))
        assertEquals(false, isLoopbackHost("192.168.1.1"))
    }

    @Test fun classifyWsRouteTailscale() {
        assertEquals(Route.WsTailscale, classifyWsRoute("100.106.128.87"))
        assertEquals(Route.WsTailscale, classifyWsRoute("mac.tail1.ts.net"))
        assertEquals(Route.WsLan, classifyWsRoute("192.168.1.10"))
        assertEquals(Route.WsLocal, classifyWsRoute("127.0.0.1"))
    }

    @Test fun transportPlanHostPort() {
        val plan = transportPlan("100.64.1.2", 7690)
        assertEquals(1, plan.size)
        assertTrue(transportPlan("", 7690).isEmpty())
        assertTrue(transportPlan("100.64.1.2", 0).isEmpty())
        assertTrue(transportPlan(null, 7690).isEmpty())
    }

    @Test fun dialInOrderUsesFirstSuccess() = runBlocking {
        var hits = 0
        val fail = TransportFactory {
            hits++
            throw PocketError("transport", "fail")
        }
        val ok = TransportFactory {
            hits++
            FakeTransport(Route.WsLan)
        }
        val t = dialInOrder(listOf(fail, ok))
        assertEquals(Route.WsLan, t.route)
        assertEquals(2, hits)
    }

    @Test fun dialInOrderEmptyThrows() = runBlocking {
        assertFailsWith<IllegalArgumentException> { dialInOrder(emptyList()) }
    }

    @Test fun routeLabels() {
        assertEquals("local", Route.WsLocal.label)
        assertEquals("Tailscale", Route.WsTailscale.label)
        assertEquals("LAN", Route.WsLan.label)
    }

    @Test fun pairingLinkHostPortOnly() {
        assertEquals(
            PairingLink("100.1.2.3", 7690, "123456"),
            PairingLink.parse("dshpocket://pair?h=100.1.2.3:7690&c=123456"),
        )
        assertNull(PairingLink.parse("dshpocket://pair?n=abcdefghijklmnopqrst&c=123456"))
        assertNull(PairingLink.parse("abcdefghijklmnopqrst#123456"))
    }
}
