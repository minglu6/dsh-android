package dev.dsh.pocket.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dsh.pocket.protocol.WorkspaceView
import dev.dsh.pocket.protocol.jsonOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real popup-to-dialog window transition; Robolectric cannot settle this dialog. */
@RunWith(AndroidJUnit4::class)
class WorkspaceDisplayInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun workspaceActionsKeepCollapseStateAndDiscardCancelledEdits() {
        Pocket.init(InstrumentationRegistry.getInstrumentation().targetContext)
        Pocket.prefs.hostSettings.bind({
            jsonOf("writable" to false, "namespaces" to listOf(
                jsonOf("ns" to "locale", "revision" to 0, "value" to jsonOf("preference" to "zh")),
                jsonOf("ns" to "ui-theme", "revision" to 0, "value" to jsonOf("preference" to "light")),
            ))
        }, { _, _, _ -> error("This display test must not mutate Host settings") })
        val workspace = WorkspaceView("default", """C:\Users\luming2\Documents\deepseek-harness\default-workspace""",
            "default-workspace", emptyList(), null, null)
        Pocket.workspaces.value = listOf(workspace)
        Pocket.chat.value = null
        Pocket.draftWorkspacePath.value = workspace.path
        Pocket.ops.value = setOf("workspace.rename", "workspace.delete")
        var toggles = 0
        rule.setContent { PocketTheme { Column(Modifier.fillMaxSize()) {
            WorkspaceNavigationGroups(listOf(workspace), emptyList(), false, emptySet(), { toggles++ }, {}) {}
        } } }
        rule.onNodeWithText("默认工作区").assertIsDisplayed()
        rule.onNodeWithTag("workspaceNewSession:default").assertIsDisplayed()
        rule.onNodeWithTag("workspaceMenu:default").performClick()
        rule.onNodeWithText(L.rename()).performClick()
        rule.onNode(hasSetTextAction()).assertTextEquals("default-workspace")
        rule.onNode(hasSetTextAction()).performTextReplacement("Cancelled edit")
        rule.onNodeWithText(L.cancel()).performClick()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.onNodeWithText("默认工作区").assertIsDisplayed()
        assertEquals("default-workspace", Pocket.workspaces.value.single().title)
        rule.onNodeWithTag("workspaceMenu:default").performClick()
        rule.onNodeWithText(L.rename()).performClick()
        rule.onNode(hasSetTextAction()).assertTextEquals(workspace.title)
        rule.onNodeWithText(L.cancel()).performClick()
        rule.onNodeWithTag("workspaceMenu:default").performClick()
        rule.onNodeWithText("删除工作区").performClick()
        rule.onNodeWithText(L.cancel()).performClick()
        rule.onNodeWithTag("workspaceNewSession:default").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(0, toggles)
            assertEquals(listOf(workspace), Pocket.workspaces.value)
        }
    }
}
