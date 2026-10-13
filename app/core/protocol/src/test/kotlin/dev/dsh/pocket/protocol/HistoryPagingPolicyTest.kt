package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryPagingPolicyTest {
    @Test fun canLoadOlderRequiresKnownWindow() {
        assertTrue(HistoryPagingPolicy.canLoadOlder(hasMore = true, loadingOlder = false, firstSeq = 40))
        assertFalse(HistoryPagingPolicy.canLoadOlder(hasMore = false, loadingOlder = false, firstSeq = 40))
        assertFalse(HistoryPagingPolicy.canLoadOlder(hasMore = true, loadingOlder = true, firstSeq = 40))
        assertFalse(HistoryPagingPolicy.canLoadOlder(hasMore = true, loadingOlder = false, firstSeq = -1))
    }

    @Test fun pageRequestUsesCursorFloorAndFirstSeq() {
        assertEquals(
            HistoryPageRequest(throughSeq = 120, beforeSeq = 40, maxMessages = 100),
            HistoryPagingPolicy.pageRequest(cursor = 120, firstSeq = 40),
        )
        assertEquals(
            HistoryPageRequest(throughSeq = 40, beforeSeq = 40, maxMessages = 50),
            HistoryPagingPolicy.pageRequest(cursor = 10, firstSeq = 40, maxMessages = 50),
        )
    }

    @Test fun pageRequestRejectsUnknownFirstSeq() {
        assertFailsWith<IllegalArgumentException> {
            HistoryPagingPolicy.pageRequest(cursor = 10, firstSeq = -1)
        }
    }
}
