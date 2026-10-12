package dev.dsh.pocket.app

import org.junit.Assert.*
import org.junit.Test

class FormatTest {
    @Test fun primaryArgPicksMeaningfulKey() {
        assertEquals("ls -la", primaryArg("bash", """{"command":"ls -la","timeout":5}"""))
        assertEquals("bridge/a.mjs", primaryArg("read", """{"path":"bridge/a.mjs"}"""))
        assertEquals("\"auth\"", primaryArg("grep", """{"pattern":"auth"}"""))
        assertNull(primaryArg("x", ""))
    }
    @Test fun hostPort() {
        assertEquals("100.1.2.3" to 7690, parseHostPort("100.1.2.3:7690"))
        assertEquals("::1" to 7690, parseHostPort("[::1]:7690"))
        assertNull(parseHostPort("host"))
        assertNull(parseHostPort("h:70000"))
    }
    @Test fun profileKind() {
        assertEquals("Tailscale", Profile("a", "a", "100.106.128.87", 1).kind)
        assertEquals("Tailscale", Profile("a", "a", "mac.tail1.ts.net", 1).kind)
        assertEquals("LAN", Profile("a", "a", "192.168.1.20", 1).kind)
        assertEquals("ws://[::1]:7", Profile("a", "a", "::1", 7).url)
    }
}
