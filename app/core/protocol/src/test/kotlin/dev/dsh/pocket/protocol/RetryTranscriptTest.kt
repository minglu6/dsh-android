package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryTranscriptTest {
    private fun event(seq: Int, type: String, data: String): JsonElement = PocketJson.parseToJsonElement(
        """{"type":"event","event":{"seq":$seq,"time":${1000 + seq},"type":"$type","data":$data}}""",
    )

    private fun scheduled(
        seq: Int, retry: Int = 1, id: String = "request-a", turn: Int = 1, step: Int = 1,
        mode: String = "normal", maximum: String = ",\"maxRetries\":5",
    ) = event(seq, "llm/retry", """{
        "retryId":"$id","turn":$turn,"step":$step,"provider":"deepseek","mode":"$mode",
        "policyKey":"provider","retry":$retry$maximum,"delayMs":1500,
        "failure":{"message":"provider temporarily unavailable","code":"RATE_LIMIT","retryable":true}
    }""")

    private fun started(seq: Int, retry: Int = 1, id: String = "request-a", turn: Int = 1, step: Int = 1) =
        event(seq, "llm/retry-started", """{"retryId":"$id","turn":$turn,"step":$step,"retry":$retry}""")

    private fun turnEnd(seq: Int, turn: Int = 1, error: Boolean = false) = event(
        seq, "turn/end", if (error) """{"turn":$turn,"reason":{"kind":"error","error":{"message":"raw provider failure","code":"QUOTA"}}}"""
        else """{"turn":$turn,"reason":{"kind":"completed"}}""",
    )

    private fun opening(type: String, records: List<JsonElement>, cursor: Int, more: Boolean = false) =
        PocketJson.parseToJsonElement("""{"type":"$type","cursor":$cursor,"hasMore":$more,"records":${JsonArray(records)},"projections":{}}""")

    private fun TranscriptReducer.retries() = items().filterIsInstance<Item.Retry>()

    @Test fun fiveAttemptsProduceOneChainAndIndependentTerminalFailure() {
        val reducer = TranscriptReducer()
        reducer.apply(event(1, "turn/start", """{"turn":1}"""))
        reducer.apply(event(2, "step/start", """{"turn":1,"step":1}"""))
        for (retry in 1..5) {
            reducer.apply(scheduled(retry * 2 + 1, retry))
            reducer.apply(started(retry * 2 + 2, retry))
        }
        reducer.apply(turnEnd(13, error = true))
        val chain = reducer.retries().single()
        assertEquals(listOf(1, 2, 3, 4, 5), chain.attempts.map { it.retry })
        assertTrue(chain.attempts.all { it.state == RetryState.Started })
        assertEquals(5, chain.attempts.last().maxRetries)
        assertEquals("normal", chain.attempts.last().mode)
        assertEquals(1500L, chain.attempts.last().delayMs)
        assertEquals("provider temporarily unavailable", chain.attempts.last().message)
        assertEquals("RATE_LIMIT", chain.attempts.last().code)
        assertEquals(1, chain.turn)
        assertEquals(1, chain.step)
        val failure = reducer.items().filterIsInstance<Item.TurnFailure>().single()
        assertEquals("raw provider failure", failure.message)
        assertEquals("QUOTA", failure.code)
        assertEquals(1, failure.turn)
        assertEquals(listOf("Retry", "TurnFailure"), reducer.items().map { it::class.simpleName })
        assertFalse(reducer.running)
    }

    @Test fun successfulRetryPreservesStartedHistoryAndTrajectoryFailure() {
        val reducer = TranscriptReducer()
        reducer.apply(event(1, "turn/start", """{"turn":1}"""))
        reducer.apply(event(2, "step/start", """{"turn":1,"step":1}"""))
        reducer.apply(scheduled(3))
        reducer.apply(started(4))
        reducer.apply(event(5, "assistant/message", """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"done"}]}}"""))
        reducer.apply(event(6, "step/end", """{"turn":1,"step":1}"""))
        reducer.apply(turnEnd(7))
        assertEquals(RetryState.Started, reducer.retries().single().attempts.single().state)
        assertTrue(reducer.items().none { it is Item.TurnFailure || it is Item.Notice })
        assertFalse(reducer.running)
        val step = reducer.stepRecords().single()
        assertEquals(1, step.retry)
        assertEquals(5, step.maxRetries)
        assertEquals(1500L, step.retryDelayMs)
        assertEquals("provider temporarily unavailable", step.lastAttemptError)
    }

    @Test fun closingStepCancelsWaitingChainButNotOtherStepsOrStartedAttempts() {
        val reducer = TranscriptReducer()
        reducer.apply(scheduled(1, id = "waiting"))
        reducer.apply(scheduled(2, id = "started"))
        reducer.apply(started(3, id = "started"))
        reducer.apply(scheduled(4, id = "other-step", step = 2))
        reducer.apply(scheduled(5, id = "other-turn", turn = 2))
        reducer.apply(event(6, "step/end", """{"turn":1,"step":1}"""))
        assertEquals(
            listOf(RetryState.Cancelled, RetryState.Started, RetryState.Scheduled, RetryState.Scheduled),
            reducer.retries().map { it.attempts.last().state },
        )
        reducer.apply(turnEnd(7))
        assertEquals(
            listOf(RetryState.Cancelled, RetryState.Started, RetryState.Cancelled, RetryState.Scheduled),
            reducer.retries().map { it.attempts.last().state },
        )
    }

    @Test fun retryIdentityNeverMergesDifferentIdsTurnsOrSteps() {
        val reducer = TranscriptReducer()
        reducer.apply(scheduled(1))
        reducer.apply(scheduled(2, id = "request-b"))
        reducer.apply(scheduled(3, turn = 2))
        reducer.apply(scheduled(4, step = 2))
        reducer.apply(started(5, turn = 2))
        assertEquals(4, reducer.retries().size)
        assertEquals(4, reducer.retries().map { it.key }.distinct().size)
        assertEquals(
            listOf(RetryState.Scheduled, RetryState.Scheduled, RetryState.Started, RetryState.Scheduled),
            reducer.retries().map { it.attempts.single().state },
        )
    }

    @Test fun missingRetryIdDoesNotCreateUncorrelatedChatRows() {
        val reducer = TranscriptReducer()
        reducer.apply(event(1, "llm/retry", """{"turn":1,"step":1,"retry":1,"delayMs":100,"failure":{"message":"raw"}}"""))
        reducer.apply(event(2, "llm/retry-started", """{"turn":1,"step":1,"retry":1}"""))
        reducer.apply(scheduled(3, id = ""))
        reducer.apply(started(4, id = ""))
        assertTrue(reducer.items().isEmpty())
        reducer.apply(turnEnd(5, error = true))
        assertEquals(1, reducer.items().filterIsInstance<Item.TurnFailure>().size)
    }

    @Test fun alwaysModeHasNoSyntheticMaximum() {
        val reducer = TranscriptReducer()
        reducer.apply(scheduled(1, mode = "always", maximum = ""))
        val attempt = reducer.retries().single().attempts.single()
        assertEquals("always", attempt.mode)
        assertNull(attempt.maxRetries)
    }

    @Test fun duplicateSequenceAndResumeDoNotDuplicateAttemptsOrFailure() {
        val reducer = TranscriptReducer()
        val first = scheduled(1)
        val begin = started(2)
        reducer.apply(opening("snapshot", listOf(first), 1))
        val key = reducer.retries().single().key
        val beforeDuplicate = reducer.items()
        reducer.apply(first)
        assertEquals(beforeDuplicate, reducer.items())
        reducer.apply(opening("resume", listOf(first, begin, scheduled(3, retry = 2)), 3))
        reducer.apply(opening("resume", listOf(begin, scheduled(3, retry = 2), turnEnd(4, error = true)), 4))
        val beforeTerminalDuplicate = reducer.items()
        reducer.apply(turnEnd(4, error = true))
        assertEquals(beforeTerminalDuplicate, reducer.items())
        val chain = reducer.retries().single()
        assertEquals(key, chain.key)
        assertEquals(listOf(1L, 3L), chain.attempts.map { it.seq })
        assertEquals(listOf(RetryState.Started, RetryState.Cancelled), chain.attempts.map { it.state })
        assertEquals(1, reducer.items().filterIsInstance<Item.TurnFailure>().size)
    }

    @Test fun prependMergesChainAcrossPagesAndRebuildsLiveIndex() {
        val reducer = TranscriptReducer()
        reducer.apply(opening("snapshot", listOf(started(6, retry = 3)), 6, more = true))
        assertTrue(reducer.retries().isEmpty())
        assertTrue(reducer.prepend(JsonArray(listOf(started(4, retry = 2), scheduled(5, retry = 3))), true))
        val key = reducer.retries().single().key
        assertTrue(reducer.prepend(JsonArray(listOf(started(2), scheduled(3, retry = 2))), true))
        assertTrue(reducer.prepend(JsonArray(listOf(scheduled(1))), false))
        val chain = reducer.retries().single()
        assertEquals(key, chain.key)
        assertEquals(listOf(1L, 3L, 5L), chain.attempts.map { it.seq })
        assertTrue(chain.attempts.all { it.state == RetryState.Started })
        assertEquals(1L, reducer.firstSeq)
        assertFalse(reducer.hasMore)
        assertFalse(reducer.prepend(JsonArray(listOf(scheduled(1))), false))
        reducer.apply(scheduled(7, retry = 4))
        reducer.apply(started(8, retry = 4))
        assertEquals(4, reducer.retries().single().attempts.size)
        assertEquals(RetryState.Started, reducer.retries().single().attempts.last().state)
    }

    @Test fun prependRetainsOrphanStartAndBoundaryPagesWithoutVisibleItems() {
        val reducer = TranscriptReducer()
        reducer.apply(opening("snapshot", listOf(turnEnd(8, error = true)), 8, more = true))
        assertTrue(reducer.prepend(JsonArray(listOf(event(7, "step/end", """{"turn":1,"step":1}"""))), true))
        assertTrue(reducer.prepend(JsonArray(listOf(scheduled(5, retry = 2))), true))
        assertTrue(reducer.prepend(JsonArray(listOf(started(4))), true))
        assertTrue(reducer.prepend(JsonArray(listOf(scheduled(3))), false))
        assertEquals(
            listOf(RetryState.Started, RetryState.Cancelled),
            reducer.retries().single().attempts.map { it.state },
        )
        assertEquals(listOf("Retry", "TurnFailure"), reducer.items().map { it::class.simpleName })
    }

    @Test fun snapshotResetDropsOldIndexesAndBoundaryEndpoints() {
        val reducer = TranscriptReducer()
        reducer.apply(opening("snapshot", listOf(scheduled(1), started(2), turnEnd(3)), 3))
        reducer.apply(opening("snapshot", listOf(scheduled(1)), 1))
        assertEquals(RetryState.Scheduled, reducer.retries().single().attempts.single().state)
        reducer.apply(started(2))
        assertEquals(RetryState.Started, reducer.retries().single().attempts.single().state)
        reducer.apply(opening("snapshot", emptyList(), 0))
        reducer.apply(scheduled(1, id = "new"))
        reducer.apply(turnEnd(2))
        assertEquals("new", reducer.retries().single().retryId)
        assertEquals(RetryState.Cancelled, reducer.retries().single().attempts.single().state)
    }
}
