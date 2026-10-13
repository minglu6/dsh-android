package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class MarkdownInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun directoryCodeIsInertInLightTheme() = checkDirectoryCode(Appearance.Light)
    @Test fun directoryCodeIsInertInDarkTheme() = checkDirectoryCode(Appearance.Dark)

    private fun checkDirectoryCode(appearance: Appearance) {
        val previousAppearance = Pocket.prefs.appearance
        val opened = mutableListOf<String>()
        val directory = "/Users/felix/Documents"
        try {
            Pocket.prefs.appearance = appearance
            rule.setContent {
                PocketTheme {
                    CompositionLocalProvider(LocalOpenFilePreview provides { opened += it }) {
                        Box(Modifier.fillMaxWidth().background(T.Bg).padding(20.dp)) {
                            MarkdownView("我当前的工作目录是 `$directory`。有什么我可以帮助你的吗？\n\n[查看文件](src/Main.kt)")
                        }
                    }
                }
            }
            rule.onNodeWithText(directory).assertIsDisplayed().assertHasNoClickAction()
                .performTouchInput { click() }
            rule.runOnIdle { assertEquals(emptyList<String>(), opened) }
            rule.onNodeWithText("查看文件").assertHasClickAction().performClick()
            rule.runOnIdle { assertEquals(listOf("src/Main.kt"), opened) }
            rule.onRoot().captureRoboImage("build/screenshots/markdown-directory-${appearance.name.lowercase()}.png")
        } finally {
            Pocket.prefs.appearance = previousAppearance
        }
    }
}
