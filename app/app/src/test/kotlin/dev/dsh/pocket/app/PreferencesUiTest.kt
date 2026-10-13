package dev.dsh.pocket.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PreferencesUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var previousAppearance: Appearance
    private lateinit var previousLanguage: AppLanguage
    private lateinit var host: HostSettingsFixture

    @Before fun setUp() {
        Pocket.init(RuntimeEnvironment.getApplication())
        host = installHostSettingsFixture()
        previousAppearance = Pocket.prefs.appearance
        previousLanguage = Pocket.prefs.language
        Pocket.prefs.appearance = Appearance.Light
        Pocket.prefs.language = AppLanguage.Zh
    }

    @After fun restore() {
        Pocket.prefs.appearance = previousAppearance
        Pocket.prefs.language = previousLanguage
    }

    @Test fun appearanceChangesTheActiveThemeWithoutRecreatingTheScreen() {
        var renderedBackground = Color.Unspecified
        rule.setContent {
            PocketTheme {
                val background = MaterialTheme.colorScheme.background
                SideEffect { renderedBackground = background }
                GeneralSettingsScreen {}
            }
        }
        rule.runOnIdle { assertTrue(renderedBackground.luminance() > 0.8f) }
        rule.onNodeWithText("深色").performClick()
        rule.runOnIdle { assertTrue(renderedBackground.luminance() < 0.1f, "Selecting Dark must repaint the active theme immediately") }
        rule.onNodeWithText("浅色").performClick()
        rule.runOnIdle { assertTrue(renderedBackground.luminance() > 0.8f, "Selecting Light must repaint without navigation") }
    }

    @Test fun desktopChangeUpdatesThemeTokensAndOpenSettingsWithoutRemounting() {
        var renderedToken = Color.Unspecified
        rule.setContent {
            PocketTheme {
                val token = T.Bg
                SideEffect { renderedToken = token }
                GeneralSettingsScreen {}
            }
        }
        rule.runOnIdle {
            host.desktopSet(SharedSetting.Appearance, kotlinx.serialization.json.JsonPrimitive("dark"))
            host.desktopSet(SharedSetting.Language, kotlinx.serialization.json.JsonPrimitive("en"))
        }
        rule.onNodeWithText("General settings").assertExists()
        rule.runOnIdle { assertTrue(renderedToken.luminance() < 0.1f) }
    }

    @Test fun advancedFontSizesStartCollapsedAndCanBeExpandedAndCollapsed() {
        rule.setContent { PocketTheme { GeneralSettingsScreen {} } }
        rule.onNodeWithText("代码字号").assertDoesNotExist()
        rule.onNodeWithText("终端字号").assertDoesNotExist()
        rule.onNodeWithContentDescription("更多字体设置").performScrollTo().performClick()
        rule.onNodeWithText("代码字号").assertExists()
        rule.onNodeWithText("终端字号").assertExists()
        rule.onNodeWithContentDescription("更多字体设置").performScrollTo().performClick()
        rule.onNodeWithText("代码字号").assertDoesNotExist()
        rule.onNodeWithText("终端字号").assertDoesNotExist()
    }
}
