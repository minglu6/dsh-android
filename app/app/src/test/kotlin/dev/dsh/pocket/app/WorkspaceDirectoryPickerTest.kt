package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class WorkspaceDirectoryPickerTest {
    @get:Rule val rule = createComposeRule()
    private val home = WorkspaceDirectoryEntry("home", "/home", false)
    private val project = WorkspaceDirectoryEntry("project", "/home/project", false)
    private val homeLevel = WorkspaceDirectoryLevel(home.path, home.path, listOf(home), listOf(project), false)
    private val projectLevel = WorkspaceDirectoryLevel(project.path, home.path, listOf(home, project), emptyList(), false)

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
    }

    @Test fun uncommittedPathCannotOpenPreviousSelectionAndFailureKeepsDialog() {
        val opened = mutableListOf<String>()
        var dismissed = false
        rule.setContent { PocketTheme {
            WorkspaceDirectoryDialog(true, true,
                list = { if (it == project.path) projectLevel else homeLevel },
                createDirectory = { _, _ -> error("not used") },
                open = { opened += it; error("workspace refused") },
                onDismiss = { dismissed = true })
        } }
        rule.onNodeWithTag("directoryEntry:${project.path}").performClick()
        rule.onNodeWithContentDescription("编辑路径").performClick()
        rule.onNodeWithTag("directoryInput:编辑路径").performTextReplacement(project.path)
        rule.onNodeWithTag("directoryOpen").assertIsNotEnabled()
        rule.onNodeWithTag("directoryInput:编辑路径").performImeAction()
        rule.onNodeWithTag("directoryOpen").assertIsEnabled().performClick()
        rule.onNodeWithText("workspace refused").assertIsDisplayed()
        rule.onNodeWithTag("workspaceDirectoryDialog").assertIsDisplayed()
        assertEquals(listOf(project.path), opened)
        assertFalse(dismissed)
    }

    @Test fun failedFolderCreationKeepsNameAndSuccessSelectsExactCreatedPath() {
        val entries = mutableListOf(project)
        val created = WorkspaceDirectoryEntry(" new ", "/home/ new ", false)
        var refuse = true
        var opened: String? = null
        rule.setContent { PocketTheme {
            WorkspaceDirectoryDialog(true, true,
                list = { path -> when (path) {
                    created.path -> WorkspaceDirectoryLevel(created.path, home.path, listOf(home, created), emptyList(), false)
                    else -> homeLevel.copy(entries = entries.toList())
                } },
                createDirectory = { path, name ->
                    if (refuse) error("directory refused")
                    require(path == home.path && name == created.name)
                    entries += created
                    created.path
                },
                open = { opened = it }, onDismiss = {})
        } }
        rule.onNodeWithText("新建文件夹").performClick()
        rule.onNodeWithTag("directoryInput:文件夹名称").performTextInput(created.name)
        rule.onNodeWithText("创建", useUnmergedTree = true).performClick()
        rule.onNodeWithText("directory refused").assertIsDisplayed()
        rule.onNodeWithTag("directoryInput:文件夹名称").assertTextContains(created.name)
        rule.runOnIdle { refuse = false }
        rule.onNodeWithText("创建", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("directoryCrumb:${created.path}").assertIsDisplayed()
        rule.onNodeWithTag("directoryOpen").performClick()
        assertEquals(created.path, opened)
    }

    @Test fun cancellingNewFolderKeepsParentSelectionAndNeverCreatesOrOpens() {
        var created = 0
        var opened = 0
        var dismissed = false
        rule.setContent { PocketTheme {
            WorkspaceDirectoryDialog(true, true,
                list = { if (it == project.path) projectLevel else homeLevel },
                createDirectory = { _, _ -> created++; "/unexpected" },
                open = { opened++ }, onDismiss = { dismissed = true })
        } }
        rule.onNodeWithTag("directoryEntry:${project.path}").performClick()
        rule.onNodeWithText("新建文件夹").performClick()
        rule.onNodeWithTag("workspaceDirectoryDialog").assertExists()
        rule.onNodeWithTag("directoryOpen").assertIsNotEnabled()
        rule.onNodeWithTag("directoryInput:文件夹名称").performTextInput("cancelled")
        rule.onNodeWithTag("cancelCreateDirectory").performClick()
        rule.onNodeWithTag("createDirectoryDialog").assertDoesNotExist()
        rule.onNodeWithTag("directoryCrumb:${project.path}").assertIsDisplayed()
        rule.onNodeWithTag("directoryOpen").assertIsEnabled()
        assertEquals(0, created)
        assertEquals(0, opened)
        assertFalse(dismissed)
        rule.onNodeWithText("取消").performClick()
        assertTrue(dismissed)
        assertEquals(0, opened)
    }

    @Test fun nativeOnlyHostUsesTypedPathWithoutBrowsingOrCreating() {
        var opened: String? = null
        rule.setContent { PocketTheme {
            WorkspaceDirectoryDialog(false, false,
                list = { error("native Host cannot list") },
                createDirectory = { _, _ -> error("native Host cannot create") },
                open = { opened = it }, onDismiss = {})
        } }
        rule.onNodeWithText("新建文件夹").assertDoesNotExist()
        rule.onNodeWithTag("directoryInput:编辑路径").performTextInput("/home/ project ")
        rule.onNodeWithTag("directoryOpen").performClick()
        assertEquals("/home/ project ", opened)
    }
}
