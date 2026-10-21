package dev.dsh.pocket.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.takahirom.roborazzi.captureRoboImage
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class JobsActionTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var client: PocketClient
    private var originalClient: Any? = null
    private val clientField = Pocket.javaClass.getDeclaredField("client").apply { isAccessible = true }
    private val requests = LinkedBlockingQueue<JsonObject>()
    private var host by mutableStateOf("host-A")
    private var session by mutableStateOf("session-A")
    private var available by mutableStateOf(true)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.appearance = Appearance.Light
        Pocket.prefs.language = AppLanguage.Zh
        originalClient = clientField.get(Pocket)
        client = PocketClient()
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) { requests.add(PocketJson.parseToJsonElement(text).jsonObject) }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        clientField.set(Pocket, client)
        owner.registry.currentState = Lifecycle.State.RESUMED
        rule.mainClock.autoAdvance = true
    }

    @After fun cleanup() {
        // Resume-autoAdvance after a pause pumps JobsAction's delay() (and any
        // leaked frame awaiters) forever; keep the clock paused through teardown.
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED }
        } finally {
            client.close()
            clientField.set(Pocket, originalClient)
        }
    }

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PocketTheme {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        JobsAction(host, session, available)
                    }
                }
            }
        }
    }

    private fun request(op: String = "jobs.list"): JsonObject {
        rule.waitUntil(5000) { requests.isNotEmpty() }
        return requests.remove().also {
            assertEquals(op, it["op"].str())
            if (op != "unfollow") assertEquals(session, it["args"]["sessionId"].str())
        }
    }

    private fun reply(request: JsonObject, statuses: List<String> = emptyList(), error: Boolean = false) {
        val rows = statuses.mapIndexed { i, status -> job("job-$i", status) }
        val frame = if (error) jsonOf("id" to request["id"], "ok" to false,
            "error" to jsonOf("code" to "internal", "message" to "roster unavailable"))
        else jsonOf("id" to request["id"], "ok" to true, "result" to jsonOf("jobs" to JsonArray(rows)))
        rule.runOnIdle {
            client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
                .invoke(client, frame.toString())
        }
        rule.waitForIdle()
    }

    private fun job(id: String, status: String, output: Long = 0, start: Long = 1_000, finish: Long? = null) = jsonOf(
        "id" to id, "kind" to "bash", "label" to "command-$id", "status" to status,
        "startedAt" to start, "finishedAt" to finish, "output" to jsonOf("total" to output, "earliest" to 0),
        "detail" to if (status == "completed") "exit code: 0" else null,
    )

    private fun result(request: JsonObject, value: JsonObject = jsonOf("stopped" to true), ok: Boolean = true) {
        val frame = if (ok) jsonOf("id" to request["id"], "ok" to true, "result" to value)
        else jsonOf("id" to request["id"], "ok" to false, "error" to jsonOf("code" to "internal", "message" to "rejected"))
        rule.runOnIdle {
            client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }.invoke(client, frame.toString())
        }
        rule.waitForIdle()
    }

    private fun roster(request: JsonObject, vararg rows: JsonObject) = result(request, jsonOf("jobs" to JsonArray(rows.toList())))

    private fun push(subId: String, frame: JsonObject? = null, error: String? = null) {
        @Suppress("UNCHECKED_CAST")
        val events = client.javaClass.getDeclaredField("_events").apply { isAccessible = true }.get(client) as MutableSharedFlow<JsonObject>
        val event = if (error == null) jsonOf("ev" to "job", "subId" to subId, "frame" to frame)
        else jsonOf("ev" to "job.error", "subId" to subId, "error" to jsonOf("code" to "internal", "message" to error))
        rule.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { events.emit(event) } }
        rule.waitForIdle()
    }

    private fun refresh(statuses: List<String> = emptyList(), error: Boolean = false) {
        rule.mainClock.advanceTimeBy(3100)
        reply(request(), statuses, error)
    }

    @Test fun emptyRosterHidesEntryAndLiveCountExcludesSettledJobs() {
        show()
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        reply(request())
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/screenshots/jobs-empty.png")

        refresh(listOf("running", "stopping", "completed", "failed", "killed"))
        rule.onNodeWithText("2 个后台任务运行中").assertIsDisplayed().performClick()
        rule.onNodeWithTag("jobsPopover").assertIsDisplayed()
        rule.onNodeWithTag("jobsPopover").captureRoboImage("build/screenshots/jobs-running.png")
        refresh(listOf("completed", "failed", "killed"))
        rule.onNodeWithText("3 个后台任务").assertIsDisplayed()
        rule.onNodeWithText("2 个后台任务运行中").assertDoesNotExist()
        refresh()
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
    }

    @Test fun sessionHostAndConnectionChangesDiscardPreviousCountsAndLateReplies() {
        show()
        reply(request(), listOf("running"))
        rule.onNodeWithText("1 个后台任务运行中").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(3100)
        val late = request()
        rule.runOnIdle { session = "session-B" }
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        reply(late, listOf("running", "running"))
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        reply(request(), listOf("completed"))
        rule.onNodeWithText("1 个后台任务").assertIsDisplayed()
        rule.runOnIdle { host = "host-B" }
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        reply(request(), listOf("running"))
        // Pause the clock so disabling availability cannot be auto-advanced into
        // the next poll. Do not turn autoAdvance back on: that pumps delay()
        // (and any leaked frame awaiters) until Espresso's idle timeout.
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread {
            available = false
            Snapshot.sendApplyNotifications()
        }
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        requests.clear()
        rule.mainClock.advanceTimeBy(6500)
        assertTrue(requests.isEmpty())
        rule.runOnUiThread {
            available = true
            Snapshot.sendApplyNotifications()
        }
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        reply(request())
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
    }

    @Test fun failedRefreshDoesNotKeepRunningStatusAndBackgroundingStopsPolling() {
        show()
        reply(request(), listOf("running"))
        refresh(error = true)
        rule.onNodeWithText("任务状态不可用").assertIsDisplayed()
        rule.onNodeWithText("1 个后台任务运行中").assertDoesNotExist()
        refresh()
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.mainClock.advanceTimeBy(6500)
        assertTrue(requests.isEmpty())
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        reply(request(), listOf("running"))
        rule.onNodeWithText("1 个后台任务运行中").assertIsDisplayed()
    }
    @Test fun groupedPopoverDefaultsAndClearPreserveLiveAndNewSettlements() {
        show()
        val live = job("live", "running", start = 5000)
        val done = job("old", "completed", finish = 4000)
        val done2 = job("newer", "completed", start = 2000, finish = 6000)
        roster(request(), done, live, done2)
        rule.onNodeWithTag("jobsAction").performClick()
        rule.onNodeWithTag("jobsPopover").assertIsDisplayed()
        rule.onNodeWithText("进行中").assertIsDisplayed()
        rule.onNodeWithText("已结束 2").assertIsDisplayed()
        rule.onNodeWithTag("jobRow:old").assertDoesNotExist()
        rule.onNodeWithTag("jobRow:live").assertIsDisplayed()
        assertTrue(requests.isEmpty()) // opening uses the header's roster
        rule.onNodeWithTag("jobsSettledToggle").performClick()
        rule.onNodeWithTag("jobRow:old").assertIsDisplayed()
        rule.onNodeWithTag("jobRow:newer").assertIsDisplayed()
        rule.onNodeWithTag("jobsClear").performClick()
        rule.onNodeWithTag("jobRow:live").assertIsDisplayed()
        rule.onNodeWithTag("jobRow:old").assertDoesNotExist()
        rule.onNodeWithText("1 个后台任务运行中").assertIsDisplayed()
        assertTrue(requests.isEmpty()) // clear must not send kill, remove, or another list request
        rule.mainClock.advanceTimeBy(3100)
        roster(request(), done, done2, job("live", "completed", start = 5000, finish = 9000))
        rule.onNodeWithText("1 个后台任务").assertIsDisplayed()
        rule.onNodeWithTag("jobRow:live").assertIsDisplayed()
        rule.onNodeWithTag("jobRow:old").assertDoesNotExist()
        rule.onNodeWithTag("jobsClear").performClick()
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        rule.onNodeWithTag("jobsPopover").assertDoesNotExist()
        assertTrue(requests.isEmpty())
        rule.mainClock.advanceTimeBy(3100)
        roster(request(), done, done2, job("live", "completed", finish = 9000), job("latest", "completed", finish = 10000))
        rule.onNodeWithText("1 个后台任务").assertIsDisplayed().performClick()
        rule.onNodeWithTag("jobRow:latest").assertIsDisplayed()
        rule.onNodeWithTag("jobRow:live").assertDoesNotExist()
    }

    @Test fun finishedOnlyListStartsOpenAndFoldingDoesNotFetchAnotherRoster() {
        show()
        roster(request(), job("a", "completed", finish = 5000), job("b", "failed", finish = 6000))
        rule.onNodeWithTag("jobsAction").performClick()
        rule.onNodeWithTag("jobRow:a").assertIsDisplayed()
        rule.onNodeWithTag("jobRow:b").assertIsDisplayed()
        rule.onNodeWithTag("jobStop:a").assertDoesNotExist()
        rule.onNodeWithTag("jobStop:b").assertDoesNotExist()
        rule.onNodeWithTag("jobsPopover").captureRoboImage("build/screenshots/jobs-finished.png")
        rule.onNodeWithTag("jobsSettledToggle").performClick()
        rule.onNodeWithTag("jobRow:a").assertDoesNotExist()
        rule.onNodeWithTag("jobsSettledToggle").performClick()
        rule.onNodeWithTag("jobRow:a").assertIsDisplayed()
        assertTrue(requests.isEmpty())
    }

    @Test fun expandedOutputFollowsCopiesAndUnsubscribesOnFoldAndSessionChange() {
        show()
        val done = job("done", "completed", output = 20, finish = 5000)
        roster(request(), done)
        rule.onNodeWithTag("jobsAction").performClick()
        rule.onNodeWithTag("jobRow:done").performClick()
        val follow = request("jobs.follow")
        assertEquals("done", follow["args"]["jobId"].str())
        result(follow, jsonOf("subId" to "sub-old"))
        push("sub-old", jsonOf("type" to "opened", "job" to done))
        push("sub-old", jsonOf("type" to "output", "lossy" to true, "chunks" to JsonArray(listOf(
            jsonOf("text" to "first\n"), jsonOf("text" to "second", "gapBefore" to true),
        ))))
        rule.onNodeWithText("first", substring = true).assertIsDisplayed()
        rule.onNodeWithText("部分历史输出已过期", substring = true).assertIsDisplayed()
        rule.onNodeWithText("此处输出存在缺失", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("jobCopy:done").performClick()
        rule.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("command-done", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        rule.onNodeWithTag("jobsSettledToggle").performClick()
        val unfollow = request("unfollow")
        assertEquals("sub-old", unfollow["args"]["subId"].str())
        result(unfollow)
        rule.onNodeWithTag("jobOutput:done").assertDoesNotExist()
        rule.onNodeWithTag("jobsSettledToggle").performClick()
        result(request("jobs.follow"), jsonOf("subId" to "sub-second"))
        push("sub-second", error = "stream disconnected")
        // Error disposal unsubscribes too; a fresh expand starts a new follow.
        result(request("unfollow"))
        rule.onNodeWithTag("jobOutputError:done").assertIsDisplayed().assertTextContains("stream disconnected", substring = true)
        rule.onNodeWithTag("jobRow:done").performClick()
        rule.onNodeWithTag("jobRow:done").performClick()
        result(request("jobs.follow"), jsonOf("subId" to "sub-third"))
        rule.runOnIdle { session = "session-B" }
        rule.onNodeWithTag("jobsPopover").assertDoesNotExist()
        // Cancellation and the new session roster are independent requests.
        rule.waitUntil(5000) { requests.size >= 2 }
        val switched = listOf(requests.remove(), requests.remove())
        val stop = switched.single { it["op"].str() == "unfollow" }
        assertEquals("sub-third", stop["args"]["subId"].str())
        result(stop)
        val fresh = switched.single { it["op"].str() == "jobs.list" }
        assertEquals("session-B", fresh["args"]["sessionId"].str())
        roster(fresh, job("fresh", "completed", finish = 7000))
        push("sub-third", jsonOf("type" to "output", "chunks" to JsonArray(listOf(jsonOf("text" to "late old output")))))
        rule.onNodeWithTag("jobsAction").performClick()
        rule.onNodeWithText("late old output").assertDoesNotExist()
        rule.onNodeWithTag("jobRow:fresh").assertIsDisplayed()
    }

    @Test fun stopConfirmationFailureAndPendingConvergeOnlyThroughRoster() {
        show()
        roster(request(), job("run", "running"), job("stopping", "stopping"), job("done", "completed"))
        rule.onNodeWithTag("jobsAction").performClick()
        rule.onNodeWithTag("jobStop:stopping").assertDoesNotExist()
        rule.onNodeWithTag("jobStop:run").performClick()
        rule.onNodeWithText("确认停止").assertIsDisplayed()
        assertTrue(requests.isEmpty())
        rule.onNodeWithTag("jobStop:run").performClick()
        val rejected = request("jobs.kill")
        assertEquals("run", rejected["args"]["jobId"].str())
        result(rejected, ok = false)
        rule.onNodeWithContentDescription("停止失败").assertIsDisplayed()
        rule.onNodeWithTag("jobStop:run").performClick()
        rule.onNodeWithTag("jobStop:run").performClick()
        val accepted = request("jobs.kill")
        rule.onNodeWithTag("jobStop:run").assertIsNotEnabled()
        result(accepted, jsonOf("outcome" to "requested"))
        rule.onNodeWithTag("jobStop:run").assertIsNotEnabled()
        assertTrue(requests.isEmpty())
        rule.mainClock.advanceTimeBy(3100)
        roster(request(), job("run", "stopping"), job("stopping", "stopping"), job("done", "completed"))
        rule.onNodeWithTag("jobStop:run").assertDoesNotExist()
        rule.onNodeWithText("2 个后台任务运行中").assertIsDisplayed()
    }

    @Test fun backDismissesExpandedOutputAndUnsubscribesWithoutMutatingTheJob() {
        show()
        roster(request(), job("done", "completed", output = 8, finish = 5000))
        rule.onNodeWithTag("jobsAction").performClick()
        rule.onNodeWithTag("jobRow:done").performClick()
        result(request("jobs.follow"), jsonOf("subId" to "sub-back"))
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithTag("jobsPopover").assertDoesNotExist()
        val stop = request("unfollow")
        assertEquals("sub-back", stop["args"]["subId"].str())
        result(stop)
        rule.onNodeWithText("1 个后台任务").assertIsDisplayed()
        assertTrue(requests.isEmpty())
    }

}
