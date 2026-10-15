package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import dev.dsh.pocket.protocol.jsonOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h792dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class PluginInventoryInteractionTest {
    @get:Rule val rule: ComposeContentTestRule = createComposeRule()

    @Test fun selectorStaysBesideGroupAndChangesOnlyInspectedPresetInLightTheme() = checkSelector(Appearance.Light)
    @Test fun selectorStaysBesideGroupAndChangesOnlyInspectedPresetInDarkTheme() = checkSelector(Appearance.Dark)

    private fun checkSelector(appearance: Appearance) {
        installHostSettingsFixture()
        val previous = Pocket.prefs.appearance
        val previousLanguage = Pocket.prefs.language
        try {
            Pocket.prefs.appearance = appearance
            Pocket.prefs.language = AppLanguage.Zh
            val snapshot = jsonOf("entries" to emptyList<Any>(), "agentPresets" to listOf(
                jsonOf("id" to "standard", "isDefault" to true, "rows" to listOf(
                    jsonOf("moduleName" to "standard-tool", "entryId" to "standard-tool", "enabled" to true, "fiberPhase" to "active"),
                )),
                jsonOf("id" to "ptc", "isDefault" to false, "rows" to listOf(
                    jsonOf("moduleName" to "ptc-tool", "entryId" to "ptc-tool", "enabled" to true, "fiberPhase" to "active"),
                )),
            ))
            rule.setContent { PocketTheme { Column(Modifier.fillMaxSize().background(T.Bg)) { PluginInventoryContent(snapshot) } } }
            val group = rule.onNodeWithText("会话插件").fetchSemanticsNode().boundsInRoot
            val selector = rule.onNodeWithTag("plugins.inventory.preset").fetchSemanticsNode().boundsInRoot
            assertEquals("selector and group title share their header row", group.center.y, selector.center.y, 1f)
            assertTrue("selector remains to the right without overlapping the title", selector.left >= group.right)
            rule.onRoot().captureRoboImage("build/screenshots/plugin-inventory-${appearance.name.lowercase()}.png")
            rule.onNodeWithText("会话插件").performClick()
            rule.onNodeWithText("standard-tool").assertDoesNotExist()
            rule.onNodeWithTag("plugins.inventory.preset").assertIsDisplayed().performClick()
            rule.onNodeWithText("PTC 模式").performClick()
            rule.onNodeWithText("ptc-tool").assertDoesNotExist()
            rule.onNodeWithText("会话插件").performClick()
            rule.onNodeWithText("ptc-tool").assertIsDisplayed()
            rule.onNodeWithTag("plugins.inventory.preset").performClick()
            rule.onNodeWithText("标准模式（默认）").performClick()
            rule.onNodeWithText("standard-tool").assertIsDisplayed()
            rule.onNodeWithTag("plugins.inventory.search").performTextInput("missing-tool")
            rule.onNodeWithText("standard-tool").assertDoesNotExist()
            rule.onNodeWithText("没有匹配的插件。").assertIsDisplayed()
        } finally {
            Pocket.prefs.appearance = previous
            Pocket.prefs.language = previousLanguage
        }
    }
}
