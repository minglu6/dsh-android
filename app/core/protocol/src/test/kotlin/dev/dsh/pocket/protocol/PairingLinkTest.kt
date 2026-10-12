package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Payload format matches plugin `pairingUri`: dshpocket://pair?h=<host:port>&c=<6-digit>. */
class PairingLinkTest {
    @Test fun plainIpv4() {
        assertEquals(
            PairingLink("100.106.128.87", 7690, "482913"),
            PairingLink.parse("dshpocket://pair?h=100.106.128.87:7690&c=482913"),
        )
    }

    @Test fun urlEncodedHostPortFromPlugin() {
        // plugin/src/manage.js uses encodeURIComponent(`${host}:${port}`)
        assertEquals(
            PairingLink("100.106.128.87", 7690, "482913"),
            PairingLink.parse("dshpocket://pair?h=100.106.128.87%3A7690&c=482913"),
        )
    }

    @Test fun ipv6Bracketed() {
        assertEquals(
            PairingLink("::1", 7691, "000001"),
            PairingLink.parse("dshpocket://pair?h=[::1]:7691&c=000001"),
        )
        assertEquals(
            PairingLink("::1", 7691, "000001"),
            PairingLink.parse("dshpocket://pair?h=%5B%3A%3A1%5D%3A7691&c=000001"),
        )
    }

    @Test fun capturesIrohNodeIdAndIgnoresUnknownParams() {
        assertEquals(
            PairingLink("192.168.1.20", 7690, "123456", "irohNode"),
            PairingLink.parse("dshpocket://pair?h=192.168.1.20:7690&n=irohNode&c=123456&fp=abc"),
        )
    }

    @Test fun trimsWhitespace() {
        assertEquals(
            PairingLink("mac.tail1.ts.net", 7690, "654321"),
            PairingLink.parse("  dshpocket://pair?h=mac.tail1.ts.net:7690&c=654321\n"),
        )
    }

    @Test fun rejectsInvalidPayloads() {
        assertNull(PairingLink.parse(""))
        assertNull(PairingLink.parse("https://example.com"))
        assertNull(PairingLink.parse("dshpocket://pair?h=x:1&c=12"))
        assertNull(PairingLink.parse("dshpocket://pair?h=100.1.2.3:7690&c=abcdef"))
        assertNull(PairingLink.parse("dshpocket://pair?c=123456"))
        assertNull(PairingLink.parse("dshpocket://other?h=1.2.3.4:7690&c=123456"))
    }
}
