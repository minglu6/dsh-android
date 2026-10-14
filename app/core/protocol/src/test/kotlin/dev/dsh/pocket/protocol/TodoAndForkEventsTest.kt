package dev.dsh.pocket.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodoAndForkEventsTest {
    private fun el(s: String) = PocketJson.parseToJsonElement(s)

    @Test fun todoWriteUpdatesSnapshot() {
        val r = TranscriptReducer()
        r.apply(el("""{"type":"event","event":{"type":"todo/write","seq":5,"time":1,"data":{"todos":[{"content":"读配置","status":"completed"},{"content":"改代码","status":"in_progress"}]}}}"""))
        assertEquals(2, r.todos().size)
        assertEquals("读配置", r.todos()[0].content)
        assertEquals("completed", r.todos()[0].status)
        assertEquals("in_progress", r.todos()[1].status)
    }

    @Test fun projectionsTodosPreferredOnSnapshot() {
        val r = TranscriptReducer()
        r.apply(el("""{"type":"snapshot","cursor":0,"hasMore":false,"records":[],"projections":{"values":{"todos":[{"content":"从投影来","status":"pending"}]}}}"""))
        assertEquals(listOf(TodoItem("从投影来", "pending")), r.todos())
    }

    @Test fun turnEndRetainsFailureAndMaxTokensNotice() {
        val r = TranscriptReducer()
        r.apply(el("""{"type":"event","event":{"type":"turn/start","seq":1,"time":0,"data":{"turn":1}}}"""))
        r.apply(el("""{"type":"event","event":{"type":"turn/end","seq":2,"time":1,"data":{"turn":1,"reason":{"kind":"error","error":{"code":"AUTH_ERROR","message":"bad key"}}}}}"""))
        val failure = r.items().filterIsInstance<Item.TurnFailure>().single()
        assertEquals("bad key", failure.message)
        assertEquals("AUTH_ERROR", failure.code)
        assertEquals(1, failure.turn)

        val r2 = TranscriptReducer()
        r2.apply(el("""{"type":"event","event":{"type":"turn/start","seq":1,"time":0,"data":{"turn":1}}}"""))
        r2.apply(el("""{"type":"event","event":{"type":"turn/end","seq":2,"time":1,"data":{"turn":1,"reason":{"kind":"max-tokens"}}}}"""))
        assertTrue(r2.items().any { it is Item.Notice && it.text == "已达到输出 token 上限" })
    }

    @Test fun userAndAssistantCarrySeqForFork() {
        val r = TranscriptReducer()
        r.apply(el("""{"type":"event","event":{"type":"user/message","seq":10,"time":1000,"data":{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user"}}}}"""))
        r.apply(el("""{"type":"event","event":{"type":"assistant/message","seq":11,"time":2000,"data":{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"hello"}]},"stream":[]}}}"""))
        val user = r.items().filterIsInstance<Item.User>().single()
        val answer = r.items().filterIsInstance<Item.Text>().single()
        assertEquals(10L, user.seq)
        assertEquals(1000L, user.at)
        assertEquals(11L, answer.seq)
        assertEquals(2000L, answer.at)
    }
}
