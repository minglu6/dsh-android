package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import dev.dsh.pocket.protocol.FrameTransport
import dev.dsh.pocket.protocol.PocketClient
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.Route
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
 * Slash menu must consume the typed `/` and wire each row like desktop / Plus sheet.
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

    @Test fun slashSearchUsesHostCatalogAndInsertsSelectedCommand() = withHostCatalog {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("composerInput").performTextInput("/")
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("/inspect-worktree").performScrollTo().assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("搜索指令")).performTextInput("inspect")
        rule.waitForIdle()
        rule.onNodeWithText("/access-team").assertDoesNotExist()
        rule.onNodeWithText("/inspect-worktree").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("composerInput").assertTextContains("/inspect-worktree", substring = true)
    }

    @Test fun renamedOfficialPermissionCommandOpensLivePicker() = withHostCatalog {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("composerInput").performTextInput("/")
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("/access-team").performScrollTo().performClick()
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("Team policy").assertIsDisplayed()
        rule.onNodeWithText("/access-team").assertDoesNotExist()
    }

    private fun withHostCatalog(block: () -> Unit) {
        val client = PocketClient()
        val onFrame = client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
        val commands = """{"commands":[
            {"name":"access-team","description":"Choose the workspace policy","definitionId":"@deepseek-ai/dsh-permission-presets"},
            {"name":"inspect-worktree","description":"Inspect repository changes","definitionId":"team/worktree"}
        ]}"""
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
