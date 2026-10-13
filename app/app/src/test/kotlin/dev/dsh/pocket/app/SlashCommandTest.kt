package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
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
        Pocket.profiles.value = listOf(Profile("p1", "desk", host = "100.106.128.87", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.catalog.value = null
        Pocket.ops.value = setOf("commands.execute", "session.export", "permissions.get", "models.catalog")
        Pocket.sessions.value = emptyList()
        Pocket.chat.value = ChatState("s1", title = "Greeting", running = false, loaded = true)
        Pocket.approvals.value = emptyList()
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

    @Test fun slashOpensCommandPopupWithDesktopRows() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("composerInput").performTextInput("/")
        rule.waitForIdle()
        rule.onNodeWithText("文件").assertIsDisplayed()
        rule.onNodeWithText("目标").assertIsDisplayed()
        rule.onNodeWithText("压缩").assertIsDisplayed()
        rule.onNodeWithText("权限").assertIsDisplayed()
        rule.onNodeWithText("模型").assertIsDisplayed()
        rule.onNodeWithText("下载日志").assertIsDisplayed()
    }

    @Test fun pickingPermissionOpensPicker() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("composerInput").performTextInput("/")
        rule.waitForIdle()
        rule.onNodeWithText("权限").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("访问模式").assertIsDisplayed()
    }

    @Test fun chatTopBarShowsTitleWithoutProfileName() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Greeting").assertIsDisplayed()
        // Profile name "desk" is drawer-only; must not appear in the chat top bar.
        rule.onNodeWithText("desk").assertDoesNotExist()
    }
}
