package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Happy-path pairing is host:port only (`dshpocket://pair?h=&c=`). */
class PairingLinkTest {
    @Test fun hostPortUri() {
        assertEquals(
            PairingLink("100.106.128.87", 7690, "482913"),
            PairingLink.parse("dshpocket://pair?h=100.106.128.87:7690&c=482913"),
        )
        assertEquals(
            PairingLink("mac.tail1.ts.net", 7690, "654321"),
            PairingLink.parse("dshpocket://pair?h=mac.tail1.ts.net:7690&c=654321"),
        )
        assertEquals(
            PairingLink("192.168.1.20", 7690, "111111"),
            PairingLink.parse("dshpocket://pair?h=192.168.1.20:7690&c=111111"),
        )
    }

    @Test fun rejectsIncomplete() {
        assertNull(PairingLink.parse("dshpocket://pair?c=123456"))
        assertNull(PairingLink.parse(""))
        assertNull(PairingLink.parse("https://example.com"))
        assertNull(PairingLink.parse("dshpocket://pair?h=x:1&c=12"))
    }

    @Test fun rejectsLegacyIrohForms() {
        assertNull(PairingLink.parse("dshpocket://pair?n=abcdefghijklmnopqrst&c=482913"))
        assertNull(PairingLink.parse("abcdefghijklmnopqrst#123456"))
        assertNull(PairingLink.parse("dshpocket://pair?n=irohNodeIdValue01&c=123456&h=192.168.1.20:7690"))
    }

    @Test fun parseHostPortOk() {
        assertEquals("100.1.2.3" to 7690, parseHostPort("100.1.2.3:7690"))
        assertEquals("100.1.2.3" to 7690, parseHostPort("ws://100.1.2.3:7690"))
        assertNull(parseHostPort("100.1.2.3"))
        assertNull(parseHostPort("host:99999"))
    }
}
