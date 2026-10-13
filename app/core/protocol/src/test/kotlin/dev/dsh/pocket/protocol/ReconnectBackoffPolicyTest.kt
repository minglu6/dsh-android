package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectBackoffPolicyTest {
    @Test fun resetsToInitial() {
        assertEquals(1_000L, ReconnectBackoffPolicy.reset())
    }

    @Test fun doublesUntilCap() {
        assertEquals(2_000L, ReconnectBackoffPolicy.next(1_000L))
        assertEquals(4_000L, ReconnectBackoffPolicy.next(2_000L))
        assertEquals(16_000L, ReconnectBackoffPolicy.next(8_000L))
        assertEquals(30_000L, ReconnectBackoffPolicy.next(16_000L))
        assertEquals(30_000L, ReconnectBackoffPolicy.next(30_000L))
    }
}
