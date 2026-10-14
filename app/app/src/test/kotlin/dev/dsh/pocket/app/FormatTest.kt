package dev.dsh.pocket.app

import org.junit.Assert.*
import org.junit.Test

class FormatTest {
    @Test fun busySendSteerLabelIsSteerNotImmediate() {
        assertEquals("排队发送", BusySend.Queue.label)
        assertEquals("插话发送", BusySend.Steer.label)
        assertEquals("queue", BusySend.Queue.mode)
        assertEquals("steer", BusySend.Steer.mode)
        assertEquals("发送消息", composerSendLabel(running = false, BusySend.Queue))
        assertEquals("排队发送", composerSendLabel(running = true, BusySend.Queue))
        assertEquals("插话发送", composerSendLabel(running = true, BusySend.Steer))
    }

    @Test fun primaryArgPicksMeaningfulKey() {
        assertEquals("ls -la", primaryArg("bash", """{"command":"ls -la","timeout":5}"""))
        assertEquals("bridge/a.mjs", primaryArg("read", """{"path":"bridge/a.mjs"}"""))
        assertEquals("\"auth\"", primaryArg("grep", """{"pattern":"auth"}"""))
        assertNull(primaryArg("x", ""))
    }

    @Test fun sessionTimeUsesDesktopElapsedBucketsAndClampsFutureActivity() {
        val day = 86_400_000L
        val now = 2_000_000_000_000L
        val cases = listOf(
            Triple(-60_000L, "刚刚", "now"),
            Triple(59_999L, "刚刚", "now"),
            Triple(60_000L, "1分钟", "1min"),
            Triple(3_599_999L, "59分钟", "59min"),
            Triple(3_600_000L, "1小时", "1h"),
            Triple(day - 1, "23小时", "23h"),
            Triple(day, "1天", "1d"),
            Triple(30 * day - 1, "29天", "29d"),
            Triple(30 * day, "1个月", "1mo"),
            Triple(365 * day - 1, "12个月", "12mo"),
            Triple(365 * day, "1年", "1y"),
            Triple(2 * 365 * day, "2年", "2y"),
        )
        for ((elapsed, zh, en) in cases) {
            assertEquals("zh elapsed=$elapsed", zh, formatSessionTime(now - elapsed, now, AppLanguage.Zh))
            assertEquals("en elapsed=$elapsed", en, formatSessionTime(now - elapsed, now, AppLanguage.En))
        }
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

        val local = Profile("t", "loop", host = "127.0.0.1", port = 7690)
        assertEquals(false, local.needsRepair)
        assertEquals("local", local.kind)
        assertEquals("127.0.0.1:7690", local.subtitle)

        val legacyIroh = Profile("a", "desk", nodeId = "abcdefghijklmnopqrst")
        assertEquals(true, legacyIroh.needsRepair)
        assertEquals("需重新配对", legacyIroh.kind)

        val broken = Profile("d", "empty")
        assertEquals(true, broken.needsRepair)
    }
}
