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

    @Test fun transportPlanIrohFirst() {
        val iroh = TransportFactory { FakeTransport(Route.IrohDirect) }
        val plan = transportPlan("nodeabc", iroh, null)
        assertEquals(1, plan.size)
        val withLocal = transportPlan("nodeabc", iroh, "ws://127.0.0.1:7690")
        assertEquals(2, withLocal.size)
        val noIroh = transportPlan(null, iroh, null)
        assertTrue(noIroh.isEmpty())
        val missingFactory = transportPlan("nodeabc", null, null)
        assertTrue(missingFactory.isEmpty())
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
        assertEquals("iroh", Route.IrohDirect.label)
        assertEquals("iroh relay", Route.IrohRelay.label)
        assertTrue(Route.IrohRelay.isRelay)
        assertEquals(false, Route.IrohDirect.isRelay)
    }

    @Test fun pairingLinkPureIroh() {
        assertEquals(
            PairingLink("abcdefghijklmnopqrst", "123456"),
            PairingLink.parse("dshpocket://pair?n=abcdefghijklmnopqrst&c=123456"),
        )
        assertEquals(
            PairingLink("abcdefghijklmnopqrst", "123456"),
            PairingLink.parse("abcdefghijklmnopqrst#123456"),
        )
        assertEquals(null, PairingLink.parse("dshpocket://pair?h=100.1.2.3:7690&c=123456"))
    }
}
