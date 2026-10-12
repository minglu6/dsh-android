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

    @Test fun profileHostPortModes() {
        val ts = Profile("b", "old", host = "100.106.128.87", port = 7690)
        assertEquals(false, ts.needsRepair)
        assertEquals("Tailscale", ts.kind)
        assertEquals("100.106.128.87:7690", ts.subtitle)

        val lan = Profile("c", "lan", host = "192.168.1.10", port = 7690)
        assertEquals(false, lan.needsRepair)
        assertEquals("LAN", lan.kind)

        val legacyIroh = Profile("a", "desk", nodeId = "abcdefghijklmnopqrst")
        assertEquals(true, legacyIroh.needsRepair)
        assertEquals("需重新配对", legacyIroh.kind)

        val broken = Profile("d", "empty")
        assertEquals(true, broken.needsRepair)
    }
}
