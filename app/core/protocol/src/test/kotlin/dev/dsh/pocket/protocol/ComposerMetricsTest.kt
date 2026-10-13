package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerMetricsTest {
    private fun json(value: String) = PocketJson.parseToJsonElement(value)
    private fun projection(r: TranscriptReducer, key: String, seq: Int, value: String): Boolean =
        r.apply(json("""{"type":"projection","sessionId":"session","key":"$key","seq":$seq,"value":$value}"""))
    private fun stats(tokens: Int = 690, ms: Int = 10000) =
        """{"turns":8,"steps":15,"llmMs":18000,"toolMs":2400,"ttftMs":900,"ttftSteps":3,"decodeMs":$ms,"decodeTokens":$tokens}"""
    private val usage = """{"uncachedInputTokens":800,"cacheReadTokens":0,"cacheWriteTokens":200,"outputTokens":690}"""
    private fun opening(type: String = "snapshot", seq: Int = 50, values: String = "", records: String = "") =
        json("""{"type":"$type","cursor":$seq,"records":[$records],"projections":{"asOfSeq":$seq,"values":{$values}}}""")
    private fun event(seq: Int, time: Int, type: String, data: String) =
        """{"type":"event","event":{"seq":$seq,"time":$time,"type":"$type","data":$data}}"""
    private fun start(seq: Int, turn: Int, time: Int) = event(seq, time, "step/start", """{"turn":$turn,"step":1}""")
    private fun message(seq: Int, turn: Int, first: Int, end: Int, tokens: Int) = event(seq, end, "assistant/message",
        """{"turn":$turn,"step":1,"usage":{"inputTokens":50,"outputTokens":$tokens},"stream":[{"type":"text-chunks","index":0,"time0":$first,"dt":[0],"texts":["reply"]}],"message":{"content":[{"type":"text","text":"reply"}]}}""")
    private fun declaration(seq: Int, time: Int, vararg callIds: String): String {
        val blocks = callIds.joinToString(",") { """{"type":"tool-call","id":"$it","name":"read","arguments":"{}"}""" }
        return event(seq, time, "assistant/message", """{"turn":1,"step":1,"message":{"content":[$blocks]}}""")
    }
    private fun call(seq: Int, time: Int, id: String) =
        event(seq, time, "tool/call", """{"turn":1,"step":1,"callId":"$id","name":"read","arguments":"{}"}""")
    private fun result(seq: Int, time: Int, id: String, isError: Boolean = false) =
        event(seq, time, "tool/result", """{"turn":1,"step":1,"message":{"role":"tool","source":{"callId":"$id"},"isError":$isError,"content":[{"type":"text","text":"done"}]}}""")

    @Test fun durableProjectionSuppliesThreeIndependentReadings() {
        val r = TranscriptReducer()
        r.apply(opening(values = """"sessionStats":${stats()},"tokenUsage":$usage,"contextPressure":{"pressureTokens":12000,"projectedTokens":9000,"contextWindow":100000}"""))
        val m = r.composerMetrics
        assertTrue(m.activityIsWholeSession)
        assertEquals(69.0, m.activity?.tokensPerSecond)
        assertEquals(8L, m.activity?.turns)
        assertEquals(15L, m.activity?.steps)
        assertEquals(300.0, m.activity?.meanTtftMs)
        assertEquals(1000L, m.tokens?.billedInputTokens)
        assertEquals(1690L, m.tokens?.totalTokens)
        assertEquals("0", m.tokens?.cacheHitPercent)
        assertEquals(9, m.context?.percent)
        assertEquals(9000.0, m.context?.usedTokens)
    }

    @Test fun realZeroIsDistinctFromUnavailable() {
        val r = TranscriptReducer()
        assertNull(r.composerMetrics.activity)
        assertNull(r.composerMetrics.tokens)
        assertNull(r.composerMetrics.context)
        projection(r, "sessionStats", 1, stats(tokens = 0, ms = 1000))
        projection(r, "tokenUsage", 1, usage)
        projection(r, "contextPressure", 1, """{"projectedTokens":0,"pressureTokens":9000,"contextWindow":100000}""")
        assertEquals(0.0, r.composerMetrics.activity?.tokensPerSecond)
        assertEquals("0", r.composerMetrics.tokens?.cacheHitPercent)
        assertEquals(0, r.composerMetrics.context?.percent)
        projection(r, "sessionStats", 2, stats(ms = 0))
        assertNull(r.composerMetrics.activity?.tokensPerSecond)
    }

    @Test fun replacesTotalsWithoutDoubleCountingDuplicateEventsOrPages() {
        val r = TranscriptReducer()
        val records = "${start(49, 8, 1000)},${message(50, 8, 1200, 2200, 100)}"
        r.apply(opening(values = """"sessionStats":${stats()},"tokenUsage":$usage""", records = records))
        val initial = r.composerMetrics
        r.apply(json(message(50, 8, 1200, 2200, 100)))
        r.prepend(json("[${start(1, 1, 100)},${message(2, 1, 200, 300, 10)}]"), false)
        assertEquals(initial, r.composerMetrics)
        assertFalse(r.prepend(json("[${start(1, 1, 100)},${message(2, 1, 200, 300, 10)}]"), false))
        r.apply(json(start(51, 9, 3000)))
        r.apply(json(message(52, 9, 4000, 5000, 200)))
        // Existing whole-log values stay authoritative until the corresponding Host replacement arrives.
        assertEquals(initial, r.composerMetrics)
        projection(r, "sessionStats", 52, stats(tokens = 890, ms = 11000))
        projection(r, "tokenUsage", 52, """{"uncachedInputTokens":850,"cacheReadTokens":0,"cacheWriteTokens":200,"outputTokens":890}""")
        assertEquals(890.0 / 11, r.composerMetrics.activity?.tokensPerSecond)
        assertEquals(1940L, r.composerMetrics.tokens?.totalTokens)
    }

    @Test fun projectionSequenceIsPerKeyAndIndependentOfFollowCursor() {
        val r = TranscriptReducer()
        r.apply(opening(seq = 100))
        assertTrue(projection(r, "sessionStats", 80, stats()))
        assertTrue(projection(r, "contextPressure", 3, """{"pressureTokens":9,"contextWindow":100}"""))
        assertFalse(projection(r, "sessionStats", 80, stats(tokens = 1)))
        assertFalse(projection(r, "sessionStats", 79, stats(tokens = 1)))
        assertEquals(69.0, r.composerMetrics.activity?.tokensPerSecond)
        assertEquals(9, r.composerMetrics.context?.percent)
        assertEquals(100L, r.cursor)
    }

    @Test fun snapshotAndResumeCannotOverwriteNewerPushButClearAbsentCapabilities() {
        val r = TranscriptReducer()
        projection(r, "sessionStats", 60, stats())
        projection(r, "contextPressure", 60, """{"pressureTokens":9,"contextWindow":100}""")
        r.apply(opening(seq = 50, values = """"sessionStats":${stats(tokens = 1)}"""))
        assertEquals(69.0, r.composerMetrics.activity?.tokensPerSecond)
        assertEquals(9, r.composerMetrics.context?.percent)
        r.apply(opening(type = "resume", seq = 61, values = """"sessionStats":${stats(tokens = 700)}"""))
        assertEquals(70.0, r.composerMetrics.activity?.tokensPerSecond)
        assertNull(r.composerMetrics.context)
        r.apply(opening(type = "resume", seq = 61))
        assertNull(r.composerMetrics.activity)
    }

    @Test fun compactionUsesProjectedPressureAndModelSwitchRemovesOldCapacity() {
        val r = TranscriptReducer()
        projection(r, "contextPressure", 1, """{"pressureTokens":90000,"contextWindow":100000}""")
        assertEquals(90, r.composerMetrics.context?.percent)
        projection(r, "contextPressure", 2, """{"pressureTokens":90000,"projectedTokens":9000,"contextWindow":100000}""")
        assertEquals(9, r.composerMetrics.context?.percent)
        projection(r, "contextBreakdown", 2, """{"systemTokens":100,"toolsTokens":200,"messageTokens":300}""")
        // The heuristic composition is not the pressure numerator.
        assertEquals(9000.0, r.composerMetrics.context?.usedTokens)
        assertEquals(200.0, r.composerMetrics.contextBreakdown?.toolsTokens)
        projection(r, "contextPressure", 3, """{"pressureTokens":90000,"projectedTokens":9000}""")
        assertNull(r.composerMetrics.context)
        projection(r, "contextPressure", 4, """{"pressureTokens":90000,"projectedTokens":9000,"contextWindow":200000}""")
        assertEquals(5, r.composerMetrics.context?.percent)
        projection(r, "contextPressure", 5, """{"projectedTokens":300000,"contextWindow":200000}""")
        assertEquals(100, r.composerMetrics.context?.percent)
    }

    @Test fun fallbackWeightsAllLoadedTimedStepsInsteadOfLastStepOrWholeTurnTime() {
        val r = TranscriptReducer()
        r.apply(opening(seq = 4, records = "${start(3, 2, 1000)},${message(4, 2, 2000, 4000, 100)}"))
        assertFalse(r.composerMetrics.activityIsWholeSession)
        assertEquals(50.0, r.composerMetrics.activity?.tokensPerSecond)
        val page = json("[${start(1, 1, 100)},${message(2, 1, 200, 1200, 200)}]")
        r.prepend(page, false)
        val m = r.composerMetrics
        assertEquals(100.0, m.activity?.tokensPerSecond) // 300 tokens / 3 seconds, not mean(200, 50).
        assertEquals(2L, m.activity?.turns)
        assertEquals(4100.0, m.activity?.llmMs)
        assertEquals(550.0, m.activity?.meanTtftMs)
        assertNull(m.tokens) // No invented durable token total from a paged window.
        r.prepend(page, false)
        assertEquals(m, r.composerMetrics)
    }

    @Test fun serialToolsUseDispatchTimesWithoutChangingCardCreationTimes() {
        val r = TranscriptReducer()
        val records = listOf(
            start(1, 1, 500),
            declaration(2, 1000, "first", "second"),
            call(3, 1000, "first"),
            result(4, 2000, "first"),
            call(5, 2000, "second"),
            result(6, 3000, "second"),
        ).joinToString(",")
        r.apply(opening(seq = 6, records = records))
        assertEquals(2000.0, r.composerMetrics.activity?.toolMs)
        assertFalse(r.composerMetrics.activityIsWholeSession)
        val tools = r.items().filterIsInstance<Item.Tool>()
        assertEquals(listOf(1000L, 1000L), tools.map { it.startedAt })
        assertEquals(listOf(2000L, 3000L), tools.map { it.endedAt })
        assertEquals(listOf(ToolStatus.Ok, ToolStatus.Ok), tools.map { it.status })
    }

    @Test fun unstartedCancelledAndUnmatchedToolsNeverInventExecutionTime() {
        val r = TranscriptReducer()
        r.apply(opening(seq = 1, records = declaration(1, 1000, "cancelled", "declared", "running")))
        r.apply(json(result(2, 3000, "cancelled", isError = true)))
        r.apply(json(result(3, 4000, "outside-window")))
        r.apply(json(call(4, 5000, "running")))
        assertEquals(0.0, r.composerMetrics.activity?.toolMs)
        assertEquals(ToolStatus.Error, r.items().filterIsInstance<Item.Tool>().first().status)
        assertEquals(3000L, r.items().filterIsInstance<Item.Tool>().first().endedAt)
        assertEquals(3, r.items().filterIsInstance<Item.Tool>().size)
    }

    @Test fun ptcDispatchesAndRepeatedFollowFramesDoNotDoubleCountRootTime() {
        val r = TranscriptReducer()
        val records = listOf(
            declaration(1, 500, "root"),
            call(2, 1000, "root"),
            event(3, 1100, "tool/ptc-dispatch-start", """{"parentCallId":"root","subCallId":"child","name":"read","arguments":{}}"""),
            event(4, 1900, "tool/ptc-dispatch", """{"parentCallId":"root","subCallId":"child","name":"read","arguments":{},"content":[{"type":"text","text":"done"}]}"""),
            result(5, 2000, "root"),
        ).joinToString(",")
        r.apply(opening(seq = 5, records = records))
        assertEquals(1000.0, r.composerMetrics.activity?.toolMs)
        assertEquals("root", r.items().filterIsInstance<Item.Tool>().single { it.callId == "child" }.parentCallId)
        val metrics = r.composerMetrics
        r.apply(json(result(5, 2000, "root")))
        r.apply(opening(type = "resume", seq = 5, records = records))
        assertEquals(metrics, r.composerMetrics)
    }

    @Test fun pagingCompletesSplitPairsAndIgnoresDeclarationsAndRepeatedPages() {
        val r = TranscriptReducer()
        r.apply(opening(seq = 6, records = "${result(5, 3000, "split")},${message(6, 1, 3100, 3200, 10)}"))
        assertEquals(0.0, r.composerMetrics.activity?.toolMs)
        assertTrue(r.prepend(json("[${call(4, 2000, "split")}]"), true))
        assertEquals(1000.0, r.composerMetrics.activity?.toolMs)
        assertTrue(r.prepend(json("[${declaration(3, 1500, "split")}]"), true))
        assertEquals(1000.0, r.composerMetrics.activity?.toolMs)
        val older = json("[${call(1, 0, "older")},${result(2, 1000, "older")}]")
        assertTrue(r.prepend(older, false))
        assertEquals(2000.0, r.composerMetrics.activity?.toolMs)
        assertFalse(r.prepend(older, false))
        assertEquals(2000.0, r.composerMetrics.activity?.toolMs)
    }

    @Test fun resumeCompletesPendingCallButSnapshotDiscardsPreviousWindowTiming() {
        val r = TranscriptReducer()
        r.apply(opening(seq = 2, records = "${declaration(1, 500, "tool")},${call(2, 1000, "tool")}"))
        assertEquals(0.0, r.composerMetrics.activity?.toolMs)
        r.apply(opening(type = "resume", seq = 3, records = result(3, 2000, "tool")))
        assertEquals(1000.0, r.composerMetrics.activity?.toolMs)
        r.apply(opening(seq = 3, records = result(3, 2000, "tool")))
        assertEquals(0.0, r.composerMetrics.activity?.toolMs)
        assertTrue(r.prepend(json("[${call(2, 1000, "tool")}]"), false))
        assertEquals(1000.0, r.composerMetrics.activity?.toolMs)
        assertEquals(0L, r.composerMetrics.activity?.steps)
    }

    @Test fun authoritativeToolTimeSurvivesPagingUntilProjectionBecomesUnavailable() {
        val r = TranscriptReducer()
        r.apply(opening(seq = 5, values = """"sessionStats":${stats()}""",
            records = "${declaration(3, 1500, "recent")},${call(4, 2000, "recent")},${result(5, 3000, "recent")}"))
        assertTrue(r.composerMetrics.activityIsWholeSession)
        assertEquals(2400.0, r.composerMetrics.activity?.toolMs)
        r.prepend(json("[${call(1, 0, "older")},${result(2, 1000, "older")}]"), false)
        assertEquals(2400.0, r.composerMetrics.activity?.toolMs)
        projection(r, "sessionStats", 6, "null")
        assertFalse(r.composerMetrics.activityIsWholeSession)
        assertEquals(2000.0, r.composerMetrics.activity?.toolMs)
    }

    @Test fun missingTimingAndUsageDoNotCreateSpeed() {
        val r = TranscriptReducer()
        r.apply(json(event(1, 1000, "assistant/message", """{"turn":1,"step":1,"usage":{"outputTokens":100},"message":{"content":[]}}""")))
        assertNull(r.composerMetrics.activity?.tokensPerSecond)
        r.apply(json(event(2, 2000, "assistant/message", """{"turn":2,"step":1,"stream":[{"type":"text-chunks","index":0,"time0":1500,"texts":["a"],"dt":[0]}],"message":{"content":[]}}""")))
        assertNull(r.composerMetrics.activity?.tokensPerSecond)
    }

    @Test fun clearingConnectionGenerationAcceptsRestartedHostWatermarks() {
        val r = TranscriptReducer()
        projection(r, "contextPressure", 1000, """{"pressureTokens":90,"contextWindow":100}""")
        r.clearComposerProjections()
        assertNull(r.composerMetrics.context)
        assertTrue(projection(r, "contextPressure", 1, """{"pressureTokens":9,"contextWindow":100}"""))
        assertEquals(9, r.composerMetrics.context?.percent)
    }

    @Test fun malformedOrPartialProjectionDoesNotInventZero() {
        val r = TranscriptReducer()
        projection(r, "tokenUsage", 1, """{"uncachedInputTokens":40,"cacheReadTokens":0,"outputTokens":1}""")
        projection(r, "contextPressure", 1, """{"pressureTokens":50,"contextWindow":0}""")
        projection(r, "sessionStats", 1, """{"decodeMs":1000,"decodeTokens":100}""")
        assertNull(r.composerMetrics.tokens)
        assertNull(r.composerMetrics.activity)
        assertNull(r.composerMetrics.context)
        projection(r, "contextPressure", 2, """{"pressureTokens":-1,"contextWindow":100}""")
        assertNull(r.composerMetrics.context)
    }

    @Test fun cacheWriteParticipatesAndPartialHitsNeverRoundToFullHits() {
        assertEquals("50", ComposerTokenUsage(0, 50, 50, 5).cacheHitPercent)
        assertEquals(105L, ComposerTokenUsage(0, 50, 50, 5).totalTokens)
        assertNull(formatComposerCacheHit(0, 0))
        assertEquals("0", formatComposerCacheHit(0, 100))
        assertEquals("100", formatComposerCacheHit(100, 100))
        assertEquals("50", formatComposerCacheHit(1, 2))
        assertEquals("99.9", formatComposerCacheHit(999, 1000))
        assertEquals("99.99", formatComposerCacheHit(9999, 10000))
        assertEquals("99.99999999999999", formatComposerCacheHit(9007199254740990, 9007199254740991))
        assertNull(formatComposerCacheHit(101, 100))
    }
}
