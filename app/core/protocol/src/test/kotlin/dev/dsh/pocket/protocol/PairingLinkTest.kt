package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure-iroh pairing: QR `n=`+`c=`, or tether-style `nodeId#code`. */
class PairingLinkTest {
    @Test fun qrWithNodeId() {
        assertEquals(
            PairingLink("abcdefghijklmnopqrst", "482913"),
            PairingLink.parse("dshpocket://pair?n=abcdefghijklmnopqrst&c=482913"),
        )
        assertEquals(
            PairingLink("abcdefghijklmnopqrst", "482913"),
            PairingLink.parse("dshpocket://pair?n=abcdefghijklmnopqrst%26x&c=482913".replace("%26x", "")),
        )
    }

    @Test fun tetherPasteLine() {
        assertEquals(
            PairingLink("abcdefghijklmnopqrst", "123456"),
            PairingLink.parse("abcdefghijklmnopqrst#123456"),
        )
        assertEquals(
            PairingLink("abcdefghijklmnopqrst", "123456"),
            PairingLink.parse("  abcdefghijklmnopqrst#123456\n"),
        )
    }

    @Test fun rejectsLegacyHostOnlyUri() {
        assertNull(PairingLink.parse("dshpocket://pair?h=100.106.128.87:7690&c=482913"))
        assertNull(PairingLink.parse("dshpocket://pair?h=mac.tail1.ts.net:7690&c=654321"))
        assertNull(PairingLink.parse("dshpocket://pair?c=123456"))
        assertNull(PairingLink.parse(""))
        assertNull(PairingLink.parse("https://example.com"))
    }

    @Test fun ignoresUnknownExtraKeysWhenNodePresent() {
        assertEquals(
            PairingLink("irohNodeIdValue01", "123456"),
            PairingLink.parse("dshpocket://pair?n=irohNodeIdValue01&c=123456&fp=abc&h=192.168.1.20:7690"),
        )
    }
}
