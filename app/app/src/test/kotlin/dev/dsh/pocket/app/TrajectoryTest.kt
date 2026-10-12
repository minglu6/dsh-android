package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TranscriptReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrajectoryTest {
    private fun j(s: String) = PocketJson.parseToJsonElement(s)
    private fun ev(seq: Int, type: String, data: String, time: Long) =
        j("""{"type":"event","event":{"type":"$type","seq":$seq,"time":$time,"data":$data}}""")

    @Test fun groupsUserTurnWithTools() {
        val turns = buildTrajectory(listOf(
            Item.User("u1", "hello"),
            Item.Reasoning("r1", "think", 1000, false),
            Item.Tool("t1", "c1", "read", """{"path":"a.kt"}""", ToolStatus.Ok, startedAt = 0, endedAt = 40),
            Item.Text("x1", "done", false),
            Item.User("u2", "more"),
            Item.Text("x2", "ok", false),
        ))
        assertEquals(2, turns.size)
        assertEquals(1, turns[0].index)
        assertTrue(turns[0].rows.any { it.kind == "用户" })
        assertTrue(turns[0].rows.any { it.kind == "工具" && it.title.startsWith("read") })
        assertTrue(turns[0].rows.none { it.kind == "思考" })
        assertEquals(2, turns[1].index)
    }

    @Test fun assistantRowShowsUsageAndElapsedFromStepRecords() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"hasMore":false,"projections":{}}"""))
        r.apply(ev(1, "turn/start", """{"turn":1}""", 1_000))
        r.apply(ev(2, "user/message", """{"content":[{"type":"text","text":"你好"}],"source":{"kind":"user"},"role":"user"}""", 1_001))
        r.apply(ev(3, "step/start", """{"turn":1,"step":1}""", 10_000))
        r.apply(ev(4, "assistant/message", """{"turn":1,"step":1,"stream":[],"usage":{"inputTokens":17730,"outputTokens":49},
            "message":{"source":{"provider":"deepseek","model":"deepseek-v4.1-flash"},
            "content":[{"type":"text","text":"你好！有什么可以帮忙的？"}]}}""", 12_200))
        val turns = buildTrajectory(r.items(), r.stepRecords())
        assertEquals(1, turns.size)
        val assistant = turns[0].rows.single { it.kind == "助手" }
        assertEquals("你好！有什么可以帮忙的？", assistant.title)
        assertEquals("17.7k", assistant.input)
        assertEquals("49", assistant.output)
        assertEquals("—", assistant.think)
        assertTrue(assistant.time.startsWith("2.2"), "time=${assistant.time}")
        assertTrue(turns[0].rows.none { it.kind == "思考" })
        assertEquals(formatElapsed(0), turns[0].rows.single { it.kind == "用户" }.time)
    }
}
