package dev.dsh.pocket.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
        val p = Profile("p1", "Mac mini", host = "100.106.128.87", port = 7690)
        Pocket.profiles.value = listOf(p, Profile("p2", "书房 NAS", host = "192.168.1.20", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online, "p2" to Dot.Offline)
        Pocket.catalog.value = catalog
        Pocket.ops.value = setOf(
            "permissions.set", "presets.list",
            "plugins.list", "schedule.catalog", "sessions.search", "workspace.state",
        )
        Pocket.presets.value = listOf("standard", "ptc", "minimal", "cordis")
        Pocket.sessions.value = listOf(
            SessionSummary("s1", "重构 bridge 鉴权流程", "/Users/felix/src/pi-android", true, false, 3, null),
            SessionSummary("s2", "Termux RUN_COMMAND 失败排查", "/Users/felix/src/pi-android", false, false, 2, null),
            SessionSummary("s3", "梳理 /api 与 remote.mux 协议", "/Users/felix/Documents", false, false, 1, null),
        )
        Pocket.chat.value = null
        Pocket.approvals.value = emptyList()
    }

    private fun chatState(
        hasMore: Boolean = false,
        loadingOlder: Boolean = false,
    ) = ChatState(
        "s1", title = "问候", running = true, loaded = true, runStartedAt = System.currentTimeMillis() - 42_000,
        usage = Usage(3, 11, 10_000, 9_600, 800),
        hasMore = hasMore,
        firstSeq = if (hasMore) 40L else -1L,
        loadingOlder = loadingOlder,
        items = listOf(
            Item.System(
                "sys1", TrajL.layoutInitialSystemPrompt, "You are a helpful coding agent.", at = 0,
                toolsJson = """[{"name":"read","description":"Read a file","parameters":{"type":"object"}}]""",
            ),
            Item.Compacted("cmp1", "c1", CompactionStatus.Ok, summary = "Kept the auth goal.", startedAt = 50, endedAt = 90),
            Item.User("u", "给 pi-rpc-bridge 加上 token 轮换，旧 token 宽限 5 分钟", at = 100, messageSourceJson = """{"kind":"user"}"""),
            Item.Context("x1", "Current runtime context. This snapshot supersedes earlier…", "runtime-context", at = 110, messageSourceJson = """{"kind":"plugin"}"""),
            Item.Context("x2", "<system-reminder>\nA skill is a reusable set of task-specific instructions…", null, at = 120),
            Item.Reasoning("r10", "先看握手逻辑。", 8000, false),
            Item.Text("t10", "握手在 `handleAuth()` 里一次性比对。我会改成同时接受当前 token 和宽限期内的上一个：\n```js\nconst valid = [current, previous]\n  .filter(t => t && t.expiresAt > now)\n  .some(t => safeEqual(t.value, got))\n```", false),
            Item.Tool("t0", "root", PTC_TOOL_NAME, """{"code":"await read({path:'bridge/pi-rpc-bridge.mjs'})","description":"inspect bridge"}""", ToolStatus.Ok, "done", startedAt = 180, endedAt = 199, turn = 1, step = 1),
            Item.Tool("t0s", "root:ptc:1", "read", """{"path":"bridge/pi-rpc-bridge.mjs"}""", ToolStatus.Ok, "ok", startedAt = 185, endedAt = 195, turn = 1, step = 1, parentCallId = "root"),
            Item.Tool("t1", "c1", "read", """{"path":"bridge/pi-rpc-bridge.mjs"}""", ToolStatus.Ok, "ok", startedAt = 200, endedAt = 240),
            Item.Tool("t2", "c2", "grep", """{"pattern":"auth"}""", ToolStatus.Ok, "6 matches", startedAt = 250, endedAt = 370),
            Item.Tool("t3", "c3", "edit", """{"path":"bridge/pi-rpc-bridge.mjs"}""", ToolStatus.Running, startedAt = 400),
            Item.Tool("t4", "c4", "bash", """{"command":"npm install --save-dev vitest@latest && npx vitest run bridge/"}""", ToolStatus.AwaitingApproval, startedAt = 500),
            Item.Tool("t5", "c5", "bash", """{"command":"false"}""", ToolStatus.Error, error = "exit 1", startedAt = 600, endedAt = 610),
        ),
        steps = listOf(
            StepRecord(
                seq = 10, turn = 1, step = 1, stepStartTime = 150, messageTime = 8250,
                firstTokenTime = 400,
                replyText = "握手在 `handleAuth()` 里一次性比对。我会改成同时接受当前 token 和宽限期内的上一个：\n```js\nconst valid = [current, previous]\n  .filter(t => t && t.expiresAt > now)\n  .some(t => safeEqual(t.value, got))\n```",
                reasoningText = "先看握手逻辑。",
                usage = StepUsage(inputTokens = 18_200, outputTokens = 431, reasoningTokens = 120),
                provider = "deepseek", model = "deepseek-v4.1-flash",
                toolCallIds = listOf("root", "root:ptc:1", "c1", "c2", "c3", "c4", "c5"),
                requestConfigJson = """{"provider":"deepseek","model":"deepseek-v4.1-flash","temperature":0.2,"maxTokens":8192}""",
            ),
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
    @Test fun s06b_termuxHelp() = showWithDialogs("06b-termux-help") { TermuxHelpSheet({}) }
    @Test fun s06c_termuxConnect() = showWithDialogs("06c-termux-connect") { TermuxConnectSheet(onDismiss = {}, onConnected = {}) }
    @Test fun s07_pairSheet() { Pocket.profiles.value = emptyList(); showWithDialogs("07-pair-sheet") { PairSheet(PairingLink("100.106.128.87", 7690, "752314"), onDismiss = {}, onPaired = {}) } }
    @Test fun s07b_cameraPermissionSheet() = showWithDialogs("07b-camera-permission") { CameraPermissionSheet({}, {}) }
    @Test fun s08_settings() = show("08-settings") {
        Pocket.ops.value = setOf("models.providers", "plugins.list", "schedule.catalog", "credentials.list")
        SettingsScreen({}, {}, {}, {}, {}, {}, {})
    }
    @Test fun s09_general() = show("09-general-settings") { GeneralSettingsScreen {} }
    @Test fun s22_modelsSettings() = show("22-models-settings") {
        Pocket.ops.value = setOf("models.providers")
        ModelsSettingsScreen {}
    }
    @Test fun s23_pluginsSettings() = show("23-plugins-settings") {
        Pocket.ops.value = setOf("plugins.list")
        PluginsSettingsScreen {}
    }
    @Test fun s24_scheduleSettings() = show("24-schedule-settings") {
        Pocket.ops.value = setOf("schedule.catalog", "schedule.create")
        ScheduleSettingsScreen {}
    }
    @Test fun s25_credentialsSettings() = show("25-credentials-settings") {
        Pocket.ops.value = setOf("credentials.list", "credentials.set")
        CredentialsSettingsScreen {}
    }
    @Test fun s10_modelSheet() = showWithDialogs("10-model-sheet") { ModelSheet(catalog, ModelChoice("deepseek", "deepseek-v4.1-flash", "DeepSeek-V4.1-Flash", "high"), {}) {} }
    @Test fun s11_permissionSheet() = showWithDialogs("11-permission-sheet") { PermissionSheet("workspace-write", {}) {} }
    @Test fun s12_plusSheet() = showWithDialogs("12-plus-sheet") {
        PlusSheet({}, {}, {}, {}, {}, {}, {}, {}, {})
    }
    @Test fun s14_trajectory() {
        Pocket.chat.value = chatState(hasMore = true)
        show("14-trajectory") {
            TrajectoryView(chatState(hasMore = true))
        }
    }
    @Test fun s14b_trajectoryDetail() {
        val tool = Item.Tool(
            "t1", "c1", "read", """{"file_path":"/Users/felix/.dsh/profiles/desktop/cordis.patch.yml"}""",
            ToolStatus.Ok,
            result = "<path>/Users/felix/.dsh/profiles/desktop/cordis.patch.yml</path>\n<type>file</type>",
            startedAt = 1_760_287_161_514L, endedAt = 1_760_287_161_525L,
            schemaJson = """{"name":"read","description":"Read a UTF-8 text file and return line-numbered content.","parameters":{"type":"object","properties":{"file_path":{"type":"string"}}}}""",
            turn = 6, step = 1,
        )
        val steps = listOf(
            StepRecord(
                seq = 60, turn = 6, step = 1, stepStartTime = 1_760_287_161_000L, messageTime = 1_760_287_162_000L,
                replyText = "read done", reasoningText = "", usage = StepUsage(inputTokens = 100, outputTokens = 20),
                provider = "deepseek", model = "deepseek-v4.1-flash", toolCallIds = listOf("c1"),
            ),
        )
        val requests = buildTrajectoryRequests(listOf(Item.User("u", "hi"), tool), steps)
        val row = buildTrajectory(listOf(Item.User("u", "hi"), tool), steps).single().rows.single { it.kind == TrajL.kindTool }
        showWithDialogs("14b-trajectory-detail") {
            TrajectoryDetailSheet(row, turnIndex = 6, parentRequest = requests.single()) {}
        }
    }
    @Test fun s14d_requestInspector() {
        val state = chatState()
        val requests = buildTrajectoryRequests(state.items, state.steps)
        val assistant = requests.first { it.purpose == TrajectoryRequest.Purpose.Assistant }
        showWithDialogs("14d-request-inspector") {
            TrajectoryRequestSheet(assistant) {}
        }
    }
    @Test fun s14e_historyLoadEarlier() {
        show("14e-history-load-earlier") {
            TrajectoryView(chatState(hasMore = true))
        }
    }

    private fun trajKindRow(kind: String): TrajRow {
        val state = chatState()
        return buildTrajectory(state.items, state.steps).flatMap { it.rows }.first { it.kind == kind }
    }

    private fun shotKind(name: String, kind: String) {
        val row = trajKindRow(kind)
        showWithDialogs(name) { TrajectoryDetailSheet(row, turnIndex = row.turn ?: 1) {} }
    }

    @Test fun s14c_kindSystem() = shotKind("14c-kind-system", TrajL.kindSystem)
    @Test fun s14c_kindCompacted() = shotKind("14c-kind-compacted", TrajL.kindCompacted)
    @Test fun s14c_kindUser() = shotKind("14c-kind-user", TrajL.kindUser)
    @Test fun s14c_kindContext() = shotKind("14c-kind-context", TrajL.kindContext)
    @Test fun s14c_kindAssistant() = shotKind("14c-kind-assistant", TrajL.kindAssistant)
    @Test fun s14c_kindTool() = shotKind("14c-kind-tool", TrajL.kindTool)
    @Test fun s14c_kindSubtool() = shotKind("14c-kind-subtool", TrajL.kindSubtool)
    @Test fun s15_generalWired() = show("15-general-settings") { GeneralSettingsScreen {} }
    @Test fun s16_panelWithTerminal() {
        Pocket.ops.value = setOf("files.list", "terminal.create", "jobs.list")
        showWithDialogs("16-panel-terminal") {
            PanelSheet(cwd = "/tmp/ws", onDismiss = {}, onFiles = {}, onTerminal = {}, onJobs = {})
        }
    }
    @Test fun s17_profileManageSheet() = showWithDialogs("17-profile-manage") {
        ProfileManageSheet(Pocket.profiles.value.first(), Dot.Online, isActive = true, onDismiss = {}, onConnect = {})
    }
    @Test fun s18_profileRenamePanel() {
        rule.setContent {
            PocketTheme {
                ProfileManageSheet(Pocket.profiles.value.first(), Dot.Online, isActive = true, onDismiss = {}, onConnect = {})
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("重命名").performClick()
        rule.waitForIdle()
        captureScreenRoboImage(out("18-profile-rename"))
    }
    @Test fun s20_todoSheet() = showWithDialogs("20-todo-sheet") {
        TodoSheet(
            listOf(
                TodoItem("读配置", "completed"),
                TodoItem("改代码", "in_progress"),
                TodoItem("写测试", "pending"),
            ),
        ) {}
    }
    @Test fun s21_panelWithTodo() {
        Pocket.ops.value = setOf("files.list", "jobs.list")
        showWithDialogs("21-panel-todo") {
            PanelSheet(
                cwd = "/tmp/ws",
                todos = listOf(TodoItem("读配置", "completed"), TodoItem("改代码", "in_progress")),
                onDismiss = {},
                onFiles = {},
                onTerminal = {},
                onJobs = {},
            )
        }
    }
    @Test fun s19_terminalSheet() {
        Pocket.ops.value = setOf("terminal.create", "terminal.follow")
        Pocket.terminalMeta.value = TerminalMeta("s1", "t1", "a1", title = "zsh", state = "running")
        Pocket.terminal.resize(48, 16)
        Pocket.terminal.clear()
        // Powerlevel10k-ish prompt: Nerd Font icons (PUA) + ANSI colors.
        Pocket.terminal.write(
            "\u001B[2J\u001B[H" +
                "\u001B[1;34m\uE0B6\u001B[0m\u001B[1;44;37m felix \u001B[0m\u001B[1;34m\uE0B0\u001B[0m " +
                "\u001B[36m~\u001B[0m \u001B[1;32m\uE0A0\u001B[0m \u001B[33m22:55\u001B[0m\r\n" +
                "\u001B[1;34m❯\u001B[0m pwd\r\n" +
                "/Users/felix/Documents\r\n" +
                "\u001B[1;34m❯\u001B[0m ",
        )
        Pocket.terminalRevision.value = Pocket.terminal.revision
        showWithDialogs("19-terminal-sheet") {
            TerminalSheet("s1") {}
        }
    }
}
