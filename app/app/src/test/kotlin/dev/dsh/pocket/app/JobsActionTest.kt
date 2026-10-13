package dev.dsh.pocket.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.github.takahirom.roborazzi.captureRoboImage
import dev.dsh.pocket.protocol.*
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
    }

    @After fun cleanup() {
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
        client.close()
        clientField.set(Pocket, originalClient)
    }

    private fun show(onClick: () -> Unit = {}) {
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PocketTheme {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        JobsAction(host, session, available, onClick)
                    }
                }
            }
        }
    }

    private fun request(): JsonObject {
        rule.waitUntil(5000) { requests.isNotEmpty() }
        return requests.remove().also {
            assertEquals("jobs.list", it["op"].str())
            assertEquals(session, it["args"]["sessionId"].str())
        }
    }

    private fun reply(request: JsonObject, statuses: List<String> = emptyList(), error: Boolean = false) {
        val rows = statuses.mapIndexed { i, status -> jsonOf("id" to "job-$i", "status" to status) }
        val frame = if (error) jsonOf("id" to request["id"], "ok" to false,
            "error" to jsonOf("code" to "internal", "message" to "roster unavailable"))
        else jsonOf("id" to request["id"], "ok" to true, "result" to jsonOf("jobs" to JsonArray(rows)))
        rule.runOnIdle {
            client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
                .invoke(client, frame.toString())
        }
        rule.waitForIdle()
    }

    private fun refresh(statuses: List<String> = emptyList(), error: Boolean = false) {
        rule.mainClock.advanceTimeBy(3100)
        reply(request(), statuses, error)
    }

    @Test fun emptyRosterHidesEntryAndLiveCountExcludesSettledJobs() {
        var opened = false
        show { opened = true }
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        reply(request())
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/screenshots/jobs-empty.png")

        refresh(listOf("running", "stopping", "completed", "failed", "killed"))
        rule.onNodeWithText("2 个后台任务运行中").assertIsDisplayed().performClick()
        assertTrue(opened)
        rule.onRoot().captureRoboImage("build/screenshots/jobs-running.png")
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
        rule.runOnIdle { available = false }
        rule.onNodeWithTag("jobsAction").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(6500)
        assertTrue(requests.isEmpty())
        rule.runOnIdle { available = true }
        rule.waitForIdle()
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
}
