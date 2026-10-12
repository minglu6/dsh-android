package dev.dsh.pocket.protocol

import kotlinx.coroutines.runBlocking
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
