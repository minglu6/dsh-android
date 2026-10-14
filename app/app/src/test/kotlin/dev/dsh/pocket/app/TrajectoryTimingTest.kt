package dev.dsh.pocket.app

import kotlin.test.Test
import kotlin.test.assertEquals

class TrajectoryTimingTest {
    @Test fun assistantDurationsSwitchUnitsAtOneSecond() {
        assertEquals("999 毫秒", formatAssistantDuration(0.999))
        assertEquals("1.00 秒", formatAssistantDuration(1.0))
        assertEquals("3.35 秒", formatAssistantDuration(3.351))
        assertEquals("10.0 秒", formatAssistantDuration(10.0))
        assertEquals(TrajL.timingNotAvailable, formatAssistantDuration(null))
        assertEquals(TrajL.timingNotAvailable, formatAssistantDuration(Double.NaN))
    }

    @Test fun timingUsesExactOutputTokensAndExcludesFirstTokenLatency() {
        val row = TrajRow("a", 0, TrajL.kindAssistant, "", output = "1.3k", outputTokens = 1297,
            startedAt = 1000, firstTokenTime = 2000, timeSeconds = 3.0)
        assertEquals("1.00 秒", formatTtft(row))
        assertEquals("2.00 秒", formatGeneration(row))
        assertEquals("648.5 tok/s", formatThroughput(row))
        assertEquals(TrajL.statusPending, formatThroughput(row.copy(running = true)))
        assertEquals(TrajL.timingNotAvailable, formatThroughput(row.copy(outputTokens = null)))
        assertEquals("时长过短", formatThroughput(row.copy(timeSeconds = 1.0)))
    }
}
