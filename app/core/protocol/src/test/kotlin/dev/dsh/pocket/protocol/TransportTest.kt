package dev.dsh.pocket.protocol

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransportTest {
    private class FakeTransport(override val route: Route) : FrameTransport {
        private val ch = Channel<String>(Channel.UNLIMITED)
        override val incoming: Flow<String> = ch.receiveAsFlow()
        override var closeReason: String? = null
        override suspend fun send(text: String) { ch.send(text) }
        override fun close() { ch.close() }
    }

    @Test fun classifyTailscaleAndLan() {
        assertEquals(Route.WsTailscale, classifyWsRoute("100.64.1.2"))
        assertEquals(Route.WsTailscale, classifyWsRoute("foo.ts.net"))
        assertEquals(Route.WsLan, classifyWsRoute("192.168.1.1"))
        assertEquals(Route.WsLan, classifyWsRoute("127.0.0.1"))
    }

    @Test fun transportPlanOrdersWsThenIroh() {
        val plan = transportPlan("100.1.2.3", 7690, "nodeabc", TransportFactory { FakeTransport(Route.IrohDirect) })
        assertEquals(2, plan.size)
        val noIroh = transportPlan("100.1.2.3", 7690, null, TransportFactory { FakeTransport(Route.IrohDirect) })
        assertEquals(1, noIroh.size)
        val missingFactory = transportPlan("100.1.2.3", 7690, "nodeabc", null)
        assertEquals(1, missingFactory.size)
    }

    @Test fun dialInOrderFallsBack() = runBlocking {
        var tried = 0
        val fail = TransportFactory {
            tried++
            error("ws down")
        }
        val ok = TransportFactory {
            tried++
            FakeTransport(Route.IrohRelay)
        }
        val t = dialInOrder(listOf(fail, ok))
        assertEquals(Route.IrohRelay, t.route)
        assertEquals(2, tried)
    }

    @Test fun dialInOrderAllFail() = runBlocking {
        val err = assertFailsWith<IllegalStateException> {
            dialInOrder(listOf(TransportFactory { error("a") }, TransportFactory { error("b") }))
        }
        assertTrue(err.message?.contains("b") == true)
    }

    @Test fun routeLabels() {
        assertEquals("Tailscale", Route.WsTailscale.label)
        assertEquals("LAN", Route.WsLan.label)
        assertEquals("iroh", Route.IrohDirect.label)
        assertEquals("iroh relay", Route.IrohRelay.label)
        assertTrue(Route.IrohRelay.isRelay)
    }

    @Test fun pairingLinkKeepsNodeIdOptional() {
        assertEquals(
            PairingLink("100.1.2.3", 7690, "123456", null),
            PairingLink.parse("dshpocket://pair?h=100.1.2.3:7690&c=123456"),
        )
        assertEquals(
            PairingLink("100.1.2.3", 7690, "123456", "abcNode"),
            PairingLink.parse("dshpocket://pair?h=100.1.2.3:7690&c=123456&n=abcNode"),
        )
    }
}
