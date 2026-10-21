package dev.dsh.pocket.protocol

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TodoLifecycleTest {
    private fun list(content: String = "Ship change", status: String = "completed") =
        JsonArray(listOf(jsonOf("content" to content, "status" to status)))
    private val completed get() = listOf(TodoItem("Ship change", "completed"))
    private fun event(seq: Long, type: String, data: JsonObject = jsonOf()) =
        jsonOf("type" to "event", "event" to jsonOf("seq" to seq, "type" to type, "data" to data))
    private fun write(seq: Long, value: JsonElement = list()) = event(seq, "todo/write", jsonOf("todos" to value))
    private fun projection(seq: Long, value: JsonElement) =
        jsonOf("type" to "projection", "key" to "todos", "seq" to seq, "value" to value)
    private fun opening(cursor: Long, records: List<JsonElement> = emptyList(), values: JsonObject = jsonOf(),
        type: String = "snapshot", asOfSeq: Long = cursor) =
        jsonOf("type" to type, "cursor" to cursor, "records" to JsonArray(records),
            "projections" to jsonOf("asOfSeq" to asOfSeq, "values" to values))

    @Test fun completedPlanRemainsAtTurnEndAndClearsOnlyWhenNextTurnStarts() {
        val r = TranscriptReducer()
        r.apply(event(1, "turn/start", jsonOf("turn" to 1)))
        r.apply(write(2, list(status = "in_progress")))
        assertEquals(listOf(TodoItem("Ship change", "in_progress")), r.todos())
        r.apply(write(3))
        r.apply(event(4, "turn/end", jsonOf("turn" to 1)))
        assertEquals(completed, r.todos())
        assertFalse(r.running)
        r.apply(event(5, "turn/start", jsonOf("turn" to 2)))
        assertTrue(r.todos().isEmpty())
        assertTrue(r.running)
        r.apply(write(6, list("Next plan", "pending")))
        assertEquals(listOf(TodoItem("Next plan", "pending")), r.todos())
    }

    @Test fun explicitNullAndEmptySnapshotClearReplayedCompletedPlan() {
        for (value in listOf(JsonNull, JsonArray(emptyList()))) {
            val r = TranscriptReducer()
            r.apply(opening(4, listOf(write(2), event(3, "turn/end")), jsonOf("todos" to value)))
            assertTrue(r.todos().isEmpty())
            assertEquals(4L, r.cursor)
        }
    }

    @Test fun absentProjectionDoesNotInventClearAndHistoryStillHonorsTurnStart() {
        val r = TranscriptReducer()
        r.apply(opening(4, listOf(write(2), event(3, "turn/end"))))
        assertEquals(completed, r.todos())
        r.apply(opening(6, listOf(write(2), event(5, "turn/start"))))
        assertTrue(r.todos().isEmpty())
    }

    @Test fun streamNullAndEmptyClearWithoutAdvancingHistoricalCursor() {
        val r = TranscriptReducer()
        r.apply(opening(100))
        assertTrue(r.apply(projection(10, list())))
        assertEquals(completed, r.todos())
        assertTrue(r.apply(projection(11, JsonNull)))
        assertTrue(r.todos().isEmpty())
        r.apply(projection(12, list()))
        assertTrue(r.apply(projection(13, JsonArray(emptyList()))))
        assertTrue(r.todos().isEmpty())
        assertEquals(100L, r.cursor)
    }

    @Test fun explicitEmptyTodoWriteClearsTheStandingList() {
        val r = TranscriptReducer()
        r.apply(write(1))
        r.apply(write(2, JsonArray(emptyList())))
        assertTrue(r.todos().isEmpty())
    }

    @Test fun staleProjectionCannotResurrectCompletedTasksAfterNewTurn() {
        val r = TranscriptReducer()
        r.apply(write(10))
        r.apply(event(11, "turn/start"))
        assertFalse(r.apply(projection(10, list())))
        assertFalse(r.apply(write(10)))
        assertTrue(r.todos().isEmpty())
    }

    @Test fun newerProjectionSurvivesOlderEventsWithoutSkippingOtherHistory() {
        val r = TranscriptReducer()
        r.apply(opening(10))
        r.apply(projection(20, list("Current plan", "pending")))
        r.apply(write(11))
        r.apply(event(12, "turn/start", jsonOf("turn" to 2)))
        assertEquals(listOf(TodoItem("Current plan", "pending")), r.todos())
        assertEquals(12L, r.cursor)
        assertEquals(listOf(2), r.turnRecords().map { it.turn })
        assertTrue(r.running)
        r.apply(event(21, "turn/start", jsonOf("turn" to 3)))
        assertTrue(r.todos().isEmpty())
    }

    @Test fun newerClearProjectionCannotBeOverwrittenByOlderTodoWrite() {
        val r = TranscriptReducer()
        r.apply(opening(10))
        r.apply(projection(20, JsonNull))
        r.apply(write(11))
        assertTrue(r.todos().isEmpty())
        assertEquals(11L, r.cursor)
    }

    @Test fun sameCutUpdatesAreIdempotentAndDoNotDiscardTheMatchingEvent() {
        val r = TranscriptReducer()
        assertTrue(r.apply(projection(10, list())))
        assertTrue(r.apply(projection(10, list())))
        assertTrue(r.apply(write(10)))
        assertEquals(completed, r.todos())
        assertEquals(10L, r.cursor)
        r.apply(event(11, "turn/start"))
        assertTrue(r.todos().isEmpty())
    }

    @Test fun snapshotResetsPreviousProjectionGeneration() {
        val r = TranscriptReducer()
        r.apply(projection(200, list()))
        r.apply(opening(10, values = jsonOf("todos" to JsonNull)))
        assertTrue(r.todos().isEmpty())
        assertTrue(r.apply(projection(11, list("Reconnected", "pending"))))
        assertEquals(listOf(TodoItem("Reconnected", "pending")), r.todos())
    }

    @Test fun reconnectAcceptsLowerProjectionCutButDoesNotRewindHistory() {
        val r = TranscriptReducer()
        r.apply(opening(100, listOf(write(10))))
        r.apply(projection(200, list()))
        r.clearComposerProjections()
        r.apply(opening(100, type = "resume", asOfSeq = 20, values = jsonOf("todos" to JsonNull)))
        assertTrue(r.todos().isEmpty())
        assertFalse(r.apply(projection(19, list())))
        assertTrue(r.apply(projection(21, list("Reconnected", "pending"))))
        assertEquals(listOf(TodoItem("Reconnected", "pending")), r.todos())
        assertEquals(100L, r.cursor)
    }

    @Test fun ordinaryResumeCannotRollbackANewerProjection() {
        val r = TranscriptReducer()
        r.apply(opening(10))
        r.apply(projection(30, list()))
        r.apply(opening(20, type = "resume", asOfSeq = 20, values = jsonOf("todos" to JsonNull)))
        assertEquals(completed, r.todos())
        assertFalse(r.apply(projection(29, JsonNull)))
    }

    @Test fun resumeNullOrEmptyClearsWhileMissingKeyRetainsThePlan() {
        for (value in listOf(JsonNull, JsonArray(emptyList()))) {
            val r = TranscriptReducer()
            r.apply(write(1))
            r.apply(opening(2, type = "resume"))
            assertEquals(completed, r.todos())
            r.apply(opening(3, type = "resume", values = jsonOf("todos" to value)))
            assertTrue(r.todos().isEmpty())
        }
    }

    @Test fun resumeReplaysNewTurnClearWhenProjectionCapabilityIsAbsent() {
        val r = TranscriptReducer()
        r.apply(write(1))
        r.apply(opening(3, listOf(event(2, "turn/start"), event(3, "turn/end")), type = "resume"))
        assertTrue(r.todos().isEmpty())
    }

    @Test fun reconnectKeepsDurableEventBarrierAgainstOldBaseline() {
        val r = TranscriptReducer()
        r.apply(write(10))
        r.apply(projection(100, JsonNull))
        r.apply(event(20, "turn/start"))
        r.clearComposerProjections()
        r.apply(opening(20, type = "resume", asOfSeq = 15, values = jsonOf("todos" to list())))
        assertTrue(r.todos().isEmpty())
        assertFalse(r.apply(projection(19, list())))
        assertTrue(r.apply(projection(21, list("Current", "pending"))))
    }

    @Test fun prependingOldTodoHistoryCannotReviveClearedDockOrEraseToolDetails() {
        val r = TranscriptReducer()
        r.apply(opening(30, listOf(event(20, "turn/start")), jsonOf("todos" to JsonNull)))
        val arguments = """{"todos":[{"content":"Ship change","status":"completed"}]}"""
        assertTrue(r.prepend(JsonArray(listOf(
            event(1, "turn/start", jsonOf("turn" to 1)),
            event(2, "tool/call", jsonOf("callId" to "old-todo", "name" to "todo_write", "arguments" to arguments)),
            write(3), event(4, "turn/end", jsonOf("turn" to 1)),
        )), false))
        assertTrue(r.todos().isEmpty())
        assertEquals(arguments, r.items().filterIsInstance<Item.Tool>().single { it.callId == "old-todo" }.arguments)
        assertEquals(30L, r.cursor)
        assertEquals(1L, r.firstSeq)
        assertFalse(r.apply(projection(3, list())))
    }
}
