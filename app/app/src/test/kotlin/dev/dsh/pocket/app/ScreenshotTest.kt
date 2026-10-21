package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.dsh.pocket.protocol.*
import org.junit.Assert.assertTrue
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
    private val permissionOptions = listOf(
        PermissionOption("read-only", "Read Only", "只读取文件，不修改内容"),
        PermissionOption("workspace-write", "Workspace Write", "在工作区内读写文件并执行命令"),
        PermissionOption("danger-full-access", "Full access", "不受沙箱限制"),
        PermissionOption("auto", "Auto"),
    )
    private val permissions = PermissionCatalog(permissionOptions, permissionOptions.take(3), "workspace-write")

    @Before fun state() {
        installHostSettingsFixture()
        // Skip NEARBY_WIFI in-panel CTA so connection screenshots show empty/nearby chrome.
        runCatching { Pocket.prefs.nearbyWifiDeclined = true }
        runCatching { Pocket.prefs.appearance = Appearance.Light }
        val p = Profile("p1", "Mac mini", host = "100.106.128.87", port = 7690)
        Pocket.profiles.value = listOf(p, Profile("p2", "书房 NAS", host = "192.168.1.20", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online, "p2" to Dot.Offline)
        Pocket.catalog.value = catalog
        Pocket.permission.value = mapOf("s1" to "workspace-write")
        Pocket.permissionCatalog.value = permissions
        Pocket.permissionCatalogLoading.value = false
        Pocket.permissionCatalogError.value = null
        Pocket.permissionChanging.value = false
        Pocket.prefs.performanceUsage = PerformanceUsage.Compact
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.sessionGroupBy = SessionGroupBy.Workspace
        Pocket.prefs.sessionOrderBy = SessionOrderBy.Updated
        Pocket.prefs.archivedFilter = ArchivedFilter.Default
        Pocket.prefs.fontSize = 14
        Pocket.prefs.codeFontSize = 13
        Pocket.prefs.terminalFontSize = 13
        Pocket.prefs.showCodeWorkView = true
        Pocket.prefs.transcriptView = TranscriptView.Detailed
        Pocket.prefs.setCollapsedWorkspaces("p1", emptySet())
        Pocket.pinnedIds.value = emptySet()
        Pocket.archivedIds.value = emptySet()
        Pocket.userQuestions.value = emptyList()
        Pocket.workspaces.value = listOf(
            WorkspaceView("w1", "/Users/felix/src/pi-android", "pi-android", listOf("s1", "s2"), null, null),
            WorkspaceView("w2", "/Users/felix/Documents", "Documents", listOf("s3"), null, null),
        )
        Pocket.ops.value = setOf(
            "permissions.catalog", "permissions.set", "presets.list",
            "plugins.list", "schedule.catalog", "sessions.search", "workspace.state",
        )
        Pocket.presetRoster.value = listOf(
            jsonOf("id" to "standard", "name" to "标准模式", "isDefault" to true),
            jsonOf("id" to "ptc", "name" to "编程模式"),
            jsonOf("id" to "minimal", "name" to "极简模式"),
            jsonOf("id" to "cordis", "name" to "Cordis"),
        )
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
        composerMetrics = ComposerMetrics(
            activity = ComposerActivity(3, 11, 42000.0, 2000.0, 800.0, 1, 10000.0, 690),
            tokens = ComposerTokenUsage(10000, 0, 0, 690),
            context = ComposerContext(9000.0, 100000.0),
            activityIsWholeSession = true,
        ),
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
    @Test fun s05_connections() = show("05-connections") {
        ConnectionsScreen(
            null, {}, true, {}, {},
            nearbySeed = listOf(
                DiscoveredHost("客厅 Mac", "192.168.1.42", 7690, "f".repeat(64)),
            ),
        )
    }
    @Test fun s06_connectionsEmpty() {
        Pocket.profiles.value = emptyList()
        Pocket.active.value = null
        show("06-connections-empty") { ConnectionsScreen(null, {}, false, {}, {}) }
    }
    @Test fun s07_pairSheet() { Pocket.profiles.value = emptyList(); showWithDialogs("07-pair-sheet") { PairSheet(PairingLink("100.106.128.87", 7690, "752314"), onDismiss = {}, onPaired = {}) } }
    @Test fun s07b_cameraPermissionSheet() = showWithDialogs("07b-camera-permission") { CameraPermissionSheet({}, {}) }
    @Test fun s08_settings() {
        // Older Hosts may still advertise this API; it must not invent a settings page.
        Pocket.ops.value = setOf("models.providers", "plugins.list", "schedule.catalog", "credentials.list")
        var modelsOpened = false
        show("08-settings") {
            SettingsScreen({}, {}, {}, onModels = { modelsOpened = true })
        }
        rule.onNodeWithText("凭证").assertDoesNotExist()
        rule.onNodeWithText(L.modelsNav()).performClick()
        assertTrue(modelsOpened)
    }
    @Test fun s09_general() = show("09-general-settings") { GeneralSettingsScreen {} }
    @Test fun s22_modelsSettings() {
        Pocket.ops.value = setOf("models.providers")
        show("22-models-settings") { ModelsSettingsScreen {} }
        rule.onNodeWithText(L.modelsIntro()).assertExists()
        rule.onNodeWithText("插件自定义界面").assertDoesNotExist()
        rule.onNodeWithText("添加模型提供商").assertExists()
    }
    @Test fun s23_pluginsSettings() = show("23-plugins-settings") {
        Pocket.ops.value = setOf("plugins.list")
        PluginsSettingsScreen(onBack = {}, onCreatePlugin = {})
    }
    @Test fun s24_scheduleSettings() = show("24-schedule-settings") {
        Pocket.ops.value = setOf("schedule.catalog", "schedule.create")
        ScheduleSettingsScreen(onBack = {}, onNewTask = {}, onOpenSession = {})
    }
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun s10_modelPicker() {
        // Native Back matches desktop Escape without adding submenu header chrome.
        showWithDialogs("10-model-picker-root") {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomEnd) {
                Box { ModelPicker(catalog, ModelChoice("deepseek", "deepseek-v4.1-flash", "DeepSeek-V4.1-Flash", "high"), {}, { _, _ -> }) }
            }
        }
        rule.onNodeWithText("模型").performClick()
        rule.waitForIdle()
        captureScreenRoboImage(out("10b-model-picker-models"))
        rule.onNodeWithTag("modelPicker").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Back) }
        rule.onNodeWithText("推理等级").performClick()
        rule.waitForIdle()
        captureScreenRoboImage(out("10c-model-picker-efforts"))
    }
    @Test
    @Config(qualifiers = "w915dp-h412dp-xxhdpi")
    fun s10d_modelPickerLandscapeDark() {
        Pocket.prefs.appearance = Appearance.Dark
        val many = catalog.copy(groups = catalog.groups + ModelGroup("qoder", "Qoder CN", listOf(
            ModelInfo("qoder", "glm", "GLM 5.1", null, emptyList(), null),
            ModelInfo("qoder", "qwen", "Qwen 3.6 Flash", null, emptyList(), null),
            ModelInfo("qoder", "long", "DeepSeek V4 Pro — Enterprise reasoning model", null, emptyList(), null),
        )))
        showWithDialogs("10d-model-picker-landscape-dark") {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomEnd) {
                Box { ModelPicker(many, ModelChoice("deepseek", "deepseek-v4.1-flash", "DeepSeek-V4.1-Flash", "high"), {}, { _, _ -> }, openModels = true) }
            }
        }
        val heading = rule.onNodeWithText("DeepSeek").fetchSemanticsNode().boundsInRoot
        val selected = rule.onNodeWithText("DeepSeek-V4.1-Flash").fetchSemanticsNode().boundsInRoot
        kotlin.test.assertTrue(selected.top >= heading.bottom, "Pinned provider must not cover the selected model")
    }
    @Test fun s11_permissionSheet() = showWithDialogs("11-permission-sheet") {
        PermissionPickerSheet("workspace-write", permissions, false, null, {}, {}, {})
    }
    @Test fun s12_commandMenu() = showWithDialogs("12-command-menu") {
        CommandPopupSheet({}, {}, {}, {}, {}, {}, {}, {}, {})
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
        Pocket.ops.value = setOf("files.list", "terminal.create", "terminal.follow", "jobs.list")
        showWithDialogs("16-panel-terminal") {
            PanelSheet(onDismiss = {}, onFiles = {}, onTerminal = {}, onBrowser = {}, onInspector = {})
        }
    }
    @Test fun s17_profileManageSheet() = showWithDialogs("17-profile-manage") {
        ProfileManageSheet(Pocket.profiles.value.first(), Dot.Online, isActive = true, onDismiss = {})
    }
    @Test fun s18_profileRenamePanel() {
        rule.setContent {
            PocketTheme {
                ProfileManageSheet(Pocket.profiles.value.first(), Dot.Online, isActive = true, onDismiss = {})
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("重命名").performClick()
        rule.waitForIdle()
        captureScreenRoboImage(out("18-profile-rename"))
    }
    @Test fun s20_todoPanel() {
        show("20-todo-panel-collapsed") {
            TodoPanel("todo-fixture",
                listOf(
                    TodoItem("读配置", "completed"),
                    TodoItem("改代码", "in_progress"),
                    TodoItem("写测试", "pending"),
                ),
            )
        }
        rule.onNodeWithContentDescription("展开任务").performClick()
        rule.onRoot().captureRoboImage(out("20-todo-panel-expanded"))
        val rows = listOf("读配置", "改代码", "写测试").map {
            rule.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        rows.zipWithNext().forEach { (previous, next) ->
            assertTrue("Task content must not overlap the next task", previous.bottom <= next.top)
        }
    }
    @Test fun s21_panelWithoutSession() {
        Pocket.ops.value = setOf("files.list", "terminal.create", "terminal.follow")
        showWithDialogs("21-panel-no-session") {
            PanelSheet(onDismiss = {}, onFiles = {}, onTerminal = {}, onBrowser = {}, onInspector = {})
        }
    }
    @Test
    @Config(qualifiers = "w915dp-h412dp-xxhdpi")
    fun s22_panelLandscape() {
        Pocket.ops.value = setOf("files.list", "terminal.create", "terminal.follow")
        showWithDialogs("22-panel-landscape") {
            PanelSheet(onDismiss = {}, onFiles = {}, onTerminal = {}, onBrowser = {}, onInspector = {})
        }
    }
    @Test fun s23_panelDark() {
        Pocket.prefs.appearance = Appearance.Dark
        Pocket.ops.value = setOf("files.list", "terminal.create", "terminal.follow")
        showWithDialogs("23-panel-dark") {
            PanelSheet(onDismiss = {}, onFiles = {}, onTerminal = {}, onBrowser = {}, onInspector = {})
        }
    }
    @Test fun s19_terminalSheet() {
        Pocket.ops.value = setOf("terminal.create", "terminal.follow")
        // Render the real terminal surface with a deterministic display fixture (no Host attachment).
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
            RightPanelFrame("zsh", onDismiss = {}, onBack = {}) {
                TerminalView(Pocket.terminal, Pocket.terminalRevision.value, Modifier.fillMaxSize(), onResize = { _, _ -> }, onInput = {})
            }
        }
    }
    private fun messageChromeState(): ChatState {
        val at = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 17)
            set(java.util.Calendar.MINUTE, 10)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ChatState("s1", title = "你好", loaded = true, running = false,
            turns = listOf(TurnRecord(1, startSeq = 2, endSeq = 12, startedAt = at, endedAt = at + 6000, endReason = "completed")),
            items = listOf(
                Item.User("u1", "你好", at = at, seq = 1, messageSourceJson = """{"kind":"user"}"""),
                Item.Reasoning("r1", "Let me check the status of the background jobs and wait for the teammates to complete their work.", 1000, false, turn = 1, step = 1),
                Item.Tool("t1", "c1", "wait_agent", """{"timeout_ms":120000}""", ToolStatus.Ok,
                    result = """{"timedOut":false,"noProgress":{"message":"No active subagents"}}""", startedAt = at + 1000, endedAt = at + 1010, turn = 1, step = 1),
                Item.Reasoning("r2", "Let me check the status of the background jobs and the teammates.", 1000, false, turn = 1, step = 2),
                Item.Tool("t2", "c2", "job_list", "{}", ToolStatus.Ok,
                    result = "(no background jobs)", startedAt = at + 2000, endedAt = at + 2010, turn = 1, step = 2),
                Item.Tool("t3", "c3", "list_agents", "{}", ToolStatus.Ok,
                    result = "Main [running]", startedAt = at + 2010, endedAt = at + 2020, turn = 1, step = 2),
                Item.Reasoning("r3", "The user just said \"你好\" (Hello in Chinese). This is a simple greeting.\n\nI should respond with a friendly greeting and ask how I can help.", 1000, false, turn = 1, step = 3),
                Item.Text("a1", "你好！有什么可以帮你的吗？", false, at = at + 6000, seq = 11, messageId = "m1", turn = 1, step = 3),
            ))
    }

    private fun messageChromeScreenshots(dark: Boolean) {
        Pocket.prefs.appearance = if (dark) Appearance.Dark else Appearance.Light
        Pocket.prefs.transcriptView = TranscriptView.Standard
        Pocket.prefs.collapseTiming = CollapseTiming.Completion
        Pocket.ops.value = setOf("feedback.set", "sessions.fork")
        val state = messageChromeState()
        val suffix = if (dark) "dark" else "light"
        show("26-message-chrome-collapsed-$suffix") {
            Box(Modifier.fillMaxSize().background(T.Bg)) { Transcript(state, {}, canFork = true) }
        }
        rule.onNodeWithTag("processHeader:turn-1-process").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(out("26-message-chrome-expanded-$suffix"))
        rule.onNodeWithTag("processHeader:r1-group").performClick()
        rule.onNodeWithTag("reasoningHeader:r3").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(out("26-message-reasoning-expanded-$suffix"))
    }

    @Test fun s26_messageChromeLight() = messageChromeScreenshots(false)
    @Test fun s26_messageChromeDark() = messageChromeScreenshots(true)
}
