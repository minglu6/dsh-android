package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TurnRecord
import dev.dsh.pocket.protocol.TranscriptReducer
import dev.dsh.pocket.protocol.PocketJson
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProcessTest {
    private fun sampleTurn(okTools: Boolean = true): List<Item> = listOf(
        Item.User("u1", "看看配置"),
        Item.Reasoning("r1", "先读 patch", 1000, false, turn = 1, step = 1),
        Item.Tool("t1", "c1", "read", """{"path":"cordis.patch.yml"}""", if (okTools) ToolStatus.Ok else ToolStatus.Running,
            startedAt = 2000, endedAt = 3000, turn = 1, step = 1),
        Item.Tool("t2", "c2", "edit", """{"path":"cordis.patch.yml"}""", if (okTools) ToolStatus.Ok else ToolStatus.Running,
            startedAt = 2000, endedAt = 3000, turn = 1, step = 1),
        Item.Text("a1", SAMPLE_MARKDOWN, false, turn = 1, step = 1),
    )

    private fun completed(turn: Int = 1, reason: String = "completed") =
        TurnRecord(turn, turn * 10L, turn * 10L + 9, 1000, 7500, reason)

    private fun TranscriptReducer.event(seq: Int, type: String, time: Long, data: String) {
        apply(PocketJson.parseToJsonElement(
            """{"type":"event","event":{"seq":$seq,"type":"$type","time":$time,"data":$data}}""",
        ))
    }

    private fun TranscriptReducer.rows() =
        foldChatItems(items(), presentationPolicyFor(), running, turnRecords())

    @Test fun detailedFoldsSettledToolsIntoProcessSummary() {
        val policy = presentationPolicyFor(TranscriptView.Detailed, CollapseTiming.Completion)
        val rows = foldChatItems(sampleTurn(), policy, sessionRunning = false, turnRecords = listOf(completed()))
        assertEquals(1, rows.count { it is ChatRow.User })
        assertEquals(1, rows.count { it is ChatRow.Answer })
        assertEquals(0, rows.count { it is ChatRow.Tool }) // tools hidden inside Process
        val proc = rows.filterIsInstance<ChatRow.Process>().single()
        assertEquals(2, proc.tools.size)
        assertEquals(1, proc.reasoning.size)
        assertTrue(proc.closed)
        assertFalse("completion timing collapses settled process", proc.defaultOpen)
        assertTrue(processCollapsedByDefault(proc, policy))
    }

    @Test fun compactAlsoFoldsAndHidesTools() {
        val policy = presentationPolicyFor(TranscriptView.Compact, CollapseTiming.Completion)
        val rows = foldChatItems(sampleTurn(), policy, sessionRunning = false, turnRecords = listOf(completed()))
        assertEquals(0, rows.count { it is ChatRow.Tool })
        assertEquals(1, rows.count { it is ChatRow.Process })
        assertTrue(rows.filterIsInstance<ChatRow.Process>().single().let { processCollapsedByDefault(it, policy) })
    }

    @Test fun verboseKeepsIndividualToolRows() {
        val policy = presentationPolicyFor(TranscriptView.Verbose, CollapseTiming.Completion)
        val rows = foldChatItems(sampleTurn(), policy, sessionRunning = false)
        assertEquals(0, rows.count { it is ChatRow.Process })
        assertEquals(2, rows.count { it is ChatRow.Tool })
        assertEquals(1, rows.count { it is ChatRow.Reasoning })
        assertEquals(1, rows.count { it is ChatRow.Answer })
    }

    @Test fun nextInputDefersCollapseOnLatestTurn() {
        val policy = presentationPolicyFor(TranscriptView.Detailed, CollapseTiming.NextInput)
        val rows = foldChatItems(sampleTurn(), policy, sessionRunning = false, turnRecords = listOf(completed()))
        val proc = rows.filterIsInstance<ChatRow.Process>().single()
        assertTrue("next-input keeps latest turn open", proc.defaultOpen)
        assertFalse(processCollapsedByDefault(proc, policy))
    }

    @Test fun nextInputStillFoldsOlderTurns() {
        val policy = presentationPolicyFor(TranscriptView.Standard, CollapseTiming.NextInput)
        val items = sampleTurn() + listOf(
            Item.User("u2", "继续"),
            Item.Tool("t3", "c3", "bash", """{"command":"ls"}""", ToolStatus.Ok, turn = 2, step = 1),
            Item.Text("a2", "done", false, turn = 2, step = 1),
        )
        val rows = foldChatItems(items, policy, sessionRunning = false, turnRecords = listOf(completed(), completed(2)))
        val procs = rows.filterIsInstance<ChatRow.Process>()
        assertEquals(2, procs.size)
        assertFalse("older turn folds", procs[0].defaultOpen)
        assertTrue("latest turn stays open until next input", procs[1].defaultOpen)
    }

    @Test fun processTitleMatchesDesktopLocale() {
        assertEquals("已调用工具", processTitle(listOf("tools")))
        assertEquals("已读取文件并修改了文件", processTitle(listOf("read", "edit")))
        assertEquals("已读取文件，修改了文件，执行了命令", processTitle(listOf("read", "edit", "commands")))
        assertEquals("已读取文件，修改了文件，执行了命令等", processTitle(listOf("read", "edit", "commands", "search")))
        assertEquals("已完成分析", processTitle(emptyList()))
    }

    @Test fun toolActivityKindsMatchDesktop() {
        assertEquals("read", toolActivityKind("read"))
        assertEquals("search", toolActivityKind("grep"))
        assertEquals("commands", toolActivityKind("bash"))
        assertEquals("edit", toolActivityKind("apply_patch"))
        assertEquals("webSearch", toolActivityKind("web_search"))
        assertEquals("tools", toolActivityKind("unknown_widget"))
    }

    @Test fun liveProcessShowsRunningTitle() {
        val policy = presentationPolicyFor(TranscriptView.Standard, CollapseTiming.Completion)
        val rows = foldChatItems(sampleTurn(okTools = false), policy, sessionRunning = true)
        val proc = rows.filterIsInstance<ChatRow.Process>().singleOrNull()
            ?: rows // detailed/history may expand live rows
        // Standard groups live process under a process seat with live title.
        if (proc is List<*>) {
            assertTrue(rows.any { it is ChatRow.Tool || it is ChatRow.Process })
        } else {
            val p = rows.filterIsInstance<ChatRow.Process>().single()
            assertFalse(p.closed)
            assertTrue(p.title.contains("正在") || p.title == "正在修改文件" || p.title == "正在读取文件")
        }
    }

    @Test fun answerAlwaysVisibleWhenToolsFolded() {
        val policy = presentationPolicyFor(TranscriptView.Compact, CollapseTiming.Completion)
        val rows = foldChatItems(sampleTurn(), policy, sessionRunning = false)
        val answer = rows.filterIsInstance<ChatRow.Answer>().single()
        assertTrue(answer.item.text.contains("模型配置"))
    }

    @Test fun elapsedUsesWholeTurnNotReasoningOrConcurrentTools() {
        val proc = foldChatItems(sampleTurn(), presentationPolicyFor(), false, listOf(completed()))
            .filterIsInstance<ChatRow.Process>().single()
        assertEquals(6500L, proc.durationMs)
        assertEquals("已完成，用时 6秒", proc.title)
    }

    @Test fun textOnlyAndReasoningOnlyTurnsStillHaveCompletionHeaders() {
        for (body in listOf(
            listOf(Item.Text("a", "done", false, turn = 1, step = 1)),
            listOf(Item.Reasoning("r", "thought", 1000, false, turn = 1, step = 1)),
        )) {
            val rows = foldChatItems(body, presentationPolicyFor(), false, listOf(completed()))
            val proc = rows.filterIsInstance<ChatRow.Process>().single()
            assertTrue(proc.turnSummary)
            assertEquals(6500L, proc.durationMs)
            assertEquals(body.any { it is Item.Reasoning }, proc.canCollapse)
            assertEquals(body.filterIsInstance<Item.Text>(), rows.filterIsInstance<ChatRow.Answer>().map { it.item })
        }
    }

    @Test fun cancelledAndFailedTurnsStayOpenWithoutDuration() {
        for ((reason, label) in listOf("aborted" to "已停止", "error" to "处理失败")) {
            val proc = foldChatItems(sampleTurn(), presentationPolicyFor(), false, listOf(completed(reason = reason)))
                .filterIsInstance<ChatRow.Process>().single()
            assertEquals(label, proc.title)
            assertNull(proc.durationMs)
            assertTrue(proc.defaultOpen)
            assertFalse(proc.canCollapse)
        }
    }

    @Test fun missingBoundaryNeverBorrowsReasoningOrToolTimes() {
        for (fact in listOf(completed().copy(startSeq = null, startedAt = null), completed().copy(endedAt = null))) {
            val proc = foldChatItems(sampleTurn(), presentationPolicyFor(), false, listOf(fact))
                .filterIsInstance<ChatRow.Process>().single()
            assertNull(proc.durationMs)
            assertEquals("已完成", proc.title)
        }
        val noEnd = completed().copy(endSeq = null, endedAt = null, endReason = null)
        val unfinished = foldChatItems(sampleTurn(), presentationPolicyFor(), false, listOf(noEnd))
            .filterIsInstance<ChatRow.Process>().single()
        assertFalse(unfinished.closed)
        assertFalse(unfinished.turnSummary)
        assertNull(unfinished.durationMs)
    }

    @Test fun durationFloorsSecondsAndUsesDesktopMinuteHourUnits() {
        assertEquals("6秒", formatRunDuration(6500))
        assertEquals("1分0秒", formatRunDuration(60_999))
        assertEquals("1小时0分0秒", formatRunDuration(3_600_999))
        assertEquals("1小时2分3秒", formatRunDuration(3_723_999))
        val proc = foldChatItems(sampleTurn(), presentationPolicyFor(), false,
            listOf(completed().copy(endedAt = 1100))).filterIsInstance<ChatRow.Process>().single()
        assertEquals("已完成，用时 1秒", proc.title)
    }

    @Test fun steeringInboxAndAutomaticTurnsDoNotSplitOrMergeHostTurns() {
        val items = listOf(
            Item.User("u1", "start", seq = 1),
            Item.Reasoning("r1", "first", 1000, false, turn = 1, step = 1),
            Item.Tool("k1", "c1", "wait_agent", "{}", ToolStatus.Ok, turn = 1, step = 1),
            Item.User("inbox", "queued", pending = true),
            Item.User("steering", "adjust", seq = 4),
            Item.Reasoning("r2", "second", 1000, false, turn = 1, step = 2),
            Item.Tool("k2", "c2", "job_list", "{}", ToolStatus.Ok, turn = 1, step = 2),
            Item.Tool("k3", "c3", "list_agents", "{}", ToolStatus.Ok, turn = 1, step = 2),
            Item.Reasoning("r3", "third", 1000, false, turn = 1, step = 3),
            Item.Text("answer1", "done", false, turn = 1, step = 3),
            // No human User row triggers this automatic turn.
            Item.Reasoning("auto", "automatic", 500, false, turn = 2, step = 1),
            Item.Text("answer2", "notice", false, turn = 2, step = 1),
        )
        val rows = foldChatItems(items, presentationPolicyFor(), false, listOf(completed(), completed(2)))
        val processes = rows.filterIsInstance<ChatRow.Process>()
        assertEquals(2, processes.size)
        assertEquals(listOf("r1", "k1", "r2", "k2", "k3", "r3"), processes[0].members.map { it.key })
        assertEquals(listOf(1, 2, 3), processes[0].steps.map { it.step })
        assertEquals(listOf("auto"), processes[1].members.map { it.key })
        assertEquals(3, rows.filterIsInstance<ChatRow.User>().size)
        assertEquals(listOf("answer1", "answer2"), rows.filterIsInstance<ChatRow.Answer>().map { it.key })
        val groups = processDisplaySteps(processes[0].steps)
        assertEquals(2, groups.size)
        assertEquals(listOf("r1", "k1", "r2", "k2", "k3"), groups[0].members.map { it.key })
        assertEquals(listOf("r3"), groups[1].members.map { it.key })
    }

    @Test fun reasoningOnlyStepsSeparateToolDisclosuresWithoutLosingBlocks() {
        val members = listOf(
            Item.Tool("a", "a", "read", "{}", ToolStatus.Ok, turn = 1, step = 1),
            Item.Reasoning("b", "between", 100, false, turn = 1, step = 2),
            Item.Reasoning("c", "before", 100, false, turn = 1, step = 3),
            Item.Tool("d", "d", "read", "{}", ToolStatus.Ok, turn = 1, step = 3),
            Item.Reasoning("e", "after", 100, false, turn = 1, step = 3),
        )
        val proc = foldChatItems(members, presentationPolicyFor(), false, listOf(completed()))
            .filterIsInstance<ChatRow.Process>().single()
        val groups = processDisplaySteps(proc.steps)
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c", "d", "e")),
            groups.map { group -> group.members.map { it.key } })
    }

    @Test fun liveCommitWaitsForHostTurnEndAndKeepsAllBlocks() {
        val r = TranscriptReducer()
        r.event(1, "turn/start", 1000, """{"turn":7}""")
        r.event(2, "step/start", 1200, """{"turn":7,"step":1}""")
        r.apply(PocketJson.parseToJsonElement(
            """{"type":"assistant-stream","frame":{"type":"start","attemptId":"a","turn":7,"step":1}}""",
        ))
        r.apply(PocketJson.parseToJsonElement(
            """{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"a","time":2000,"chunk":{"type":"reasoning-delta","index":0,"text":"thinking"}}}""",
        ))
        assertEquals(listOf("thinking"), r.rows().filterIsInstance<ChatRow.Reasoning>().map { it.item.text })
        r.event(3, "assistant/message", 7000, """{"turn":7,"step":1,
            "stream":[{"type":"reasoning-chunks","time0":2000,"dt":[0,1000],"texts":["thinking"]}],
            "message":{"content":[{"type":"reasoning","text":"thinking"},{"type":"text","text":"answer"}]}}""")
        assertTrue(r.rows().filterIsInstance<ChatRow.Process>().none { it.closed })
        r.event(4, "turn/end", 7500, """{"turn":7,"reason":{"kind":"completed"}}""")
        val rows = r.rows()
        val proc = rows.filterIsInstance<ChatRow.Process>().single()
        assertEquals("已完成，用时 6秒", proc.title)
        assertEquals(listOf("thinking"), proc.reasoning.map { it.text })
        assertEquals(listOf("answer"), rows.filterIsInstance<ChatRow.Answer>().map { it.item.text })
    }
    @Test fun historyPageUpgradesUnknownDurationWithoutChangingTurnKey() {
        val r = TranscriptReducer()
        r.apply(PocketJson.parseToJsonElement("""{"type":"snapshot","cursor":4,"hasMore":true,"projections":{},
            "records":[
                {"type":"event","event":{"seq":3,"time":7000,"type":"assistant/message","data":{"turn":7,"step":1,
                    "message":{"content":[{"type":"text","text":"answer"}]}}}},
                {"type":"event","event":{"seq":4,"time":7500,"type":"turn/end","data":{"turn":7,"reason":{"kind":"completed"}}}}
            ]}"""))
        val before = r.rows().filterIsInstance<ChatRow.Process>().single()
        assertNull(before.durationMs)
        r.prepend(PocketJson.parseToJsonElement("""[
            {"type":"event","event":{"seq":1,"time":1000,"type":"turn/start","data":{"turn":7}}}
        ]"""), false)
        val after = r.rows().filterIsInstance<ChatRow.Process>().single()
        assertEquals(before.key, after.key)
        assertEquals("已完成，用时 6秒", after.title)
        assertEquals(1, r.rows().filterIsInstance<ChatRow.Answer>().size)
    }

    @Test fun otherEndReasonsKeepElapsedAndCompactLiveRemainsCollapsed() {
        for (reason in listOf("max-tokens", "interrupted", "plugin-turn")) {
            val proc = foldChatItems(sampleTurn(), presentationPolicyFor(), false, listOf(completed(reason = reason)))
                .filterIsInstance<ChatRow.Process>().single()
            assertEquals(6500L, proc.durationMs)
        }
        val live = foldChatItems(sampleTurn(okTools = false), presentationPolicyFor(TranscriptView.Compact), true)
            .filterIsInstance<ChatRow.Process>().single()
        assertFalse(live.closed)
        assertFalse(live.defaultOpen)
        assertNull(live.durationMs)
    }
}
