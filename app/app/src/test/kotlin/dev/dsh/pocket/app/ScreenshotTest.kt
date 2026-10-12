package dev.dsh.pocket.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.dsh.pocket.protocol.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders key screens at 412dp width into build/screenshots for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private fun out(name: String) = "build/screenshots/$name.png"

    private fun show(name: String, content: @Composable () -> Unit) {
        rule.setContent { PocketTheme { Box(Modifier.fillMaxSize()) { content() } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(out(name))
    }
    private fun showWithDialogs(name: String, content: @Composable () -> Unit) {
        rule.setContent { PocketTheme { Box(Modifier.fillMaxSize()) { content() } } }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        captureScreenRoboImage(out(name))
    }

    private val catalog = ModelCatalog.parse(PocketJson.parseToJsonElement("""{"default":{"provider":"deepseek","model":"deepseek-v4.1-flash"},
      "groups":[{"id":"deepseek","name":"DeepSeek","models":[
        {"id":"deepseek-v4.1-flash","name":"DeepSeek-V4.1-Flash","reasoning":{"defaultEffort":"high","efforts":[{"id":"off","name":"Off","description":"Use for simple tasks that do not need reasoning."},{"id":"low","name":"Low","description":"Prefer for routine or latency-sensitive tasks."},{"id":"high","name":"High","description":"The default balance for most tasks."},{"id":"max","name":"Max","description":"Reserve for the hardest quality-first tasks."}]}},
        {"id":"deepseek-v4-pro","name":"DeepSeek-V4-Pro","description":"Stronger agentic coding, knowledge, and difficult reasoning; suited to complex or quality-critical tasks at higher cost."}]}]}"""))

    @Before fun state() {
        val p = Profile("p1", "Mac mini", "100.106.128.87", 7690)
        Pocket.profiles.value = listOf(p, Profile("p2", "书房 NAS", "192.168.1.20", 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online, "p2" to Dot.Offline)
        Pocket.catalog.value = catalog
        Pocket.ops.value = setOf("permissions.set", "presets.list")
        Pocket.presets.value = listOf("standard", "ptc", "minimal", "cordis")
        Pocket.sessions.value = listOf(
            SessionSummary("s1", "重构 bridge 鉴权流程", "/Users/felix/src/pi-android", true, false, 3, null),
            SessionSummary("s2", "Termux RUN_COMMAND 失败排查", "/Users/felix/src/pi-android", false, false, 2, null),
            SessionSummary("s3", "梳理 /api 与 remote.mux 协议", "/Users/felix/Documents", false, false, 1, null),
        )
        Pocket.chat.value = null
        Pocket.approvals.value = emptyList()
    }

    private fun chatState() = ChatState(
        "s1", title = "问候", running = true, loaded = true, runStartedAt = System.currentTimeMillis() - 42_000,
        usage = Usage(3, 11, 10_000, 9_600, 800),
        items = listOf(
            Item.User("u", "给 pi-rpc-bridge 加上 token 轮换，旧 token 宽限 5 分钟"),
            Item.Reasoning("r", "先看握手逻辑。", 8000, false),
            Item.Tool("t1", "c1", "read", """{"path":"bridge/pi-rpc-bridge.mjs"}""", ToolStatus.Ok, "ok"),
            Item.Tool("t2", "c2", "grep", """{"pattern":"auth"}""", ToolStatus.Ok, "6 matches"),
            Item.Text("x", "握手在 `handleAuth()` 里一次性比对。我会改成同时接受当前 token 和宽限期内的上一个：\n```js\nconst valid = [current, previous]\n  .filter(t => t && t.expiresAt > now)\n  .some(t => safeEqual(t.value, got))\n```", false),
            Item.Tool("t3", "c3", "edit", """{"path":"bridge/pi-rpc-bridge.mjs"}""", ToolStatus.Running),
            Item.Tool("t4", "c4", "bash", """{"command":"npm install --save-dev vitest@latest && npx vitest run bridge/"}""", ToolStatus.AwaitingApproval),
        ),
    )

    @Test fun s01_newChat() = show("01-new-chat") { ChatRoot(onMenu = {}) }
    @Test fun s02_chat() { Pocket.chat.value = chatState(); show("02-chat") { ChatRoot(onMenu = {}) } }
    @Test fun s13_firstMessage() {
        Pocket.sessions.value = emptyList()
        Pocket.chat.value = ChatState("s9", title = null, running = true, loaded = true, runStartedAt = System.currentTimeMillis() - 11_000,
            usage = Usage(1, 0, 0, 0, 0), items = listOf(Item.User("local-0", "你好", pending = true)))
        show("13-first-message") { ChatRoot(onMenu = {}) }
    }
    @Test fun s03_approval() {
        Pocket.chat.value = chatState()
        Pocket.approvals.value = listOf(Approval("a1", "s1", "bash", "c4", "escalate sandbox"))
        show("03-approval") { ChatRoot(onMenu = {}) }
    }
    @Test fun s04_drawer() = show("04-drawer") { SessionDrawer({}, {}, {}, {}) }
    @Test fun s05_connections() = show("05-connections") { ConnectionsScreen(null, {}, true, {}, {}) }
    @Test fun s06_connectionsEmpty() { Pocket.profiles.value = emptyList(); Pocket.active.value = null; show("06-connections-empty") { ConnectionsScreen(null, {}, false, {}, {}) } }
    @Test fun s07_pairSheet() { Pocket.profiles.value = emptyList(); showWithDialogs("07-pair-sheet") { PairSheet(PairingLink("100.106.128.87", 7690, "7523"), {}, {}) } }
    @Test fun s07b_cameraPermissionSheet() = showWithDialogs("07b-camera-permission") { CameraPermissionSheet({}, {}) }
    @Test fun s08_settings() = show("08-settings") { SettingsScreen({}, {}, {}) }
    @Test fun s09_general() = show("09-general-settings") { GeneralSettingsScreen {} }
    @Test fun s10_modelSheet() = showWithDialogs("10-model-sheet") { ModelSheet(catalog, ModelChoice("deepseek", "deepseek-v4.1-flash", "DeepSeek-V4.1-Flash", "high"), {}) {} }
    @Test fun s11_permissionSheet() = showWithDialogs("11-permission-sheet") { PermissionSheet("workspace-write", {}) {} }
    @Test fun s12_plusSheet() = showWithDialogs("12-plus-sheet") {
        PlusSheet({}, {}, {}, {}, {}, {}, {}, {}, {})
    }
    @Test fun s14_trajectory() {
        Pocket.chat.value = chatState()
        show("14-trajectory") {
            TrajectoryView(chatState()) {}
        }
    }
    @Test fun s15_generalWired() = show("15-general-settings") { GeneralSettingsScreen {} }
}
