package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.dsh.pocket.protocol.jsonOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class SessionInspectorPanelTest {
    @get:Rule val rule = createComposeRule()

    @Before fun reset() { Pocket.chat.value = null }
    @After fun cleanup() { Pocket.chat.value = null }

    private fun state(session: String, type: String) = ChatState(session, loaded = true,
        inspectorRecords = listOf(jsonOf("seq" to 1, "time" to 1234L, "type" to type, "data" to jsonOf("value" to "local"))))

    @Test fun absentOrDifferentSessionDoesNotRenderAndSwitchResetsFilter() {
        val requested = mutableStateOf("A")
        rule.setContent { PocketTheme { SessionInspectorSheet(requested.value, onDismiss = {}) } }
        rule.onNodeWithTag("rightPanel").assertDoesNotExist()
        rule.runOnIdle { Pocket.chat.value = state("A", "custom/first") }
        rule.onNodeWithText("custom/first").assertIsDisplayed()
        rule.onNodeWithTag("inspectorFilter").performTextInput("first")
        rule.onNodeWithTag("inspectorApplyFilter").performClick()
        rule.runOnIdle { Pocket.chat.value = state("B", "custom/second") }
        rule.onNodeWithTag("rightPanel").assertDoesNotExist()
        rule.runOnIdle { requested.value = "B" }
        rule.onNodeWithText("custom/second").assertIsDisplayed()
        rule.onNodeWithText("custom/first").assertDoesNotExist()
    }

    @Test fun confirmedFilterAppliesToSubsequentChatUpdatesAndDetailsRemainLocal() {
        Pocket.chat.value = state("A", "custom/first")
        rule.setContent { PocketTheme { SessionInspectorSheet("A", onDismiss = {}) } }
        rule.onNodeWithTag("inspectorFilter").performTextInput("DELTA")
        rule.onNodeWithText("custom/first").assertIsDisplayed() // draft is not the active filter
        rule.onNodeWithTag("inspectorApplyFilter").performClick()
        rule.onNodeWithText("custom/first").assertDoesNotExist()
        rule.runOnIdle { Pocket.chat.value = state("A", "custom/delta") }
        rule.onNodeWithText("custom/delta").assertIsDisplayed().performClick()
        rule.onNodeWithTag("inspectorDetail").assertIsDisplayed()
        rule.onNodeWithTag("inspectorCopy").assertIsDisplayed()
        rule.onNodeWithTag("inspectorLoadEarlier").assertIsNotEnabled()
    }
}
