package dev.dsh.pocket.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerTransportTest {
    @Test fun tabsAreLanThenTailscale() {
        assertEquals(listOf(PeerTransport.Lan, PeerTransport.Tailscale), peerTransportTabs())
    }

    @Test fun addressHintsAreTransportSpecific() {
        assertTrue(peerAddressHint(PeerTransport.Lan).contains("192.168"))
        assertFalse(peerAddressHint(PeerTransport.Lan).contains("100.x"))
        assertTrue(peerAddressHint(PeerTransport.Tailscale).contains("100.x") || peerAddressHint(PeerTransport.Tailscale).contains("ts.net"))
    }

    @Test fun matchesProfileByHostClassification() {
        assertTrue(PeerTransport.Lan.matchesProfile(Profile("1", "nas", host = "192.168.1.20", port = 7690)))
        assertTrue(PeerTransport.Tailscale.matchesProfile(Profile("2", "mac", host = "100.106.128.87", port = 7690)))
        assertTrue(PeerTransport.Tailscale.matchesProfile(Profile("3", "dns", host = "mac.tail1.ts.net", port = 7690)))
        assertFalse(PeerTransport.Lan.matchesProfile(Profile("4", "loop", host = "127.0.0.1", port = 7690)))
    }

    @Test fun labelsPreferChineseByDefault() {
        assertEquals("局域网", PeerTransport.Lan.label(en = false))
        assertEquals("LAN", PeerTransport.Lan.label(en = true))
        assertEquals("Tailscale", PeerTransport.Tailscale.label(en = false))
    }
}
