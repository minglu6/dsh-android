package dev.dsh.pocket.app

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
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PresetPresentationTest {
    @get:Rule val rule = createComposeRule()

    private fun roster(defaultId: String) = listOf(
        jsonOf("id" to "standard", "name" to "团队代码模式", "isDefault" to (defaultId == "standard")),
        jsonOf("id" to "review", "name" to "项目审查", "isDefault" to (defaultId == "review")),
    )

    @Before fun setup() {
        installHostSettingsFixture()
        Pocket.active.value = "preset-test"
        Pocket.status.value = mapOf("preset-test" to Dot.Online)
        Pocket.presetRoster.value = emptyList()
        Pocket.chat.value = null
        Pocket.sessions.value = emptyList()
        Pocket.approvals.value = emptyList()
        Pocket.userQuestions.value = emptyList()
        Pocket.ops.value = emptySet()
        Pocket.catalog.value = null
    }

    @After fun cleanup() { Pocket.presetRoster.value = emptyList(); Pocket.chat.value = null }

    @Test fun missingSessionPresetDoesNotBorrowNewTaskDefault() {
        val options = roster("standard")
        assertNull(presetLabel(null, options))
        assertNull(presetLabel("", options))
        assertEquals("团队代码模式", presetLabel("standard", options))
        assertEquals("removed-custom-mode", presetLabel("removed-custom-mode", options))
    }

    @Test fun lateRosterAndDefaultChangesDoNotOverrideExplicitDraftChoice() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.runOnIdle { Pocket.presetRoster.value = roster("standard") }
        rule.onNodeWithText("团队代码模式").assertIsDisplayed()
        rule.runOnIdle { Pocket.presetRoster.value = roster("review") }
        rule.onNodeWithText("项目审查").assertIsDisplayed().performClick()
        rule.onNodeWithText("团队代码模式").performClick()
        rule.onNodeWithText("团队代码模式").assertIsDisplayed()
        rule.runOnIdle { Pocket.presetRoster.value = roster("standard") }
        rule.runOnIdle { Pocket.presetRoster.value = roster("review") }
        rule.onNodeWithText("团队代码模式").assertIsDisplayed()
        rule.onNodeWithText("项目审查").assertDoesNotExist()
    }

    @Test fun sessionHeaderWaitsForRecordedPresetAndTracksPublishedName() {
        Pocket.presetRoster.value = roster("standard")
        Pocket.chat.value = ChatState("preset-session", loaded = true)
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithContentDescription("团队代码模式").assertDoesNotExist()
        rule.runOnIdle { Pocket.chat.value = Pocket.chat.value!!.copy(agentPreset = "review") }
        rule.onNodeWithContentDescription("项目审查").assertIsDisplayed()
        rule.runOnIdle { Pocket.presetRoster.value = listOf(jsonOf("id" to "review", "name" to "审查新名称")) }
        rule.onNodeWithContentDescription("审查新名称").assertIsDisplayed()
    }

    @Test fun sessionPresetIsPassiveAndTouchCannotOpenPicker() {
        Pocket.presetRoster.value = roster("standard")
        Pocket.chat.value = ChatState("preset-session", loaded = true, agentPreset = "standard")
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        val preset = rule.onNodeWithContentDescription("团队代码模式").assertIsDisplayed().assertHasNoClickAction()
        preset.performTouchInput { click() }
        rule.onNodeWithText("Agent 预设").assertDoesNotExist()
        assertEquals("standard", Pocket.chat.value?.agentPreset)
    }
}
