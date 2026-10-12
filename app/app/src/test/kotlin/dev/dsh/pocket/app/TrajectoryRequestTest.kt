package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.CompactionStatus
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.StepRecord
import dev.dsh.pocket.protocol.StepUsage
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TranscriptReducer
import dev.dsh.pocket.protocol.TrajectoryRecordState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Request inspector numbering + tabs — desktop TrajectoryView `requestNumbers`
 * / REQUEST_TABS (`deepseek-harness` @ d743267).
 */
class TrajectoryRequestTest {
    private fun j(s: String) = PocketJson.parseToJsonElement(s)
    private fun ev(seq: Int, type: String, data: String, time: Long = 1000L + seq) =
        j("""{"type":"event","event":{"type":"$type","seq":$seq,"time":$time,"data":$data}}""")

    @Test fun numbersAssistantAndCompactionInSeqOrder() {
        val items = listOf(
            Item.User("u1", "hi"),
            Item.Compacted("cmp1", "c1", CompactionStatus.Ok, summary = "kept", startedAt = 50, endedAt = 90),
            Item.Text("a1", "hello", false),
            Item.Tool("t1", "c1", "read", "{}", ToolStatus.Ok, turn = 1, step = 1),
            Item.Tool("t2", "c1:ptc:1", "bash", "{}", ToolStatus.Ok, turn = 1, step = 1, parentCallId = "c1"),
        )
        val steps = listOf(
            StepRecord(
                seq = 10, turn = 1, step = 1, stepStartTime = 100, messageTime = 200,
                replyText = "hello", reasoningText = "", usage = StepUsage(inputTokens = 10, outputTokens = 2),
                provider = "deepseek", model = "flash", toolCallIds = listOf("c1", "c1:ptc:1"),
                requestConfigJson = """{"provider":"deepseek","model":"flash"}""",
            ),
        )
        val requests = buildTrajectoryRequests(items, steps)
        assertEquals(2, requests.size)
        assertEquals(TrajectoryRequest.Purpose.Compaction, requests[0].purpose)
        assertEquals(1, requests[0].number)
        assertEquals(TrajectoryRequest.Purpose.Assistant, requests[1].purpose)
        assertEquals(2, requests[1].number)
        assertEquals(1, requests[1].toolCallCount)
        assertEquals(1, requests[1].subtoolCallCount)
        assertEquals(StepUsage(inputTokens = 10, outputTokens = 2), requests[1].usage)
        assertEquals(StepUsage(inputTokens = 10, outputTokens = 2), requests[1].cumulativeUsage)
        assertEquals(
            listOf(TrajRequestTab.Overview, TrajRequestTab.Options, TrajRequestTab.Usage, TrajRequestTab.Timing),
            trajRequestTabs(requests[1]),
        )
        assertEquals(
            listOf(TrajRequestTab.Overview, TrajRequestTab.Usage, TrajRequestTab.Timing),
            trajRequestTabs(requests[0]),
        )
    }

    @Test fun boundaryChipAnchorsOnAssistantResultRow() {
        val steps = listOf(
            StepRecord(
                seq = 10, turn = 1, step = 1, stepStartTime = 100, messageTime = 200,
                replyText = "ok", reasoningText = "", usage = StepUsage(),
                provider = null, model = null, toolCallIds = emptyList(),
            ),
        )
        val turns = buildTrajectory(listOf(Item.User("u", "hi"), Item.Text("a10", "ok", false)), steps)
        val requests = buildTrajectoryRequests(listOf(Item.User("u", "hi"), Item.Text("a10", "ok", false)), steps)
        val req = requests.single()
        assertEquals("a10", requestBoundaryRowKey(req, turns))
        assertEquals(req, requestForRow(turns[0].rows.single { it.kind == TrajL.kindAssistant }, requests))
    }

    @Test fun requestHeaderAndRetryStampOntoStepRecord() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":0,"records":[],"hasMore":false,"projections":{}}"""))
        r.apply(ev(1, "turn/start", """{"turn":1}"""))
        r.apply(ev(2, "step/start", """{"turn":1,"step":1}"""))
        r.apply(
            ev(
                3,
                "request/header",
                """{"header":{"config":{"provider":"deepseek","model":"flash","temperature":0.2},
                "tools":[{"name":"read","description":"R","parameters":{"type":"object"}}]}}""",
            ),
        )
        r.apply(
            ev(
                4,
                "llm/retry",
                """{"retry":1,"maxRetries":3,"delayMs":500,"error":{"message":"rate limited"}}""",
            ),
        )
        r.apply(
            ev(
                5,
                "assistant/message",
                """{"turn":1,"step":1,"stream":[],"usage":{"inputTokens":11,"outputTokens":3},
                "message":{"content":[{"type":"text","text":"hi"}]}}""",
            ),
        )
        val step = r.stepRecords().single()
        assertEquals("deepseek", step.provider)
        assertEquals("flash", step.model)
        assertNotNull(step.requestConfigJson)
        assertTrue(step.requestConfigJson!!.contains("temperature"))
        assertEquals(1, step.retry)
        assertEquals(3, step.maxRetries)
        assertEquals(500L, step.retryDelayMs)
        assertEquals("rate limited", step.lastAttemptError)
        val header = r.requestHeaders().single()
        assertEquals("deepseek", header.provider)
        assertEquals("flash", header.model)
        val req = buildTrajectoryRequests(r.items(), r.stepRecords()).single()
        assertEquals(1, req.retry)
        assertEquals(3, req.maxRetries)
        assertEquals(TrajectoryRecordState.Complete, req.status)
        assertTrue(req.requestConfigJson!!.contains("temperature"))
    }

    @Test fun prependMergesOlderRequestHeaders() {
        val r = TranscriptReducer()
        r.apply(j("""{"type":"snapshot","cursor":4,"hasMore":true,"records":[
            {"type":"event","event":{"type":"request/header","seq":4,"time":4,"data":{"header":{"config":{"provider":"new","model":"m2"},"tools":[]}}}}
        ],"projections":{}}"""))
        assertEquals(1, r.requestHeaders().size)
        assertEquals("new", r.requestHeaders().single().provider)
        val older = j("""[
            {"type":"event","event":{"type":"request/header","seq":2,"time":2,"data":{"header":{"config":{"provider":"old","model":"m1"},"tools":[]}}}}
        ]""")
        assertTrue(r.prepend(older, pageHasMore = false))
        assertEquals(listOf("old", "new"), r.requestHeaders().map { it.provider })
        assertFalse(r.hasMore)
    }

    @Test fun localesMatchDesktopRequestAndHistory() {
        assertEquals("请求 #3", TrajL.requestLabel(3))
        assertEquals("请求 #2 · 压缩", TrajL.requestLabelCompaction(2))
        assertEquals("加载更早的历史", TrajL.historyLoadEarlier)
        assertEquals("正在加载更早的历史…", TrajL.historyLoadingEarlier)
        assertEquals("选项", TrajL.tabOptions)
        assertEquals("用量", TrajL.tabUsage)
        assertEquals("结果", TrajL.detailsResult)
        assertEquals("已压缩", TrajL.detailsCompacted)
    }
}
