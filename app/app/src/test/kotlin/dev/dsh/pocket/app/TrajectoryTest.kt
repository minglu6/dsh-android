package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.CompactionStatus
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TranscriptReducer
import dev.dsh.pocket.protocol.trajectoryStatusLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-kind port of desktop ui-trajectory ledger + detailTabs
 * (`deepseek-harness` @ d743267 / locales.ts + TrajectoryTable.tsx).
 */
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
        assertTrue(turns[0].rows.any { it.kind == TrajL.kindUser })
        assertTrue(turns[0].rows.any { it.kind == TrajL.kindTool && it.title.startsWith("read") })
        assertTrue(turns[0].rows.none { it.kind == TrajL.recordThinking })
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
        val assistant = turns[0].rows.single { it.kind == TrajL.kindAssistant }
        assertEquals("你好！有什么可以帮忙的？", assistant.title)
        assertEquals("17.7k", assistant.input)
        assertEquals("49", assistant.output)
        assertEquals("—", assistant.think)
        assertTrue(assistant.time.startsWith("2.2"), "time=${assistant.time}")
        assertTrue(turns[0].rows.none { it.kind == TrajL.recordThinking })
        assertEquals(formatElapsed(0), turns[0].rows.single { it.kind == TrajL.kindUser }.time)
    }

    @Test fun interruptedAssistantIsErrorNotSystemNotice() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"hasMore":false,"projections":{}}"""))
        r.apply(ev(1, "turn/start", """{"turn":1}""", 1_000))
        r.apply(ev(2, "user/message", """{"content":[{"type":"text","text":"hi"}],"source":{"kind":"user"},"role":"user"}""", 1_001))
        r.apply(ev(3, "step/start", """{"turn":1,"step":1}""", 2_000))
        r.apply(ev(4, "assistant/message", """{"turn":1,"step":1,"interrupted":true,"stream":[],"usage":{},
            "message":{"content":[{"type":"text","text":"partial"}]}}""", 2_500))
        assertTrue(r.items().none { it is Item.Notice })
        val assistant = buildTrajectory(r.items(), r.stepRecords())[0].rows.single { it.kind == TrajL.kindAssistant }
        assertTrue(assistant.isError)
        assertEquals("失败", trajectoryStatusLabel(trajRowState(assistant)))
    }

    @Test fun kindSystemInitialAndUpdated() {
        val turns = buildTrajectory(listOf(
            Item.System("sys1", TrajL.layoutInitialSystemPrompt, "You are helpful.", at = 100, toolsJson = """[{"name":"read","description":"d","parameters":{}}]"""),
            Item.System(
                "sys2", TrajL.layoutSystemPromptUpdated, "You are updated.", at = 150,
                previousText = "You are helpful.",
                toolsJson = """[{"name":"read","description":"d","parameters":{}},{"name":"bash","description":"b","parameters":{}}]""",
                previousToolsJson = """[{"name":"read","description":"d","parameters":{}}]""",
            ),
            Item.User("u1", "你好", at = 200),
        ))
        val sys = turns[0].rows.filter { it.kind == TrajL.kindSystem }
        assertEquals(2, sys.size)
        assertEquals(TrajL.layoutInitialSystemPrompt, sys[0].title)
        assertEquals(
            listOf(TrajDetailTab.SystemPrompt, TrajDetailTab.Tools),
            trajDetailTabs(sys[0]),
        )
        assertEquals(
            listOf(TrajDetailTab.Diff, TrajDetailTab.SystemPrompt, TrajDetailTab.Tools),
            trajDetailTabs(sys[1]),
        )
        assertNotNull(toolCatalogDiff(sys[1].previousToolsJson, sys[1].toolsJson))
    }

    @Test fun kindContextInjections() {
        val turns = buildTrajectory(listOf(
            Item.User("u1", "你好", at = 200, messageSourceJson = """{"kind":"user"}"""),
            Item.Context("x1", "Current runtime context. This snapshot supersedes earlier…", "runtime-context", at = 210, messageSourceJson = """{"kind":"plugin"}"""),
            Item.Context("x2", "<system-reminder>\nA skill is a reusable set…", null, at = 220),
            Item.Context("x3", "<hindsight_knowledge>\nThis repository has…", null, at = 230),
            Item.Context("x4", "Time sampled while preparing turn 1, step 1: 2026-10-12T22:53:55…", null, at = 240),
        ))
        assertEquals(4, turns[0].rows.count { it.kind == TrajL.kindContext })
        val withSource = turns[0].rows.first { it.key == "x1" }
        assertTrue(trajDetailTabs(withSource).contains(TrajDetailTab.Source))
        assertTrue(turns[0].rows.none { it.title.contains('\n') })
    }

    @Test fun kindUserWithSourceTab() {
        val row = buildTrajectory(listOf(
            Item.User("u1", "hello", messageSourceJson = """{"kind":"user","rpcId":"r1"}"""),
        ))[0].rows.single { it.kind == TrajL.kindUser }
        assertEquals(
            listOf(TrajDetailTab.Overview, TrajDetailTab.Preview, TrajDetailTab.Raw, TrajDetailTab.Source),
            trajDetailTabs(row),
        )
    }

    @Test fun kindAssistantPreviewTabsAndToolCallOnly() {
        val toolOnly = buildTrajectory(listOf(Item.User("u", "x"), Item.Text("t", "", false)))[0]
            .rows.single { it.kind == TrajL.kindAssistant }
        assertTrue(toolOnly.toolCallOnly)
        assertEquals(TrajL.layoutToolCallOnly, toolOnly.title)
        assertEquals(
            listOf(TrajDetailTab.Overview, TrajDetailTab.Preview, TrajDetailTab.Raw, TrajDetailTab.Timing),
            trajDetailTabs(toolOnly),
        )
    }

    @Test fun kindToolDetailTabsAndInlineResult() {
        val tool = Item.Tool(
            "t1", "c1", "read", """{"file_path":"/tmp/a.yml"}""",
            ToolStatus.Ok, result = "<path>/tmp/a.yml</path>",
            startedAt = 1_000, endedAt = 1_011,
            schemaJson = """{"name":"read","description":"Read a UTF-8 text file and return line-numbered content.","parameters":{"type":"object"}}""",
            turn = 6, step = 1,
        )
        val row = buildTrajectory(listOf(Item.User("u1", "hi"), tool))[0].rows.single { it.kind == TrajL.kindTool }
        assertEquals(
            listOf(TrajDetailTab.Overview, TrajDetailTab.Input, TrajDetailTab.Output, TrajDetailTab.Schema, TrajDetailTab.Timing),
            trajDetailTabs(row),
        )
        assertEquals(TrajL.kindTool, trajDetailKindLabel(row.kind))
        assertEquals("${TrajL.turnLabel(6)} · ${TrajL.groupStep(1)}", trajDetailLocation(row, turnIndex = 1))
        assertEquals(TrajL.statusCompleted, trajectoryStatusLabel(trajRowState(row)))
        assertTrue(row.title.contains(" · "))
        assertNotNull(row.resultPreview)
        assertTrue(row.resultPreview!!.contains("<path>"))
    }

    @Test fun kindSubtoolFromParentCallId() {
        val parent = Item.Tool("t0", "root", PTC_TOOL_NAME, """{"code":"await read({path:'a'})","description":"read a"}""", ToolStatus.Ok, turn = 1, step = 1)
        val sub = Item.Tool(
            "t1", "root:ptc:1", "read", """{"path":"a"}""", ToolStatus.Ok, result = "ok",
            turn = 1, step = 1, parentCallId = "root",
        )
        val rows = buildTrajectory(listOf(Item.User("u", "hi"), parent, sub))[0].rows
        assertTrue(rows.any { it.kind == TrajL.kindTool && it.toolName == PTC_TOOL_NAME })
        val subRow = rows.single { it.kind == TrajL.kindSubtool }
        assertEquals(2, subRow.depth)
        assertTrue(trajDetailTabs(subRow).contains(TrajDetailTab.Overview))
        val runCode = rows.single { it.toolName == PTC_TOOL_NAME }
        assertTrue(trajDetailTabs(runCode).contains(TrajDetailTab.Code))
        assertTrue(runCode.title.startsWith("$PTC_TOOL_NAME · "))
    }

    @Test fun kindCompactedRunningAndDone() {
        val running = compactedFrom(Item.Compacted("c1", "id1", CompactionStatus.Running, startedAt = 10))
        assertEquals(TrajL.layoutCompacting, running.title)
        assertEquals(TrajL.statusPending, trajectoryStatusLabel(trajRowState(running)))
        assertEquals(listOf(TrajDetailTab.Overview, TrajDetailTab.RawOutput), trajDetailTabs(running))

        val done = compactedFrom(
            Item.Compacted("c2", "id2", CompactionStatus.Ok, summary = "Kept the goal.", startedAt = 10, endedAt = 40),
        )
        assertEquals("Kept the goal.", done.title)
        assertEquals(TrajL.statusCompleted, trajectoryStatusLabel(trajRowState(done)))

        val failed = compactedFrom(
            Item.Compacted("c3", "id3", CompactionStatus.Error, interrupted = true, startedAt = 10, endedAt = 20),
        )
        assertEquals(TrajL.layoutCompactionInterrupted, failed.title)
        assertTrue(failed.isError)
    }

    private fun compactedFrom(item: Item.Compacted): TrajRow =
        buildTrajectory(listOf(Item.User("u", "hi"), item))[0].rows.single { it.kind == TrajL.kindCompacted }

    @Test fun pendingToolOmitsResultTabAndShowsWaiting() {
        val tool = Item.Tool("t1", "c1", "bash", """{"command":"ls"}""", ToolStatus.Running, turn = 1, step = 2)
        val row = buildTrajectory(listOf(Item.User("u1", "hi"), tool))[0].rows.single { it.kind == TrajL.kindTool }
        assertEquals(
            listOf(TrajDetailTab.Overview, TrajDetailTab.Input, TrajDetailTab.Schema, TrajDetailTab.Timing),
            trajDetailTabs(row),
        )
        assertEquals(TrajL.statusPending, trajectoryStatusLabel(trajRowState(row)))
        assertEquals("${TrajL.turnLabel(1)} · ${TrajL.groupStep(2)}", trajDetailLocation(row, turnIndex = 1))
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

    @Test fun localesAreDesktopZh() {
        assertEquals("轨迹", TrajL.viewTrajectory)
        assertEquals("系统", TrajL.kindSystem)
        assertEquals("已压缩", TrajL.kindCompacted)
        assertEquals("子工具", TrajL.kindSubtool)
        assertEquals("（仅工具调用）", TrajL.recordToolCallOnly)
        assertEquals("初始系统提示词", TrajL.layoutInitialSystemPrompt)
        assertEquals("失败", TrajL.statusFailed)
    }

    @Test fun includesSystemContextAndTurnLabels() {
        val turns = buildTrajectory(listOf(
            Item.System("sys1", TrajL.layoutInitialSystemPrompt, "You are helpful.", at = 100),
            Item.User("u1", "你好", at = 200),
            Item.Context("x1", "Current runtime context. This snapshot supersedes earlier…", "runtime-context", at = 210),
            Item.Text("t1", "你好！有什么可以帮你吗？", false),
            Item.User("u2", "继续", at = 500),
        ))
        assertEquals(2, turns.size)
        assertEquals(TrajL.turnLabel(1), TrajL.turnLabel(turns[0].index))
        assertEquals(TrajL.kindSystem, turns[0].rows[0].kind)
        assertEquals(TrajL.layoutInitialSystemPrompt, turns[0].rows[0].title)
        assertEquals(TrajL.kindUser, turns[0].rows[1].kind)
    }
}
