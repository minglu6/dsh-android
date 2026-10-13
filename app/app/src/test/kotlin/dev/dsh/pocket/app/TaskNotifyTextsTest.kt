package dev.dsh.pocket.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskNotifyTextsTest {
    @Test
    fun buildsDoneAndQuestionCopyWithSessionTitle() {
        assertEquals("任务已完成", TaskNotifyTexts.title(TaskEvent.Kind.Done))
        assertEquals("需要你确认", TaskNotifyTexts.title(TaskEvent.Kind.Question))
        assertEquals("你的 DSH 任务已完成", TaskNotifyTexts.body(TaskEvent.Kind.Done, null))
        assertEquals("你的 DSH 任务已完成：修 bug", TaskNotifyTexts.body(TaskEvent.Kind.Done, "修 bug"))
        assertEquals("等待你的回答", TaskNotifyTexts.body(TaskEvent.Kind.Question, null))
        assertEquals("计划 — 等待你的回答", TaskNotifyTexts.body(TaskEvent.Kind.Question, "计划"))
    }

    @Test
    fun tagsKeepSessionsAndQuestionsDistinct() {
        val done = TaskEvent(TaskEvent.Kind.Done, "s1", turn = 3)
        val q = TaskEvent(TaskEvent.Kind.Question, "s1", questionId = "q9")
        assertEquals("dsh-task-done-s1-3", TaskNotifyTexts.tag(done))
        assertEquals("dsh-task-question-q9", TaskNotifyTexts.tag(q))
        assertTrue(TaskNotifyTexts.tag(done) != TaskNotifyTexts.tag(q))
    }

    @Test
    fun parsesMinimalTaskWireEvents() {
        val done = TaskEvent.parse(Json.parseToJsonElement("""{"ev":"task","kind":"done","sessionId":"s1","turn":4}""").jsonObject)!!
        assertEquals(TaskEvent.Kind.Done, done.kind)
        assertEquals("s1", done.sessionId)
        assertEquals(4L, done.turn)
        val q = TaskEvent.parse(Json.parseToJsonElement("""{"ev":"task","kind":"question","sessionId":"s1","questionId":"q1"}""").jsonObject)!!
        assertEquals(TaskEvent.Kind.Question, q.kind)
        assertEquals("q1", q.questionId)
        assertNull(TaskEvent.parse(Json.parseToJsonElement("""{"ev":"task","kind":"other","sessionId":"s1"}""").jsonObject))
        assertNull(TaskEvent.parse(Json.parseToJsonElement("""{"ev":"task","kind":"done"}""").jsonObject))
    }
}
