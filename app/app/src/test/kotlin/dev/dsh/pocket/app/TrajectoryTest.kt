package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TranscriptReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
// Lane/segment projection covered by protocol TrajectoryTimelineTest (desktop timeline.ts port).

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

    @Test fun includesSystemContextAndTurnLabels() {
        val turns = buildTrajectory(listOf(
            Item.System("sys1", "初始系统提示词", "You are helpful.", at = 100),
            Item.User("u1", "你好", at = 200),
            Item.Context("x1", "Current runtime context. This snapshot supersedes earlier…", "runtime-context", at = 210),
            Item.Context("x2", "<system-reminder>\nA skill is a reusable set…", null, at = 220),
            Item.Context("x3", "<hindsight_knowledge>\nThis repository has…", null, at = 230),
            Item.Context("x4", "Time sampled while preparing turn 1, step 1: 2026-10-12T22:53:55…", null, at = 240),
            Item.Text("t1", "你好！有什么可以帮你吗？", false),
            Item.User("u2", "继续", at = 500),
        ))
        assertEquals(2, turns.size)
        assertEquals(1, turns[0].index)
        assertEquals("第 ${turns[0].index} 轮", "第 1 轮")
        assertEquals("系统", turns[0].rows[0].kind)
        assertEquals("初始系统提示词", turns[0].rows[0].title)
        assertEquals("用户", turns[0].rows[1].kind)
        assertEquals(4, turns[0].rows.count { it.kind == "上下文" })
        assertTrue(turns[0].rows.any { it.kind == "上下文" && it.title.startsWith("Current runtime context") })
        assertTrue(turns[0].rows.none { it.title.contains('\n') })
        assertEquals(2, turns[1].index)
    }

    @Test fun searchMatchesAndTerms() {
        val turns = buildTrajectory(listOf(
            Item.User("u1", "hello world"),
            Item.Context("x1", "runtime context cwd=/tmp", "runtime-context"),
            Item.Tool("t1", "c1", "read", """{"path":"a.kt"}""", ToolStatus.Ok),
        ))
        assertNull(trajectorySearchMatches(turns, "  "))
        val hits = assertNotNull(trajectorySearchMatches(turns, "runtime cwd"))
        assertEquals(setOf("x1"), hits)
        assertTrue(trajectorySearchMatches(turns, "read")!!.contains("t1"))
    }
}
