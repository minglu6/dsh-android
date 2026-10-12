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

    @Test fun profileIrohAndLegacyRepair() {
        val ok = Profile("a", "desk", nodeId = "abcdefghijklmnopqrst")
        assertEquals("iroh", ok.kind)
        assertEquals(false, ok.needsRepair)

        val legacy = Profile("b", "old", host = "100.106.128.87", port = 7690)
        assertEquals(true, legacy.needsRepair)
        assertEquals("需重新配对", legacy.kind)
    }
}
