package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.dsh.pocket.protocol.PocketError
import dev.dsh.pocket.protocol.jsonOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h850dp-xxhdpi")
class OfficeFontWarningTest {
    @get:Rule val rule = createComposeRule()
    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
    }
    @After fun restore() { Pocket.prefs.hostSettings.clear() }

    @Test fun warningAppearsOnlyForCurrentMissingFontsAndOpensOfficialDetails() {
        val fonts = mutableStateOf(emptyList<String>())
        rule.setContent { PocketTheme { OfficeFontWarning(fonts.value) } }
        rule.onNodeWithContentDescription("缺失 2 种字体，点击查看").assertDoesNotExist()
        rule.runOnIdle { fonts.value = listOf("Aptos", "Calibri") }
        rule.onNodeWithContentDescription("缺失 2 种字体，点击查看").performClick()
        rule.onNodeWithText("缺失的字体").assertIsDisplayed()
        rule.onNodeWithText("2 种字体").assertIsDisplayed()
        rule.onNodeWithText("Aptos").assertIsDisplayed()
        rule.onNodeWithText("Calibri").assertIsDisplayed()
        rule.onNodeWithContentDescription("关闭字体详情").performClick()
        rule.onNodeWithText("缺失的字体").assertDoesNotExist()
        rule.onNodeWithContentDescription("缺失 2 种字体，点击查看").performClick()
        rule.runOnIdle { fonts.value = emptyList() }
        rule.onNodeWithText("缺失的字体").assertDoesNotExist()
        rule.runOnIdle { fonts.value = listOf("New Font") }
        rule.onNodeWithText("缺失的字体").assertDoesNotExist()
    }

    @Test fun conversionErrorsNeverExposeEngineDiagnostics() {
        val diagnostic = "private engine diagnostic /home/private/document.docx"
        val reasons = listOf("source-changed", "busy", "timeout", "invalid-document", "output-too-large", "unavailable", "unknown")
        reasons.forEach { reason ->
            assertFalse(officePreviewErrorMessage(PocketError("document-render/failed", diagnostic, jsonOf("reason" to reason))).contains(diagnostic))
        }
        assertFalse(officePreviewErrorMessage(IllegalStateException(diagnostic)).contains(diagnostic))
    }
}
