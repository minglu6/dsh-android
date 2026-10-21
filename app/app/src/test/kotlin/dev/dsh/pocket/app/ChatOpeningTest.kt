package dev.dsh.pocket.app

import android.content.ContextWrapper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class ChatOpeningTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var fixture: ChatOpeningFixture
    private val mounted = mutableStateOf(true)
    private var hasContent = false
    private val sessionId = "toeic-listening"
    private val title = "托业考试听力练习方法"
    private val oldTitle = "旧目录标题"
    private val flash = ModelChoice("qoder-cn", "dfmodel", "DeepSeek-V4.1-Flash", "off")
    private val auto = ModelChoice("qoder-cn", "auto", "Auto", "off")

    @Before fun setUp() {
        Pocket.init(RuntimeEnvironment.getApplication())
        fixture = ChatOpeningFixture()
    }

    @After fun tearDown() {
        if (hasContent) {
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
        }
        fixture.close()
    }

    private fun show(pendingPreset: String? = null) {
        hasContent = true
        rule.setContent { PocketTheme {
            if (mounted.value) ChatRoot(onMenu = {}, pendingPreset = pendingPreset)
        } }
    }

    private fun opening() {
        Pocket.sessions.value = listOf(SessionSummary(sessionId, oldTitle, "/toeic", false, false, 1, null))
        Pocket.model.value = mapOf(sessionId to flash)
        Pocket.permission.value = mapOf(sessionId to "workspace-write")
        Pocket.chat.value = ChatState(sessionId, title = "旧快照标题", agentPreset = "cordis", loaded = false)
    }

    private fun assertSyncing() {
        rule.onNodeWithText("同步会话…").assertIsDisplayed()
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("同步中…")
        rule.onNodeWithTag("composerModel").assertContentDescriptionEquals("同步中…").assertIsNotEnabled()
        rule.onNodeWithTag("composerPermission").assertIsNotEnabled()
            .assertContentDescriptionEquals("正在同步权限")
        rule.onNodeWithTag("composerSend").assertIsNotEnabled()
        rule.onNodeWithText(flash.name).assertDoesNotExist()
        rule.onNodeWithText("Off", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("modelReleaseBadge", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("工作区内修改").assertDoesNotExist()
        rule.onNodeWithContentDescription("仅可查看").assertDoesNotExist()
        rule.onNodeWithContentDescription("创造模式").assertDoesNotExist()
        rule.onNodeWithText("创造模式").assertDoesNotExist()
        rule.onNodeWithText(oldTitle).assertDoesNotExist()
        rule.onNodeWithText("旧快照标题").assertDoesNotExist()
    }

    @Test fun openingHidesCachedDefaultsUntilSnapshotAndPreservesDraftAcrossReady() {
        opening()
        show()
        rule.onNodeWithTag("composerInput").performTextInput("请继续整理听力练习计划")
        assertSyncing()
        rule.onNodeWithTag("composerInput").assertTextContains("请继续整理听力练习计划")
        rule.onRoot().captureRoboImage("build/screenshots/chat-opening-loading.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))

        rule.runOnIdle {
            Pocket.model.value = mapOf(sessionId to auto)
            Pocket.permission.value = mapOf(sessionId to "auto")
            // The directory intentionally stays stale; the loaded conversation is authoritative.
            Pocket.chat.value = ChatState(sessionId, title = title, agentPreset = "standard", loaded = true,
                items = listOf(
                    Item.User("history-question", "托业听力应该怎么练习？"),
                    Item.Text("history-answer", "# 建议\n\n每天完成一组听力练习，并复盘错题。", false),
                ))
        }
        rule.onNodeWithTag("sessionTitle").assertTextEquals(title)
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("Auto")
        rule.onNodeWithTag("composerModel").assertIsEnabled()
        rule.onNodeWithTag("composerPermission").assertIsEnabled().assertContentDescriptionEquals("自动审查")
        rule.onNodeWithTag("composerSend").assertIsEnabled()
        rule.onNodeWithTag("composerInput").assertTextContains("请继续整理听力练习计划")
        rule.onNodeWithText("每天完成一组听力练习，并复盘错题。").assertIsDisplayed()
        rule.onNodeWithText(oldTitle).assertDoesNotExist()
        rule.onNodeWithText("同步会话…").assertDoesNotExist()
        rule.onNodeWithText(flash.name).assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/screenshots/chat-opening-ready.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    @Test fun switchingToAnotherOpeningConversationDoesNotReusePreviousMetadataOrCachedBadge() {
        val previous = "previous-session"
        val badgeModel = ModelChoice("deepseek-account", "deepseek-flash", flash.name, "off")
        Pocket.chat.value = ChatState(previous, title = "上一会话", agentPreset = "cordis", loaded = true)
        Pocket.model.value = mapOf(previous to badgeModel, sessionId to badgeModel)
        Pocket.permission.value = mapOf(previous to "read-only", sessionId to "read-only")
        show()
        rule.onNodeWithTag("sessionTitle").assertTextEquals("上一会话")
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals(flash.name)
        rule.onNodeWithTag("modelReleaseBadge", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("composerPermission").assertContentDescriptionEquals("仅可查看")
        rule.onNodeWithContentDescription("创造模式").assertIsDisplayed()
        rule.onNodeWithTag("composerInput").performTextInput("上一会话的草稿")

        rule.runOnIdle {
            Pocket.sessions.value = listOf(SessionSummary(sessionId, oldTitle, "/toeic", false, false, 1, null))
            Pocket.chat.value = ChatState(sessionId, title = "旧快照标题", agentPreset = "cordis", loaded = false)
        }
        assertSyncing()
        rule.onNodeWithText("上一会话").assertDoesNotExist()
        rule.onNodeWithTag("composerInput").assertTextEquals("")
        rule.onNodeWithTag("composerInput").performTextInput("当前会话的新草稿")
        rule.onNodeWithTag("composerSend").assertIsNotEnabled()

        rule.runOnIdle {
            Pocket.model.value = Pocket.model.value + (sessionId to auto)
            Pocket.permission.value = Pocket.permission.value + (sessionId to "auto")
            Pocket.chat.value = ChatState(sessionId, title = title, agentPreset = "standard", loaded = true)
        }
        rule.onNodeWithTag("sessionTitle").assertTextEquals(title)
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("Auto")
        rule.onNodeWithTag("composerPermission").assertContentDescriptionEquals("自动审查").assertIsEnabled()
        rule.onNodeWithTag("composerInput").assertTextContains("当前会话的新草稿")
        rule.onNodeWithTag("composerSend").assertIsEnabled()
    }

    @Test fun welcomeKeepsHostDefaultAndLocallySelectedModelPresetAndTextDraft() {
        show(pendingPreset = "cordis")
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals(flash.name)
        rule.onNodeWithTag("composerModel").assertIsEnabled()
        rule.onNodeWithTag("composerPermission").assertContentDescriptionEquals("工作区内修改").assertIsEnabled()
        rule.onNodeWithText("创造模式").assertIsDisplayed()
        rule.onNodeWithText("同步会话…").assertDoesNotExist()
        rule.onNodeWithTag("composerInput").performTextInput("新会话的本地草稿")
        rule.onNodeWithTag("composerSend").assertIsEnabled()
        rule.onNodeWithTag("composerModel").performClick()
        rule.onNodeWithText("模型").performClick()
        rule.onNodeWithText("Auto").performClick()
        rule.onNodeWithTag("modelPicker").assertDoesNotExist()
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("Auto")
        rule.onNodeWithTag("composerInput").assertTextContains("新会话的本地草稿")
        rule.onNodeWithText("创造模式").assertIsDisplayed()
        rule.onNodeWithTag("composerSend").assertIsEnabled()
        rule.runOnIdle {
            assertNull(Pocket.chat.value)
            assertEquals(emptyMap(), Pocket.model.value)
            assertEquals("dfmodel", Pocket.catalog.value?.defaultModel)
        }
    }

    @Test fun loadedConversationWithUnknownModelDoesNotBorrowGlobalDefault() {
        opening()
        show()
        assertSyncing()
        rule.runOnIdle {
            Pocket.model.value = emptyMap()
            Pocket.permission.value = mapOf(sessionId to "auto")
            Pocket.chat.value = ChatState(sessionId, title = title, loaded = true)
        }
        rule.onNodeWithTag("sessionTitle").assertTextEquals(title)
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("选择模型")
        rule.onNodeWithTag("composerModel").assertContentDescriptionEquals("选择模型").assertIsEnabled()
        rule.onNodeWithText(flash.name).assertDoesNotExist()
        rule.onNodeWithText("Off", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("modelReleaseBadge", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun englishOpeningUsesSyncingLabelsInsteadOfCachedMetadata() {
        opening()
        Pocket.prefs.language = AppLanguage.En
        show()
        rule.onNodeWithText("Syncing conversation…").assertIsDisplayed()
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("Syncing…")
        rule.onNodeWithTag("composerModel").assertContentDescriptionEquals("Syncing…").assertIsNotEnabled()
        rule.onNodeWithTag("composerPermission").assertIsNotEnabled()
        rule.onNodeWithTag("composerInput").performTextInput("Keep this draft")
        rule.onNodeWithTag("composerSend").assertIsNotEnabled()
        rule.onNodeWithText(flash.name).assertDoesNotExist()
        rule.onNodeWithText(oldTitle).assertDoesNotExist()
        rule.onNodeWithText("Off", useUnmergedTree = true).assertDoesNotExist()
    }
}

/** Owns every process-wide flow and uses isolated preferences without rebinding another test's Host. */
private class ChatOpeningFixture {
    private fun field(name: String) = Pocket.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private val oldPrefs = Pocket.prefs
    private val oldClient = field("client").get(Pocket)
    @Suppress("UNCHECKED_CAST")
    private val flows = Pocket.javaClass.declaredFields.mapNotNull { field ->
        field.isAccessible = true
        val flow = field.get(Pocket) as? MutableStateFlow<*> ?: return@mapNotNull null
        (flow as MutableStateFlow<Any?>) to flow.value
    }
    private val client = PocketClient()
    private val isolatedContext = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        private val prefix = "chat-opening-${UUID.randomUUID()}-"
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
    }
    private val prefs = AppPrefs(isolatedContext)
    private val catalog = ModelCatalog.parse(PocketJson.parseToJsonElement(OPENING_CATALOG_JSON))

    @Suppress("UNCHECKED_CAST")
    private fun <T> setFlow(name: String, value: T) {
        (field(name).get(Pocket) as MutableStateFlow<T>).value = value
    }

    init {
        field("prefs").set(Pocket, prefs)
        installHostSettingsFixture(prefs)
        prefs.appearance = Appearance.Light
        prefs.defaultPermission = "workspace-write"
        val onFrame = client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) {
                val request = PocketJson.parseToJsonElement(text)
                check(request["op"].str() == "models.catalog")
                onFrame.invoke(client, """{"id":${request["id"]},"ok":true,"result":$OPENING_CATALOG_JSON}""")
            }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        field("client").set(Pocket, client)
        Pocket.profiles.value = listOf(Profile("opening-host", "Mac mini", host = "127.0.0.1", port = 7690))
        Pocket.active.value = "opening-host"
        Pocket.status.value = mapOf("opening-host" to Dot.Online)
        Pocket.sessions.value = emptyList()
        Pocket.chat.value = null
        Pocket.model.value = emptyMap()
        Pocket.catalog.value = catalog
        Pocket.permission.value = emptyMap()
        val options = listOf(PermissionOption("read-only", "read-only"),
            PermissionOption("workspace-write", "workspace-write"), PermissionOption("auto", "auto"))
        Pocket.permissionCatalog.value = PermissionCatalog(options, options.take(2), "workspace-write")
        Pocket.permissionCatalogLoading.value = false
        Pocket.permissionCatalogError.value = null
        Pocket.permissionChanging.value = false
        Pocket.presetRoster.value = listOf(jsonOf("id" to "standard", "isDefault" to true),
            jsonOf("id" to "cordis", "isDefault" to false))
        Pocket.ops.value = setOf("models.catalog", "models.select", "permissions.catalog", "permissions.set", "presets.list")
        Pocket.workspaces.value = emptyList()
        Pocket.draftWorkspacePath.value = null
        Pocket.approvals.value = emptyList()
        Pocket.userQuestions.value = emptyList()
        Pocket.feedback.value = emptyMap()
        Pocket.compatibility.value = null
        setFlow("_firstPrompt", null)
        setFlow("_team", null)
        setFlow("_subagents", null)
        setFlow("_catalogLoading", false)
        setFlow("_catalogError", null)
        setFlow("_modelPending", null)
    }

    fun close() {
        client.close()
        prefs.hostSettings.clear()
        field("client").set(Pocket, oldClient)
        field("prefs").set(Pocket, oldPrefs)
        flows.forEach { (flow, value) -> flow.value = value }
    }
}

private const val OPENING_CATALOG_JSON = """{
  "default":{"provider":"qoder-cn","model":"dfmodel","effort":"off"},
  "groups":[{"id":"qoder-cn","name":"Qoder CN","models":[
    {"id":"dfmodel","name":"DeepSeek-V4.1-Flash","reasoning":{"defaultEffort":"off","efforts":[{"id":"off","name":"Off"}]}},
    {"id":"auto","name":"Auto","reasoning":{"defaultEffort":"off","efforts":[{"id":"off","name":"Off"}]}}
  ]}]
}"""
