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

    @Test fun messageClockMatchesDesktopRules() {
        fun at(y: Int, month0: Int, d: Int, h: Int, mi: Int): Long =
            java.util.Calendar.getInstance().apply {
                set(y, month0, d, h, mi, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        val now = at(2026, java.util.Calendar.OCTOBER, 12, 16, 8)
        assertEquals("16:08", formatMessageClock(now, now))
        assertEquals("10月11日 16:08", formatMessageClock(at(2026, java.util.Calendar.OCTOBER, 11, 16, 8), now))
        assertEquals("2025年1月3日 09:05", formatMessageClock(at(2025, java.util.Calendar.JANUARY, 3, 9, 5), now))
        assertEquals("已完成", todoStatusLabel("completed"))
        assertEquals("进行中", todoStatusLabel("in_progress"))
        assertEquals("待处理", todoStatusLabel("pending"))
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
