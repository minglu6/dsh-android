package dev.dsh.pocket.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurnTokenUsageTest {
    private fun json(value: String) = PocketJson.parseToJsonElement(value)
    private fun event(seq: Int, type: String, turn: Int = 1, step: Int = 1, extra: String = ""): JsonElement =
        json("""{"type":"event","event":{"seq":$seq,"time":$seq,"type":"$type","data":{"turn":$turn,"step":$step$extra}}}""")

    private fun usage(vararg overrides: Pair<String, JsonElement?>): JsonObject {
        val values = linkedMapOf<String, JsonElement>(
            "inputTokens" to JsonPrimitive(100), "outputTokens" to JsonPrimitive(20),
            "totalTokens" to JsonPrimitive(170), "cacheReadTokens" to JsonPrimitive(50),
        )
        for ((key, value) in overrides) if (value == null) values.remove(key) else values[key] = value
        return JsonObject(values)
    }

    private fun message(
        seq: Int,
        value: JsonElement? = usage(),
        streamUsage: JsonElement? = value,
        turn: Int = 1,
        step: Int = 1,
        provider: String = "deepseek",
        model: String = "deepseek-chat",
    ): JsonElement {
        val stream = if (streamUsage == null) "[]" else """[{"type":"chunk","chunk":{"type":"usage","usage":$streamUsage}}]"""
        val final = if (value == null) "" else """, "usage":$value"""
        return event(seq, "assistant/message", turn, step,
            """, "stream":$stream, "message":{"id":"m$seq","role":"assistant","content":[{"type":"text","text":"done"}],"source":{"provider":"$provider","model":"$model"}}$final""")
    }

    private fun attempt(seq: Int, value: JsonElement? = usage(), step: Int = 1): JsonElement {
        val sample = if (value == null) "" else """{"type":"chunk","chunk":{"type":"usage","usage":$value}},"""
        return event(seq, "assistant/attempt", step = step,
            extra = """, "stream":[$sample{"type":"chunk","chunk":{"type":"finish","reason":{"kind":"error"}}}]""")
    }

    private fun complete(vararg middle: JsonElement): List<JsonElement> = listOf(
        event(1, "turn/start"), event(2, "step/start"), *middle,
        event(90, "step/end"), event(91, "turn/end"),
    )

    private fun reducer(events: List<JsonElement>): TranscriptReducer = TranscriptReducer().also { r -> events.forEach(r::apply) }
    private fun result(events: List<JsonElement>): TurnTokenUsage? = reducer(events).turnTokenUsage()[1]
    private fun snapshot(events: List<JsonElement>, more: Boolean = false): JsonElement = buildJsonObject {
        put("type", JsonPrimitive("snapshot"))
        put("cursor", JsonPrimitive(events.lastOrNull()?.get("event")?.get("seq").long() ?: -1L))
        put("records", JsonArray(events))
        put("hasMore", JsonPrimitive(more))
    }

    @Test fun exactCompleteUsageAndOptionalZeroBuckets() {
        assertEquals(TurnTokenUsage(100, 20, 170, 50, 0, 8,
            listOf(TurnTokenUsageRoute("deepseek", "deepseek-chat"))),
            result(complete(message(3, usage("cacheWriteTokens" to JsonPrimitive(0), "reasoningTokens" to JsonPrimitive(8))))))
    }

    @Test fun derivesTotalOnlyWithBothCacheBuckets() {
        assertEquals(170L, result(complete(message(3,
            usage("totalTokens" to null, "cacheWriteTokens" to JsonPrimitive(0)))))?.totalTokens)
        assertNull(result(complete(message(3, usage("totalTokens" to null)))))
    }

    @Test fun finalUsageOverridesStreamAndStreamFillsMissingFinal() {
        val final = usage("inputTokens" to JsonPrimitive(30), "outputTokens" to JsonPrimitive(5),
            "cacheReadTokens" to JsonPrimitive(10), "totalTokens" to JsonPrimitive(45))
        assertEquals(45L, result(complete(message(3, final, usage())))?.totalTokens)
        assertEquals(170L, result(complete(message(3, null, usage())))?.totalTokens)
        // An invalid final sample must not silently fall back to a valid stream sample.
        assertNull(result(complete(message(3, usage("inputTokens" to JsonPrimitive(-1)), usage()))))
    }

    @Test fun lastDurableStreamSampleWinsWithoutCountingSamplesTwice() {
        val last = usage("totalTokens" to JsonPrimitive(180))
        val sample = event(3, "assistant/message", extra = """,
            "message":{"content":[],"source":{"provider":"deepseek","model":"deepseek-chat"}},
            "stream":[{"type":"chunk","chunk":{"type":"usage","usage":${usage()}}},
                      {"type":"chunk","chunk":{"type":"usage","usage":$last}}]""")
        assertEquals(180L, result(complete(sample))?.totalTokens)
    }

    @Test fun failedAttemptCountsExactlyOnceAcrossRetryAndWithoutRetryStart() {
        val retried = result(complete(attempt(3), event(4, "llm/retry"), event(5, "llm/retry-started"), message(6)))
        assertEquals(TurnTokenUsage(200, 40, 340, 100), retried)
        assertEquals(TurnTokenUsage(100, 20, 170, 50), result(complete(attempt(3), event(4, "llm/retry"))))
        assertEquals(170L, result(complete(attempt(3)))?.totalTokens)
        assertNull(result(complete(attempt(3, null), event(4, "llm/retry"), event(5, "llm/retry-started"), message(6))))
    }

    @Test fun multipleStepsSumWithDistinctRoutesAndOptionalBucketsRequireEveryAttempt() {
        val events = listOf(event(1, "turn/start"), event(2, "step/start"), message(3), event(4, "step/end"),
            event(5, "step/start", step = 2), message(6, step = 2, provider = "openai", model = "gpt-5"),
            event(7, "step/end", step = 2), event(8, "turn/end"))
        assertEquals(TurnTokenUsage(200, 40, 340, 100, routes = listOf(
            TurnTokenUsageRoute("deepseek", "deepseek-chat"), TurnTokenUsageRoute("openai", "gpt-5"))), result(events))
        val partial = events.toMutableList()
        partial[2] = message(3, usage("cacheWriteTokens" to JsonPrimitive(0), "reasoningTokens" to JsonPrimitive(2)))
        partial[5] = message(6, usage("cacheReadTokens" to null), step = 2, provider = "", model = "")
        assertEquals(TurnTokenUsage(200, 40, 340), result(partial))
        partial[5] = message(6, step = 2)
        assertEquals(listOf(TurnTokenUsageRoute("deepseek", "deepseek-chat")), result(partial)?.routes)
    }

    @Test fun rejectsInvalidCountersAndContradictoryTotals() {
        val invalid = listOf(
            usage("inputTokens" to JsonPrimitive(-1)),
            usage("outputTokens" to JsonPrimitive(1.5)),
            usage("totalTokens" to JsonPrimitive(9_007_199_254_740_992L)),
            usage("inputTokens" to JsonPrimitive("100")),
            usage("cacheReadTokens" to JsonPrimitive(-1)),
            usage("cacheWriteTokens" to JsonPrimitive(1.5)),
            usage("totalTokens" to JsonPrimitive(10)),
            usage("totalTokens" to JsonPrimitive(160)),
            usage("totalTokens" to JsonPrimitive(171), "cacheWriteTokens" to JsonPrimitive(0)),
            usage("reasoningTokens" to JsonPrimitive(21)),
            usage("inputTokens" to JsonPrimitive(9_007_199_254_740_991L), "outputTokens" to JsonPrimitive(0),
                "cacheReadTokens" to JsonPrimitive(1), "totalTokens" to JsonPrimitive(9_007_199_254_740_991L)),
            usage("inputTokens" to JsonPrimitive(9_007_199_254_740_991L), "outputTokens" to JsonPrimitive(1),
                "cacheReadTokens" to JsonPrimitive(0), "cacheWriteTokens" to JsonPrimitive(0), "totalTokens" to null),
        )
        for (value in invalid) assertNull(result(complete(message(3, value))), "$value")
    }

    @Test fun aggregationNeverExceedsJavascriptSafeInteger() {
        val half = 4_503_599_627_370_496L
        for (bucket in listOf("inputTokens", "outputTokens", "totalTokens")) {
            val value = usage("inputTokens" to JsonPrimitive(0), "outputTokens" to JsonPrimitive(0),
                "cacheReadTokens" to null, "totalTokens" to JsonPrimitive(half), bucket to JsonPrimitive(half))
            val events = listOf(event(1, "turn/start"), event(2, "step/start"), message(3, value), event(4, "step/end"),
                event(5, "step/start", step = 2), message(6, value, step = 2), event(7, "step/end", step = 2), event(8, "turn/end"))
            assertNull(result(events), bucket)
        }
    }

    @Test fun missingOrContradictoryLifecycleHidesWholeTurn() {
        val valid = complete(message(3))
        val invalid = listOf(
            valid.drop(1), valid.dropLast(1), valid.filterIndexed { index, _ -> index != 1 },
            valid.filterIndexed { index, _ -> index != 3 },
            complete(message(3, null, null)), complete(),
            listOf(event(1, "turn/start"), event(2, "turn/end")),
            listOf(event(1, "turn/start"), event(2, "turn/start")) + valid.drop(1),
            complete(event(3, "step/start", step = 2), message(4)),
            complete(message(3, step = 2)), complete(message(3, turn = 2)),
            complete(event(3, "llm/retry-started"), message(4)),
            complete(message(3), event(4, "llm/retry-started")),
            complete(message(3), event(4, "llm/retry")),
            complete(event(3, "llm/retry"), message(4)),
            complete(attempt(3), event(4, "llm/retry"), event(5, "llm/retry-started", step = 2), message(6)),
            complete(attempt(3), event(4, "llm/retry", step = 2)),
            complete(attempt(3, step = 2)),
            valid.dropLast(1) + event(91, "turn/end", turn = 2),
            valid + event(92, "turn/end"), valid + event(92, "step/start"),
        )
        for ((index, events) in invalid.withIndex()) assertNull(result(events), "lifecycle case $index")
    }

    @Test fun partialHistoryCanBeCompletedAcrossSeveralPrependPages() {
        val events = complete(attempt(3), event(4, "llm/retry"), event(5, "llm/retry-started"), message(6))
        val r = TranscriptReducer()
        r.apply(snapshot(events.drop(5), more = true))
        assertTrue(r.turnTokenUsage().isEmpty())
        assertTrue(r.prepend(JsonArray(events.subList(2, 5)), true))
        assertTrue(r.turnTokenUsage().isEmpty())
        assertTrue(r.prepend(JsonArray(events.take(2)), false))
        assertEquals(340L, r.turnTokenUsage()[1]?.totalTokens)
        assertFalse(r.prepend(JsonArray(events), false))
        assertEquals(340L, r.turnTokenUsage()[1]?.totalTokens)
    }

    @Test fun snapshotResetsAccountingAndDuplicateEventsDoNotRebill() {
        val events = complete(message(3))
        val r = reducer(events)
        val saved = r.turnTokenUsage()
        events.forEach(r::apply)
        r.apply(buildJsonObject { put("type", JsonPrimitive("resume")); put("records", JsonArray(events)) })
        assertEquals(saved, r.turnTokenUsage())
        r.apply(snapshot(events.drop(1), true))
        assertTrue(r.turnTokenUsage().isEmpty())
        assertEquals(170L, saved[1]?.totalTokens)
        r.prepend(JsonArray(events.take(1)), false)
        assertEquals(saved, r.turnTokenUsage())
        r.apply(snapshot(emptyList()))
        assertTrue(r.turnTokenUsage().isEmpty())
    }

    @Test fun completedMapAndRoutesAreImmutableIndependentSnapshots() {
        val r = reducer(complete(message(3)))
        val saved = r.turnTokenUsage()
        assertFailsWith<UnsupportedOperationException> { (saved as MutableMap<*, *>).clear() }
        assertFailsWith<UnsupportedOperationException> { (saved.getValue(1).routes as MutableList<*>).clear() }
        listOf(event(100, "turn/start", turn = 2), event(101, "step/start", turn = 2), message(102, turn = 2),
            event(103, "step/end", turn = 2), event(104, "turn/end", turn = 2)).forEach(r::apply)
        assertEquals(setOf(1), saved.keys)
        assertEquals(setOf(1, 2), r.turnTokenUsage().keys)
    }

    @Test fun sessionUsageAndTrajectoryKeepExistingMessageOnlyAccounting() {
        val r = reducer(complete(attempt(3), event(4, "llm/retry"), event(5, "llm/retry-started"), message(6)))
        assertEquals(340L, r.turnTokenUsage()[1]?.totalTokens)
        assertEquals(1, r.stepRecords().size)
        assertEquals(1, r.usage.steps)
        assertEquals(150L, r.usage.inputTokens)
        assertEquals(20L, r.usage.outputTokens)
    }
}
