package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals

class ToolProcessSummaryTest {
    private fun tool(name: String, result: String?, args: String = "{}", status: ToolStatus = ToolStatus.Ok) =
        Item.Tool("tool-1", "call-1", name, args, status, result = result)

    @Test fun waitReceiptDistinguishesNoProgressTimeoutAndChange() {
        val noProgress = tool("wait_agent", """{"timedOut":false,"noProgress":{"message":"No running children"}}""", """{"timeout_ms":120000}""")
        assertEquals("没有正在运行的子智能体", toolProcessSummary(noProgress).summary)
        assertEquals("子智能体状态 · 等待超时", toolProcessSummary(noProgress.copy(result = """{"timedOut":true}""")).summary)
        assertEquals("子智能体状态 · 检测到变化", toolProcessSummary(noProgress.copy(result = """{"timedOut":false}""")).summary)
    }

    @Test fun malformedOrFailedReceiptsNeverClaimAnEmptySuccessfulList() {
        assertNull(toolProcessSummary(tool("job_list", "malformed output")).summary)
        assertNull(toolProcessSummary(tool("wait_agent", """{"noProgress":{"message":"bad shape"}}""")).summary)
        val failed = tool("job_list", "(no background jobs)", status = ToolStatus.Error).copy(error = "permission denied")
        assertEquals("permission denied", toolProcessSummary(failed).summary)
        assertNotEquals("0 个后台任务", toolProcessSummary(failed).summary)
    }

    @Test fun countsOnlyRecognizedDurableAgentAndJobOutputs() {
        assertEquals("0 个后台任务", toolProcessSummary(tool("job_list", "(no background jobs)")).summary)
        assertEquals("2 个后台任务", toolProcessSummary(tool("job_list", "one [bash] running — npm test\ntwo [subagent] completed — inspect UI")).summary)
        assertEquals("1 个智能体", toolProcessSummary(tool("list_agents", """[{"id":"a1","status":"idle"}]""")).summary)
        assertEquals("2 个智能体", toolProcessSummary(tool("list_agents", "a1 [idle] parent=root depth=1 — Worker\na2 [running] — Scout")).summary)
        assertNull(toolProcessSummary(tool("job_list", "one [bash] running — npm test\nnot a job")).summary)
    }

    @Test fun runningRowsDoNotTreatResidualOutputAsCompletedReceipts() {
        val running = tool("job_list", "(no background jobs)", status = ToolStatus.Running)
        assertNull(toolProcessSummary(running).summary)
        assertEquals("0 个后台任务", toolProcessSummary(running.copy(status = ToolStatus.Ok)).summary)
    }

    @Test fun genericArgumentPreferenceAndUnknownNamesRemainInspectable() {
        assertEquals("Run tests", toolProcessSummary(tool("bash", null, """{"command":"npm test","description":"Run tests"}""", ToolStatus.Running)).summary)
        assertEquals("first, second", toolProcessSummary(tool("web_search", null, """{"queries":["first\nextra","second"]}""", ToolStatus.Running)).summary)
        val custom = toolProcessSummary(tool("vendor.job_list", "(no background jobs)", """{"scope":"current"}"""))
        assertEquals("vendor.job_list · current", custom.summary)
        assertEquals(ToolProcessIcon.Generic, custom.icon)
    }
}
