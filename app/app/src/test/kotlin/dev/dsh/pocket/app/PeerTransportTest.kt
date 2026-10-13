package dev.dsh.pocket.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerTransportTest {
    @Test fun tabsAlwaysIncludeLanTailscaleAndLocal() {
        val tabs = peerTransportTabs()
        assertEquals(listOf(PeerTransport.Lan, PeerTransport.Tailscale, PeerTransport.Local), tabs)
        assertEquals(PeerTransport.Lan, tabs.first())
    }

    @Test fun tabsCanOmitLocalWhenExplicitlyDisabled() {
        assertFalse(peerTransportTabs(includeLocal = false).contains(PeerTransport.Local))
        assertEquals(
            listOf(PeerTransport.Lan, PeerTransport.Tailscale),
            peerTransportTabs(includeLocal = false),
        )
    }

    @Test fun addressHintsAreTransportSpecificNotTailscaleOnly() {
        assertTrue(peerAddressHint(PeerTransport.Lan).contains("192.168"))
        assertFalse(peerAddressHint(PeerTransport.Lan).contains("100.x"))
        assertTrue(peerAddressHint(PeerTransport.Tailscale).contains("100.x") || peerAddressHint(PeerTransport.Tailscale).contains("ts.net"))
        assertEquals("127.0.0.1:7690", peerAddressHint(PeerTransport.Local))
        assertEquals("127.0.0.1:7690", peerAddressSeed(PeerTransport.Local))
        assertEquals("", peerAddressSeed(PeerTransport.Lan))
    }

    @Test fun matchesProfileByHostClassification() {
        assertTrue(PeerTransport.Lan.matchesProfile(Profile("1", "nas", host = "192.168.1.20", port = 7690)))
        assertTrue(PeerTransport.Tailscale.matchesProfile(Profile("2", "mac", host = "100.106.128.87", port = 7690)))
        assertTrue(PeerTransport.Tailscale.matchesProfile(Profile("3", "dns", host = "mac.tail1.ts.net", port = 7690)))
        assertTrue(PeerTransport.Local.matchesProfile(Profile("4", "termux", host = "127.0.0.1", port = 7690)))
    }

    @Test fun nearbyConnectOpensPairWhenSingleHost() {
        val one = DiscoveredHost(deviceName = "Studio", host = "192.168.1.8", port = 7690, instanceId = "id")
        val action = resolveNearbyConnect(listOf(one))
        assertEquals(NearbyConnectAction.OpenPair(one), action)
        assertEquals(NearbyConnectAction.ShowLanTab, resolveNearbyConnect(emptyList()))
        assertEquals(
            NearbyConnectAction.ShowLanTab,
            resolveNearbyConnect(listOf(one, one.copy(instanceId = "other", deviceName = "Other"))),
        )
    }

    @Test fun labelsPreferChineseByDefault() {
        assertEquals("局域网", PeerTransport.Lan.label(en = false))
        assertEquals("LAN", PeerTransport.Lan.label(en = true))
        assertEquals("本机", PeerTransport.Local.label(en = false))
        assertEquals("Tailscale", PeerTransport.Tailscale.label(en = false))
    }
}
