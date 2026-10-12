package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.RemoteTransport
import org.junit.Assert.*
import org.junit.Test

class FormatTest {
    @Test fun primaryArgPicksMeaningfulKey() {
        assertEquals("ls -la", primaryArg("bash", """{"command":"ls -la","timeout":5}"""))
        assertEquals("bridge/a.mjs", primaryArg("read", """{"path":"bridge/a.mjs"}"""))
        assertEquals("\"auth\"", primaryArg("grep", """{"pattern":"auth"}"""))
        assertNull(primaryArg("x", ""))
    }

    @Test fun profileIrohAndTailscaleModes() {
        val ok = Profile("a", "desk", nodeId = "abcdefghijklmnopqrst")
        assertEquals("iroh", ok.kind)
        assertEquals(false, ok.needsRepair)
        assertEquals(RemoteTransport.Iroh, ok.remote)

        val ts = Profile("b", "old", host = "100.106.128.87", port = 7690)
        assertEquals(false, ts.needsRepair)
        assertEquals("Tailscale", ts.kind)
        assertEquals(RemoteTransport.Tailscale, ts.remote)
        assertEquals("100.106.128.87:7690", ts.subtitle)

        val broken = Profile("c", "empty")
        assertEquals(true, broken.needsRepair)
    }
}
