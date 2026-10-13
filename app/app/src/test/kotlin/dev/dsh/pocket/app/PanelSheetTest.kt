package dev.dsh.pocket.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PanelSheetTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.appearance = Appearance.Light
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.ops.value = setOf("files.list", "terminal.create", "terminal.follow", "jobs.list")
    }

    @Test fun filePageReturnsToGuideWithoutClosingPanel() {
        rule.setContent {
            PocketTheme {
                var page by remember { mutableStateOf("guide") }
                when (page) {
                    "guide" -> PanelSheet(onDismiss = { page = "closed" }, onFiles = { page = "files" }, onTerminal = {}, onBrowser = {}, onInspector = {})
                    "files" -> FilesSheet("no-connection", onDismiss = { page = "closed" }, onBack = { page = "guide" })
                }
            }
        }
        rule.onNodeWithText("工作区文件").performClick()
        rule.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
        rule.onNodeWithText("终端").assertIsDisplayed()
        rule.onNodeWithContentDescription("收起右侧边栏").performClick()
        rule.onNodeWithTag("rightPanel").assertDoesNotExist()
    }

    @Test fun capabilitiesUpdateWhileGuideIsOpen() {
        Pocket.ops.value = setOf("terminal.create", "jobs.list")
        rule.setContent { PocketTheme { PanelSheet(onDismiss = {}, onFiles = {}, onTerminal = {}, onBrowser = {}, onInspector = {}) } }
        rule.onNodeWithText("终端").assertDoesNotExist()
        rule.onNodeWithText("后台任务").assertDoesNotExist()
        rule.runOnIdle { Pocket.ops.value = setOf("files.list", "terminal.create", "terminal.follow") }
        rule.onNodeWithText("工作区文件").assertIsEnabled()
        rule.onNodeWithText("终端").assertIsEnabled()
        rule.runOnIdle { Pocket.ops.value = emptySet() }
        rule.onNodeWithText("工作区文件").assertDoesNotExist()
        rule.onNodeWithText("终端").assertDoesNotExist()
        rule.onNodeWithText("浏览器").assertIsEnabled()
        rule.onNodeWithText("会话数据诊断").assertIsEnabled()
    }

    @Test fun guideAllowsAllAvailableToolsBeforeSelectingSession() {
        Pocket.chat.value = null
        rule.setContent {
            PocketTheme { PanelSheet(onDismiss = {}, onFiles = {}, onTerminal = {}, onBrowser = {}, onInspector = {}) }
        }
        rule.onNodeWithText("工作区文件").assertIsEnabled()
        rule.onNodeWithText("终端").assertIsEnabled()
        rule.onNodeWithText("会话数据诊断").assertIsEnabled()
        rule.onNodeWithText("浏览器").assertIsEnabled()
    }
}
