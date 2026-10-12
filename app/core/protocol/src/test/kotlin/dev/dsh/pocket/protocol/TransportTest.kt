package dev.dsh.pocket.protocol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test fun transportPlanIrohExclusive() {
        val iroh = TransportFactory { FakeTransport(Route.IrohDirect) }
        val plan = transportPlan(RemoteTransport.Iroh, nodeId = "nodeabc", irohFactory = iroh)
        assertEquals(1, plan.size)
        val withLocal = transportPlan(
            RemoteTransport.Iroh, nodeId = "nodeabc", irohFactory = iroh, loopbackWsUrl = "ws://127.0.0.1:7690",
        )
        assertEquals(2, withLocal.size)
        assertTrue(transportPlan(RemoteTransport.Iroh, nodeId = null, irohFactory = iroh).isEmpty())
        assertTrue(transportPlan(RemoteTransport.Iroh, nodeId = "nodeabc", irohFactory = null).isEmpty())
    }

    @Test fun transportPlanTailscaleExclusive() {
        val plan = transportPlan(RemoteTransport.Tailscale, host = "100.64.1.2", port = 7690)
        assertEquals(1, plan.size)
        // Missing host/port → empty; never silently adds iroh.
        assertTrue(transportPlan(RemoteTransport.Tailscale, host = "", port = 7690).isEmpty())
        assertTrue(transportPlan(RemoteTransport.Tailscale, host = "100.64.1.2", port = 0).isEmpty())
        val iroh = TransportFactory { FakeTransport(Route.IrohDirect) }
        val stillWsOnly = transportPlan(
            RemoteTransport.Tailscale, host = "100.64.1.2", port = 7690, irohFactory = iroh, nodeId = "abc",
        )
        assertEquals(1, stillWsOnly.size)
    }

    @Test fun dialInOrderUsesFirstSuccess() = runBlocking {
        var hits = 0
        val fail = TransportFactory {
            hits++
            throw PocketError("transport", "fail")
        }
        val ok = TransportFactory {
            hits++
            FakeTransport(Route.IrohRelay)
        }
        val t = dialInOrder(listOf(fail, ok))
        assertEquals(Route.IrohRelay, t.route)
        assertEquals(2, hits)
    }

    @Test fun dialInOrderEmptyThrows() = runBlocking {
        assertFailsWith<IllegalArgumentException> { dialInOrder(emptyList()) }
    }

    @Test fun routeLabels() {
        assertEquals("local", Route.WsLocal.label)
        assertEquals("Tailscale", Route.WsTailscale.label)
        assertEquals("LAN", Route.WsLan.label)
        assertEquals("iroh", Route.IrohDirect.label)
        assertEquals("iroh relay", Route.IrohRelay.label)
        assertTrue(Route.IrohRelay.isRelay)
        assertEquals(false, Route.IrohDirect.isRelay)
    }

    @Test fun pairingLinkModes() {
        assertEquals(
            PairingLink.Iroh("abcdefghijklmnopqrst", "123456"),
            PairingLink.parse("dshpocket://pair?n=abcdefghijklmnopqrst&c=123456"),
        )
        assertEquals(
            PairingLink.Iroh("abcdefghijklmnopqrst", "123456"),
            PairingLink.parse("abcdefghijklmnopqrst#123456"),
        )
        assertEquals(
            PairingLink.Tailscale("100.1.2.3", 7690, "123456"),
            PairingLink.parse("dshpocket://pair?h=100.1.2.3:7690&c=123456"),
        )
    }
}
