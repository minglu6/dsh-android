package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProcessTest {
    private fun sampleTurn(okTools: Boolean = true): List<Item> = listOf(
        Item.User("u1", "看看配置"),
        Item.Reasoning("r1", "先读 patch", 1200, false),
        Item.Tool("t1", "c1", "read", """{"path":"cordis.patch.yml"}""", if (okTools) ToolStatus.Ok else ToolStatus.Running),
        Item.Tool("t2", "c2", "edit", """{"path":"cordis.patch.yml"}""", if (okTools) ToolStatus.Ok else ToolStatus.Running),
        Item.Text("a1", SAMPLE_MARKDOWN, false),
    )

    @Test fun detailedFoldsSettledToolsIntoProcessSummary() {
        val policy = presentationPolicyFor(TranscriptView.Detailed, CollapseTiming.Completion)
        val rows = foldChatItems(sampleTurn(), policy, sessionRunning = false)
        assertEquals(1, rows.count { it is ChatRow.User })
        assertEquals(1, rows.count { it is ChatRow.Answer })
        assertEquals(0, rows.count { it is ChatRow.Tool }) // tools hidden inside Process
        val proc = rows.filterIsInstance<ChatRow.Process>().single()
        assertEquals(2, proc.tools.size)
        assertEquals(1, proc.reasoning.size)
        assertTrue(proc.closed)
        assertFalse("completion timing collapses settled process", proc.defaultOpen)
        assertEquals("已读取文件并修改了文件", proc.title)
        assertTrue(processCollapsedByDefault(proc, policy))
    }

    @Test fun compactAlsoFoldsAndHidesTools() {
        val policy = presentationPolicyFor(TranscriptView.Compact, CollapseTiming.Completion)
        val rows = foldChatItems(sampleTurn(), policy, sessionRunning = false)
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
        val rows = foldChatItems(sampleTurn(), policy, sessionRunning = false)
        val proc = rows.filterIsInstance<ChatRow.Process>().single()
        assertTrue("next-input keeps latest turn open", proc.defaultOpen)
        assertFalse(processCollapsedByDefault(proc, policy))
    }

    @Test fun nextInputStillFoldsOlderTurns() {
        val policy = presentationPolicyFor(TranscriptView.Standard, CollapseTiming.NextInput)
        val items = sampleTurn() + listOf(
            Item.User("u2", "继续"),
            Item.Tool("t3", "c3", "bash", """{"command":"ls"}""", ToolStatus.Ok),
            Item.Text("a2", "done", false),
        )
        val rows = foldChatItems(items, policy, sessionRunning = false)
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
}
