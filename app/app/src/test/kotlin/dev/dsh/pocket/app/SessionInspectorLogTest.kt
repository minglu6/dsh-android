package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.*
import org.junit.Test

class SessionInspectorLogTest {
    private fun event(seq: Long, type: String = "custom/event") = jsonOf("type" to "event",
        "event" to jsonOf("seq" to seq, "time" to 1234L, "type" to type, "data" to jsonOf("secret" to "test-value")))
    private fun snapshot(vararg records: JsonElement) = jsonOf("type" to "snapshot", "records" to JsonArray(records.toList()))
    private fun chunk(text: String) = jsonOf("type" to "assistant-stream", "frame" to jsonOf(
        "type" to "chunk", "attemptId" to "a", "chunk" to jsonOf("type" to "text-delta", "text" to text)))

    @Test fun snapshotReplacesWindowAndResumeDeduplicatesActualEventRecords() {
        val log = SessionInspectorLog()
        log.apply(snapshot(event(20), event(21)))
        log.apply(jsonOf("type" to "resume", "records" to JsonArray(listOf(event(21), event(22)))))
        log.apply(event(22))
        assertEquals(listOf(20L, 21L, 22L), log.snapshot().map { it["seq"].long() })
        log.apply(snapshot(event(5)))
        assertEquals(listOf(5L), log.snapshot().map { it["seq"].long() })
        assertEquals("test-value", log.snapshot().single()["data"]["secret"].str())
    }

    @Test fun earlierAssistantHistoryDoesNotEraseCurrentObservedStream() {
        val log = SessionInspectorLog()
        log.apply(snapshot(event(20)))
        val observed = chunk("live")
        log.apply(observed)
        val key = inspectorRawRows(log.snapshot()).last().key
        log.prepend(JsonArray(listOf(event(5, "assistant/message"), event(20))))
        assertEquals(listOf(5L, 20L, null), log.snapshot().map { it["seq"].long() })
        assertSame(observed, log.snapshot().last())
        assertEquals(key, inspectorRawRows(log.snapshot()).last().key)
        log.apply(event(21, "assistant/message"))
        assertEquals(listOf(5L, 20L, 21L), log.snapshot().map { it["seq"].long() })
    }

    @Test fun snapshotsRemainImmutableAcrossAppendsReplacementAndClear() {
        val log = SessionInspectorLog()
        log.apply(snapshot(event(20)))
        val first = log.snapshot()
        log.apply(chunk("one"))
        val second = log.snapshot()
        log.apply(chunk("two"))
        log.apply(event(21))
        log.prepend(JsonArray(listOf(event(1))))
        log.clear()
        assertEquals(1, first.size)
        assertEquals(20L, first.single()["seq"].long())
        assertEquals(2, second.size)
        assertEquals("one", second.last()["frame"]["chunk"]["text"].str())
        assertTrue(log.snapshot().isEmpty())
    }

    @Test fun baselinePreservesActualObjectAndControlFramesStayOut() {
        val log = SessionInspectorLog()
        val baseline = jsonOf("activeAttempt" to jsonOf("attemptId" to "a", "stream" to JsonArray(emptyList())))
        log.apply(jsonOf("type" to "snapshot", "records" to JsonArray(emptyList()), "assistantStream" to baseline))
        log.apply(jsonOf("type" to "projection", "key" to "title", "seq" to 7))
        assertSame(baseline, log.snapshot().single())
        val row = inspectorRawRows(log.snapshot()).single()
        assertNull(row.seq)
        assertNull(row.time)
        log.apply(jsonOf("type" to "resume", "records" to JsonArray(emptyList()), "assistantStream" to jsonOf()))
        assertTrue(log.snapshot().isEmpty())
    }

    @Test fun observedFramesDoNotInventServerSequenceOrClock() {
        val log = SessionInspectorLog()
        log.apply(chunk("live"))
        val row = inspectorRawRows(log.snapshot()).single()
        assertNull(row.seq)
        assertNull(row.time)
    }
}
