package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import dev.dsh.pocket.protocol.WorkspaceView
import dev.dsh.pocket.protocol.jsonOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class WorkspaceDisplayTest {
    @get:Rule val rule = createComposeRule()
    private val windowsPath = """C:\Users\luming2\Documents\deepseek-harness\default-workspace"""
    private val default = WorkspaceView("default", windowsPath, "default-workspace", emptyList(), null, null)
    private val custom = WorkspaceView("custom", """D:\projects\default-workspace""", "我的工程", emptyList(), null, null)

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
        Pocket.active.value = "workspace-display"
        Pocket.status.value = mapOf("workspace-display" to Dot.Online)
        Pocket.ops.value = setOf("workspace.rename")
        Pocket.chat.value = null
        Pocket.sessions.value = emptyList()
        Pocket.approvals.value = emptyList()
        Pocket.userQuestions.value = emptyList()
        Pocket.catalog.value = null
        Pocket.presetRoster.value = emptyList()
        Pocket.workspaces.value = listOf(default, custom)
        Pocket.draftWorkspacePath.value = null
    }

    @Test fun composerUsesRegisteredTitleAndPickerRetainsExactHostPathAcrossLocaleChanges() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithText("默认工作区").assertIsDisplayed()
        rule.onNodeWithText(windowsPath).assertDoesNotExist()
        rule.onRoot().captureRoboImage("build/screenshots/workspace-windows-composer.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        rule.onNodeWithText("默认工作区").performClick()
        rule.onNodeWithText(custom.title).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(custom.path, Pocket.draftWorkspacePath.value) }
        rule.onNodeWithText(custom.title).assertIsDisplayed()
        rule.runOnIdle { Pocket.prefs.language = AppLanguage.En }
        rule.onNodeWithText(custom.title).assertIsDisplayed()
        rule.runOnIdle { Pocket.draftWorkspacePath.value = windowsPath }
        rule.onNodeWithText("Default workspace").assertIsDisplayed()
        rule.runOnIdle {
            Pocket.workspaces.value = listOf(default.copy(title = "Windows 项目"), custom)
        }
        rule.onNodeWithText("Windows 项目").assertIsDisplayed()
        rule.runOnIdle {
            val untitled = WorkspaceView.parse(jsonOf("workspaceId" to "untitled", "path" to """\\server\share\project"""))!!
            Pocket.workspaces.value = listOf(untitled)
            Pocket.draftWorkspacePath.value = untitled.path
        }
        rule.onNodeWithText("project").assertIsDisplayed()
    }

    @Test fun sidebarTracksLanguageAndHostTitleChanges() {
        Pocket.workspaces.value = listOf(default)
        Pocket.prefs.sessionGroupBy = SessionGroupBy.Workspace
        Pocket.prefs.setCollapsedWorkspaces("workspace-display", emptySet())
        rule.setContent { PocketTheme { SessionDrawer({}, {}, {}, {}) } }
        rule.onNodeWithText("默认工作区").assertIsDisplayed()
        rule.onNodeWithText(windowsPath).assertIsDisplayed()
        rule.onRoot().captureRoboImage("build/screenshots/workspace-windows-sidebar.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        rule.runOnIdle { Pocket.prefs.language = AppLanguage.En }
        rule.onNodeWithText("Default workspace").assertIsDisplayed()
        rule.runOnIdle { Pocket.workspaces.value = listOf(default.copy(title = custom.title)) }
        rule.onNodeWithText(custom.title).assertIsDisplayed()
        rule.onNodeWithText("Default workspace").assertDoesNotExist()
    }
}
