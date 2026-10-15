package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotEnabled
import dev.dsh.pocket.protocol.FrameTransport
import dev.dsh.pocket.protocol.PocketClient
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.Route
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Slash typing and the + launcher share the desktop command source and action dispatch.
 * Chat top bar must not show the connection StatusDot (drawer keeps it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class SlashCommandTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        installHostSettingsFixture()
        Pocket.profiles.value = listOf(Profile("p1", "desk", host = "100.106.128.87", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.catalog.value = null
        Pocket.ops.value = setOf("commands.list", "commands.execute", "session.export", "permissions.catalog", "permissions.get", "models.catalog")
        Pocket.sessions.value = emptyList()
        Pocket.chat.value = ChatState("s1", title = "Greeting", running = false, loaded = true)
        Pocket.approvals.value = emptyList()
        Pocket.userQuestions.value = emptyList()
        Pocket.permissionCatalog.value = null
        Pocket.permission.value = mapOf("s1" to "workspace-write")
        Pocket.model.value = emptyMap()
    }

    @Test fun planChipVisibleWhenPlanModeOn() {
        Pocket.chat.value = ChatState("s1", title = "Greeting", running = false, loaded = true, planMode = true)
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("planChip").assertIsDisplayed()
    }

    @Test fun planChipHiddenWhenPlanModeOff() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("planChip").assertDoesNotExist()
    }

    @Test fun disconnectDisablesModelCommandWithoutOpeningPicker() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("composerInput").performTextInput("/")
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("模型").assertIsEnabled()
        rule.runOnIdle { Pocket.status.value = mapOf("p1" to Dot.Offline) }
        rule.onNodeWithText("模型").assertIsNotEnabled().performClick()
        rule.onNodeWithTag("modelPicker").assertDoesNotExist()
    }

    @Test fun slashSearchUsesHostCatalogAndInsertsSelectedCommand() = withHostCatalog {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("composerInput").performTextInput("/")
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("inspect-worktree").performScrollTo().assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("搜索…")).performTextInput("inspect")
        rule.waitForIdle()
        rule.onNodeWithText("access-team").assertDoesNotExist()
        rule.onNodeWithText("inspect-worktree").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("composerInput").assertTextContains("/inspect-worktree", substring = true)
    }

    @Test fun renamedOfficialPermissionCommandOpensLivePicker() = withHostCatalog {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("composerInput").performTextInput("/")
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("access-team").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("Team policy").assertIsDisplayed()
        rule.onNodeWithText("access-team").assertDoesNotExist()
    }


    @Test fun plusAndSlashExposeTheSameFullHostCatalog() = withHostCatalog {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithContentDescription(L.sectionAdd()).performClick()
        rule.onNodeWithText("模型").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("access-team").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("inspect-worktree").performScrollTo().assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithTag("composerInput").performTextInput("/")
        rule.onNodeWithText("模型").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("access-team").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("inspect-worktree").performScrollTo().assertIsDisplayed()
    }

    @Test fun plusDismissalPreservesComposerAndOfflineActionsStayDisabled() = withHostCatalog(
        commands = """{"commands":[{"name":"plan","definitionId":"@deepseek-ai/dsh-plan-mode"}]}""",
    ) {
        Pocket.ops.value += "attachments.upload"
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("composerInput").performTextInput("keep this draft")
        rule.onNodeWithContentDescription(L.sectionAdd()).performClick()
        rule.onNodeWithText("计划").assertIsDisplayed()
        rule.runOnIdle { Pocket.status.value = mapOf("p1" to Dot.Offline) }
        rule.onNodeWithText("计划").assertIsNotEnabled()
        rule.onNodeWithText(L.file()).assertIsNotEnabled()
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithTag("composerInput").assertTextContains("keep this draft")
    }
    private fun withHostCatalog(
        commands: String = """{"commands":[
            {"name":"access-team","description":"Choose the workspace policy","definitionId":"@deepseek-ai/dsh-permission-presets"},
            {"name":"inspect-worktree","description":"Inspect repository changes","definitionId":"team/worktree","input":{"hint":"arguments"}}
        ]}""",
        block: () -> Unit,
    ) {
        val client = PocketClient()
        val onFrame = client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
        val permissions = """{"options":[{"value":"team","name":"Team policy"}],"defaultOptions":[{"value":"team","name":"Team policy"}],"defaultPreset":"team"}"""
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) {
                val request = PocketJson.parseToJsonElement(text)
                val result = when (request["op"].str()) {
                    "commands.list" -> {
                        assertEquals("s1", request["args"]["sessionId"].str())
                        commands
                    }
                    "permissions.catalog" -> permissions
                    else -> error("Unexpected operation: ${request["op"]}")
                }
                onFrame.invoke(client, """{"id":${request["id"]},"ok":true,"result":$result}""")
            }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        val field = Pocket.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val previous = field.get(Pocket)
        field.set(Pocket, client)
        try { block() }
        finally { client.close(); field.set(Pocket, previous) }
    }

    @Test fun chatTopBarShowsTitleWithoutProfileName() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Greeting").assertIsDisplayed()
        // Profile name "desk" is drawer-only; must not appear in the chat top bar.
        rule.onNodeWithText("desk").assertDoesNotExist()
    }
}
