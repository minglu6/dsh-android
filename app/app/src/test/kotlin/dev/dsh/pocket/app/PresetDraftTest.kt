package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PresetDraftTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.active.value = "preset-host"
        Pocket.status.value = mapOf("preset-host" to Dot.Online)
        Pocket.ops.value = emptySet()
        Pocket.chat.value = null
        Pocket.sessions.value = emptyList()
        Pocket.approvals.value = emptyList()
        Pocket.userQuestions.value = emptyList()
        Pocket.catalog.value = null
        Pocket.presetRoster.value = listOf(
            jsonOf("id" to "standard", "isDefault" to true),
            jsonOf("id" to "cordis", "isDefault" to false),
        )
    }

    @Test fun creatorEntryStagesOnlyThisDraftWithoutChangingDefaultOrSending() {
        val request = mutableStateOf<String?>("cordis")
        val defaults = Pocket.presetRoster.value
        rule.setContent { PocketTheme {
            ChatRoot(onMenu = {}, pendingPreset = request.value, onPresetConsumed = { request.value = null })
        } }
        rule.onNodeWithText("创造模式").assertIsDisplayed()
        rule.onNodeWithTag("composerInput").assertTextEquals("")
        rule.onNodeWithTag("composerSend").assertIsNotEnabled()
        assertNull(Pocket.chat.value)
        assertTrue(Pocket.sessions.value.isEmpty())
        assertEquals(defaults, Pocket.presetRoster.value)
        rule.runOnIdle {
            Pocket.presetRoster.value = defaults + jsonOf("id" to "custom", "name" to "Custom", "isDefault" to false)
        }
        rule.onNodeWithText("创造模式").assertIsDisplayed()
        rule.runOnIdle { Pocket.active.value = "other-host" }
        rule.onNodeWithText("标准模式").assertIsDisplayed()
        rule.onNodeWithText("创造模式").assertDoesNotExist()
    }
}
