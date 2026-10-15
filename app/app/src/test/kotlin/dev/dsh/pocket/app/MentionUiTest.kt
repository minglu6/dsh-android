package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextRange
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class MentionUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var client: PocketClient
    private lateinit var restore: () -> Unit
    private lateinit var clipboard: ClipboardManager
    private val requests = ConcurrentLinkedQueue<JsonObject>()
    private fun field(name: String) = Pocket.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String): Any? = field(name).get(Pocket)
    private fun put(name: String, value: Any?) = field(name).set(Pocket, value)

    @Suppress("UNCHECKED_CAST")
    @Before fun setUp() {
        Pocket.init(RuntimeEnvironment.getApplication())
        val fields = listOf("client", "subId", "pendingFollowEpoch", "reducer", "inspectorLog",
            "permissionProjectionSeq", "modelProjectionSeq", "modelUsesDefault", "firstPromptSessionId",
            "continuityRepairAttempts", "desiredViewedSession", "completionReadySession",
            "completionViewClient", "completionViewSession").associateWith(::get)
        val flows = listOf("chat", "sessions", "active", "status", "ops", "workspaces", "presetRoster",
            "permission", "model", "catalog", "feedback", "approvals", "userQuestions", "_firstPrompt",
            "permissionCatalog", "permissionCatalogLoading").associateWith { (get(it) as MutableStateFlow<Any?>).value }
        val epoch = get("followEpoch") as AtomicLong
        val oldEpoch = epoch.get()
        val attempts = get("followAttempts") as MutableMap<String, Int>
        val oldAttempts = attempts.toMap()
        val stray = get("stray") as MutableMap<String, MutableList<JsonObject>>
        val oldStray = stray.toMap()
        restore = {
            fields.forEach { (key, value) -> put(key, value) }
            flows.forEach { (key, value) -> (get(key) as MutableStateFlow<Any?>).value = value }
            epoch.set(oldEpoch)
            attempts.clear(); attempts.putAll(oldAttempts)
            stray.clear(); stray.putAll(oldStray)
        }
        installHostSettingsFixture()
        client = PocketClient()
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) { requests.add(PocketJson.parseToJsonElement(text).jsonObject) }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        put("client", client)
        put("subId", null)
        put("pendingFollowEpoch", null)
        put("firstPromptSessionId", null)
        (get("_firstPrompt") as MutableStateFlow<FirstPrompt?>).value = null
        Pocket.chat.value = null
        Pocket.sessions.value = emptyList()
        Pocket.active.value = "mention-host"
        Pocket.status.value = mapOf("mention-host" to Dot.Online)
        Pocket.ops.value = setOf("references.files", "references.sessions")
        Pocket.workspaces.value = listOf(WorkspaceView("workspace", "/selected-project", "Selected project", emptyList(), null, null))
        Pocket.presetRoster.value = emptyList()
        Pocket.permission.value = emptyMap()
        Pocket.model.value = emptyMap()
        Pocket.catalog.value = null
        Pocket.approvals.value = emptyList()
        Pocket.userQuestions.value = emptyList()
        Pocket.permissionCatalogLoading.value = false
    }

    @After fun tearDown() {
        (get("transcriptPublisher") as TranscriptPublisher).cancel()
        client.close()
        restore()
    }

    private fun request(op: String): JsonObject {
        rule.waitForIdle()
        rule.waitUntil(5000) { requests.any { it["op"].str() == op } }
        return requests.first { it["op"].str() == op }.also { requests.remove(it) }
    }

    private fun reply(request: JsonObject, result: String) = rule.runOnIdle {
        client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
            .invoke(client, """{"id":${request["id"]},"ok":true,"result":$result}""")
    }

    private fun snapshot() = rule.runOnIdle {
        val frame = PocketJson.parseToJsonElement("""{"ev":"follow","subId":"mention-sub","frame":{"type":"snapshot","cursor":0,"records":[],"projections":{"asOfSeq":0,"values":{}}}}""").jsonObject
        Pocket.javaClass.getDeclaredMethod("onPush", JsonObject::class.java).apply { isAccessible = true }.invoke(Pocket, frame)
    }

    private fun candidates(sessionId: String) {
        val files = request("references.files")
        val sessions = request("references.sessions")
        assertEquals(sessionId, files["args"]["sessionId"].str())
        assertEquals(sessionId, sessions["args"]["sessionId"].str())
        reply(files, """{"items":[{"path":"src/Main.kt","kind":"file"}]}""")
        reply(sessions, """{"items":[{"mention":"@[设计讨论](dsh-session:Im90aGVyIg)","title":"设计讨论","kind":"session"}]}""")
        rule.onNodeWithText("Main.kt").assertIsDisplayed()
        rule.onNodeWithText("设计讨论").assertIsDisplayed()
    }

    private fun showChat() {
        rule.setContent { PocketTheme {
            clipboard = LocalClipboardManager.current
            ChatRoot(onMenu = {})
        } }
    }

    private fun assertCanonicalDraft(expected: String) {
        val input = rule.onNodeWithTag("composerInput")
        input.performTextInputSelection(TextRange(0, expected.length))
        input.performSemanticsAction(SemanticsActions.CopyText) { it() }
        rule.runOnIdle { assertEquals(expected, clipboard.getText()?.text) }
        input.performTextInputSelection(TextRange(expected.length))
    }

    @Test fun firstMentionCreatesBlankSessionWaitsForHistoryAndPreservesDraft() {
        showChat()
        rule.onNodeWithTag("composerInput").performTextInput("请检查 @")
        val create = request("sessions.create")
        assertEquals("workspace", create["args"]["workspaceId"].str())
        rule.onNodeWithTag("mentionMenu").assertIsDisplayed()
        rule.onNodeWithTag("composerInput").assertIsFocused().performTextInput("Ma")
        rule.onNodeWithText("请先打开会话").assertDoesNotExist()
        assertFalse(requests.any { it["op"].str()?.startsWith("references.") == true })
        reply(create, """{"sessionId":"mention-session"}""")
        val follow = request("follow")
        reply(follow, """{"subId":"mention-sub"}""")
        rule.waitForIdle()
        assertFalse(requests.any { it["op"].str()?.startsWith("references.") == true })
        snapshot()
        candidates("mention-session")
        rule.onNodeWithText("Main.kt").performClick()
        assertCanonicalDraft("请检查 @src/Main.kt ")
        assertNull(Pocket.firstPrompt.value)
        assertTrue(Pocket.chat.value!!.items.isEmpty())
        assertFalse(requests.any { it["op"].str() in setOf("sessions.create", "prompt") })
    }

    @Test fun firstPlusMenuCreatesRealBlankSessionAndPreservesDraft() {
        assertBlankCommandMenuPreparesSession(slash = false)
    }

    @Test fun firstSlashMenuCreatesRealBlankSessionAndPreservesTrigger() {
        assertBlankCommandMenuPreparesSession(slash = true)
    }

    private fun assertBlankCommandMenuPreparesSession(slash: Boolean) {
        Pocket.ops.value = setOf("sessions.create", "commands.list", "commands.execute")
        val draft = if (slash) "/ins" else "保留尚未发送的草稿"
        showChat()
        rule.onNodeWithTag("composerInput").performTextInput(draft)
        if (!slash) rule.onNodeWithContentDescription(L.sectionAdd()).performClick()
        val create = request("sessions.create")
        assertEquals("workspace", create["args"]["workspaceId"].str())
        assertFalse(requests.any { it["op"].str() in setOf("commands.list", "prompt", "commands.execute") })
        reply(create, """{"sessionId":"command-session"}""")
        reply(request("follow"), """{"subId":"mention-sub"}""")
        rule.waitForIdle()
        assertFalse(requests.any { it["op"].str() == "commands.list" })
        snapshot()
        val catalog = request("commands.list")
        assertEquals("command-session", catalog["args"]["sessionId"].str())
        reply(catalog, """{"commands":[{"name":"inspect-host","description":"Host command"}]}""")
        rule.onNodeWithText("inspect-host").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        assertCanonicalDraft(draft)
        assertEquals("command-session", Pocket.chat.value?.sessionId)
        assertTrue(Pocket.chat.value!!.loaded)
        assertTrue(Pocket.chat.value!!.items.isEmpty())
        assertNull(Pocket.firstPrompt.value)
        assertFalse(requests.any { it["op"].str() in setOf("sessions.create", "prompt", "commands.execute") })
    }

    @Test fun existingSessionSearchDoesNotCreateAnotherConversation() {
        Pocket.chat.value = ChatState("existing", loaded = true)
        showChat()
        rule.onNodeWithTag("composerInput").performTextInput("关联 @")
        candidates("existing")
        rule.onNodeWithText("设计讨论").performClick()
        assertCanonicalDraft("关联 @[设计讨论](dsh-session:Im90aGVyIg) ")
        rule.onNodeWithTag("composerInput").assertIsFocused()
        rule.onNodeWithTag("mentionMenu").assertDoesNotExist()
        assertFalse(requests.any { it["op"].str() == "sessions.create" })
    }

    @Test fun tabDrillsQuotedDirectoryBreadcrumbReturnsAndRowPickInsertsFolder() {
        Pocket.chat.value = ChatState("existing", loaded = true)
        showChat()
        rule.onNodeWithTag("composerInput").performTextInput("参考 @")
        reply(request("references.files"), """{"items":[{"path":"design docs","kind":"directory"}]}""")
        val failed = request("references.sessions")
        rule.runOnIdle {
            client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
                .invoke(client, """{"id":${failed["id"]},"ok":false,"error":{"code":"unavailable","message":"session references unavailable"}}""")
        }
        rule.onNodeWithText("design docs/").assertIsDisplayed()
        rule.onNodeWithTag("composerInput").performKeyInput { pressKey(Key.Tab) }
        val nested = request("references.files")
        assertEquals("design docs/", nested["args"]["query"].str())
        assertFalse(requests.any { it["op"].str() == "references.sessions" })
        reply(nested, """{"items":[{"path":"design docs/notes.md","kind":"file"}]}""")
        rule.onNodeWithTag("composerInput").assertIsFocused().assertTextEquals("参考 @\"design docs/")
        rule.onNodeWithText("notes.md").assertIsDisplayed()
        rule.onNodeWithText("工作区").performClick()
        val root = request("references.files")
        assertEquals("", root["args"]["query"].str())
        reply(root, """{"items":[{"path":"design docs","kind":"directory"}]}""")
        rule.onNodeWithText("design docs/").performClick()
        assertCanonicalDraft("参考 @\"design docs/\" ")
        rule.onNodeWithTag("composerInput").assertIsFocused()
        rule.onNodeWithTag("mentionMenu").assertDoesNotExist()
        assertFalse(requests.any { it["op"].str() in setOf("sessions.create", "prompt", "references.sessions") })
    }

    @Test fun queryRefinementCannotPickStaleRowsAndShiftTabDismissesWithoutChangingDraft() {
        Pocket.chat.value = ChatState("existing", loaded = true)
        showChat()
        val input = rule.onNodeWithTag("composerInput")
        input.performTextInput("检索 @")
        candidates("existing")
        input.performTouchInput { click(centerRight) }
        rule.onNodeWithTag("mentionMenu").assertIsDisplayed()
        input.performTextInput("Ma")
        val files = request("references.files")
        val sessions = request("references.sessions")
        rule.onNodeWithText("Main.kt").assertIsDisplayed()
        input.performKeyInput { pressKey(Key.Enter) }
        input.assertTextEquals("检索 @Ma")
        rule.onNodeWithTag("mentionMenu").assertIsDisplayed()
        reply(files, """{"items":[{"path":"src/Main2.kt","kind":"file"}]}""")
        reply(sessions, """{"items":[]}""")
        rule.onNodeWithText("Main2.kt").assertIsDisplayed()
        input.performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Tab)
            keyUp(Key.ShiftLeft)
        }
        input.assertIsFocused().assertTextEquals("检索 @Ma")
        rule.onNodeWithTag("mentionMenu").assertDoesNotExist()
        input.performTextInput("i")
        rule.onNodeWithTag("mentionMenu").assertIsDisplayed()
        rule.onNodeWithText("开始对话").performTouchInput { click() }
        rule.onNodeWithTag("mentionMenu").assertDoesNotExist()
        input.assertTextEquals("检索 @Mai")
    }
}
