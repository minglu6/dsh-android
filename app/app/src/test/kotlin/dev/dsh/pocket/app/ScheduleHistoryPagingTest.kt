package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ScheduleHistoryPagingTest {
    @get:Rule val rule = createComposeRule()
    private val clientField = Pocket.javaClass.getDeclaredField("client").apply { isAccessible = true }
    private lateinit var client: PocketClient
    private var previousClient: Any? = null
    private var previousOps = emptySet<String>()
    private var previousActive: String? = null
    private var previousStatus = emptyMap<String, Dot>()
    private val requests = CopyOnWriteArrayList<JsonElement>()
    private val task = mutableStateOf("task-a")
    private val session = mutableStateOf("session-a")
    private val online = mutableStateOf(true)
    private val latest = mutableStateOf<String?>(null)

    @Before fun setup() {
        installTestIdlePolicy()
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        previousClient = clientField.get(Pocket)
        previousOps = Pocket.ops.value
        previousActive = Pocket.active.value
        previousStatus = Pocket.status.value
        Pocket.ops.value = setOf("schedule.history")
        Pocket.active.value = "schedule-history-test"
        Pocket.status.value = mapOf("schedule-history-test" to Dot.Online)
        client = PocketClient()
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) { requests += PocketJson.parseToJsonElement(text) }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        clientField.set(Pocket, client)
    }

    @After fun cleanup() {
        client.close()
        clientField.set(Pocket, previousClient)
        Pocket.ops.value = previousOps
        Pocket.active.value = previousActive
        Pocket.status.value = previousStatus
    }

    private fun mount() {
        rule.setContent { PocketTheme {
            ScheduleHistoryScreen(session.value, task.value, "UTC", latest.value, online.value)
        } }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("schedule.history", requests.first()["op"].str()) }
    }

    private fun frame(value: JsonObject) {
        client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
            .invoke(client, value.toString())
    }

    private fun complete(index: Int, ids: List<String>, next: String? = null, pruned: Boolean = false) {
        rule.runOnIdle {
            frame(jsonOf("id" to requests[index]["id"], "ok" to true, "result" to jsonOf(
                "records" to JsonArray(ids.map { id -> jsonOf("messageId" to id,
                    "scheduledAt" to "2026-10-21T00:00:00Z", "deliveredAt" to "2026-10-21T00:00:01Z", "prompt" to "record-$id") }),
                "nextBefore" to next, "earlierRecordsPruned" to pruned,
                "retention" to jsonOf("days" to 30, "records" to 100),
            )))
        }
        rule.waitForIdle()
    }

    private fun fail(index: Int) {
        rule.runOnIdle {
            frame(jsonOf("id" to requests[index]["id"], "ok" to false,
                "error" to jsonOf("code" to "history_failed", "message" to "history unavailable")))
        }
        rule.waitForIdle()
    }

    private fun count(expected: Int) = rule.runOnIdle { assertEquals(expected, requests.size) }
    private fun bottom(recordCount: Int) = rule.onNodeWithTag("scheduleHistory").performScrollToIndex(recordCount + 1)
    private fun pull() {
        rule.onNodeWithTag("scheduleHistory").performScrollToIndex(0)
        rule.onNodeWithTag("scheduleHistoryRefresh").performTouchInput { swipeDown(durationMillis = 700) }
        rule.waitForIdle()
    }

    @Test fun bottomLoadsOfficialMessageCursorAndKeepsDeduplicatedRecords() {
        mount()
        complete(0, (0..19).map { "m$it" }, "m19")
        count(1)
        rule.onNodeWithText("刷新").assertDoesNotExist()
        rule.onNodeWithText("加载更多").assertDoesNotExist()
        bottom(20)
        count(2)
        rule.runOnIdle {
            assertEquals("session-a", requests[1]["args"]["sessionId"].str())
            assertEquals("task-a", requests[1]["args"]["id"].str())
            assertEquals("m19", requests[1]["args"]["before"].str())
            assertEquals(20, requests[1]["args"]["limit"].int())
        }
        complete(1, listOf("m19", "m20"))
        rule.onAllNodesWithText("record-m19").assertCountEquals(1)
        rule.onNodeWithText("record-m19").assertIsDisplayed()
        rule.onNodeWithTag("scheduleHistory").performScrollToIndex(21)
        rule.onNodeWithText("record-m20").assertIsDisplayed()
        rule.onNodeWithText("record-m0").assertDoesNotExist()
        bottom(21)
        rule.onNodeWithTag("scheduleHistory").performTouchInput { swipeUp() }
        count(2)
        rule.onNodeWithTag("scheduleHistory").performScrollToIndex(1)
        rule.onNodeWithText("record-m0").assertIsDisplayed()
    }

    @Test fun shortPagesFillViewportUntilTerminalPageAndPreserveRetentionRules() {
        mount()
        complete(0, listOf("m0"), "m0")
        count(2)
        complete(1, listOf("m1"), "m1")
        count(3)
        complete(2, listOf("m2"), pruned = true)
        rule.onNodeWithText("record-m0").assertIsDisplayed()
        rule.onNodeWithText("record-m2").assertIsDisplayed()
        rule.onNodeWithText("更早的运行记录已清理").assertIsDisplayed()
        rule.onNodeWithText("保留规则").performClick()
        rule.onNodeWithText("每个任务最多保留最近 30 天的 100 条运行记录。保存新记录时会自动清理超出限制的记录，清理不会停止任务。").assertExists()
        rule.onNodeWithTag("scheduleHistory").performTouchInput { swipeUp() }
        count(3)
    }

    @Test fun pullRefreshReadsFirstPageWithoutDiscardingRecordsWhilePending() {
        mount()
        complete(0, listOf("old"))
        pull()
        count(2)
        rule.runOnIdle { assertNull(requests[1]["args"]["before"].str()) }
        rule.onNodeWithText("record-old").assertIsDisplayed()
        complete(1, listOf("new"))
        rule.onNodeWithText("record-new").assertIsDisplayed()
        rule.onNodeWithText("record-old").assertDoesNotExist()
        count(2)
    }

    @Test fun repeatedCursorStopsAndExplicitRetryCanRecoverWithoutLosingRecords() {
        mount()
        complete(0, listOf("m0"), "m0")
        count(2)
        complete(1, listOf("m0"), "m0")
        count(2)
        rule.onNodeWithText("运行记录分页未取得进展，请重试或下拉刷新。").assertIsDisplayed()
        rule.runOnIdle { Pocket.ops.value += "models.providers" }
        count(2)
        rule.onNodeWithText("重试").performClick()
        count(3)
        complete(2, listOf("m1"))
        rule.onNodeWithText("record-m0").assertIsDisplayed()
        rule.onNodeWithText("record-m1").assertIsDisplayed()
        count(3)
    }

    @Test fun failedPageDoesNotRetryFromRecompositionButNewBoundaryGestureRetriesSameCursor() {
        mount()
        complete(0, (0..19).map { "m$it" }, "m19")
        bottom(20)
        count(2)
        fail(1)
        rule.onNodeWithText("history_failed: history unavailable", substring = true).assertExists()
        rule.runOnIdle { Pocket.ops.value += "models.providers" }
        count(2)
        rule.onNodeWithTag("scheduleHistory").performTouchInput { swipeUp(durationMillis = 700) }
        count(3)
        rule.runOnIdle { assertEquals("m19", requests[2]["args"]["before"].str()) }
        complete(2, listOf("m20"))
        bottom(21)
        count(3)
    }

    @Test fun failedPageCanBeReplacedByPullRefreshAndEmptyProgressCannotSpin() {
        mount()
        complete(0, listOf("m0"), "m0")
        complete(1, emptyList(), "different-but-empty")
        count(2)
        pull()
        count(3)
        rule.runOnIdle { assertNull(requests[2]["args"]["before"].str()) }
        complete(2, emptyList())
        rule.onNodeWithText("暂无任务运行记录").assertIsDisplayed()
        count(3)
    }

    @Test fun switchingTaskRejectsLateReceiptAndResetsPagingAndScrollState() {
        mount()
        rule.runOnIdle { task.value = "task-b"; session.value = "session-b" }
        rule.waitForIdle()
        count(2)
        complete(0, listOf("stale"), "stale-cursor")
        rule.onNodeWithText("record-stale").assertDoesNotExist()
        count(2)
        rule.runOnIdle {
            assertEquals("task-b", requests[1]["args"]["id"].str())
            assertEquals("session-b", requests[1]["args"]["sessionId"].str())
            assertNull(requests[1]["args"]["before"].str())
        }
        complete(1, listOf("current"))
        rule.onNodeWithText("record-current").assertIsDisplayed()
        rule.onNodeWithText("record-stale").assertDoesNotExist()
    }

    @Test fun latestReceiptSupersedesPendingRefreshAndReconnectReloadsFirstPage() {
        mount()
        rule.runOnIdle { latest.value = "delivery-new" }
        rule.waitForIdle()
        count(2)
        complete(0, listOf("stale"))
        rule.onNodeWithText("record-stale").assertDoesNotExist()
        complete(1, listOf("current"))
        rule.runOnIdle { online.value = false }
        rule.waitForIdle()
        count(2)
        rule.onNodeWithText("record-current").assertIsDisplayed()
        rule.runOnIdle { online.value = true }
        rule.waitForIdle()
        count(3)
        rule.runOnIdle { assertNull(requests[2]["args"]["before"].str()) }
        complete(2, listOf("reconnected"))
        rule.onNodeWithText("record-reconnected").assertIsDisplayed()
    }

    @Test fun detailsAndEditorKeepOneScrollableViewportWhenSwitchingToHistory() {
        Pocket.ops.value = setOf("schedule.catalog", "schedule.history", "schedule.update", "schedule.delete")
        rule.setContent { PocketTheme { ScheduleSettingsScreen({}, {}, {}) } }
        rule.waitForIdle()
        val row = jsonOf("id" to "task-a", "sessionId" to "session-a", "status" to "active",
            "kind" to "daily", "time" to "09:00", "timeZone" to "UTC", "title" to "Paging task",
            "prompt" to "Daily reminder", "scheduledAt" to "2027-01-01T09:00:00Z")
        rule.runOnIdle {
            assertEquals("schedule.catalog", requests.single()["op"].str())
            frame(jsonOf("id" to requests[0]["id"], "ok" to true, "result" to JsonArray(listOf(row))))
        }
        rule.onNodeWithText("Paging task").performClick()
        rule.onNodeWithText("删除任务").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("编辑").performScrollTo().performClick()
        rule.onNodeWithText("保存修改").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("任务运行记录").performClick()
        rule.waitForIdle()
        count(2)
        complete(1, (0..19).map { "m$it" }, "m19")
        bottom(20)
        count(3)
        complete(2, listOf("m20"))
        rule.onNodeWithText("record-m19").assertIsDisplayed()
        rule.onNodeWithTag("scheduleHistory").performScrollToIndex(21)
        rule.onNodeWithText("record-m20").assertIsDisplayed()
        rule.onNodeWithText("规则").performClick()
        rule.onNodeWithText("删除任务").performScrollTo().assertIsDisplayed()
        count(3)
    }
}
