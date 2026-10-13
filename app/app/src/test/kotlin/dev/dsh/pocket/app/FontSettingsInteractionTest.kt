package dev.dsh.pocket.app

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h850dp-xxhdpi")
class FontSettingsInteractionTest {
    @get:Rule val rule = createComposeRule()
    private var original: Map<String, Any?> = emptyMap()
    private val keys = listOf("language", "textFontFamily", "codeFontFamily", "terminalFontFamily")

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        Pocket.init(context)
        installHostSettingsFixture()
        val storage = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        original = keys.associateWith { storage.all[it] }
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.textFontFamily = ""
        Pocket.prefs.codeFontFamily = ""
        Pocket.prefs.terminalFontFamily = ""
    }

    @After fun restore() {
        RuntimeEnvironment.getApplication().getSharedPreferences("app", Context.MODE_PRIVATE).edit().apply {
            original.forEach { (key, value) -> if (value is String) putString(key, value) else remove(key) }
        }.commit()
    }

    @Test fun stepperDisablesBothLimitsAndDoesNotExposeEditableSize() {
        rule.setContent {
            PocketTheme {
                Column {
                    listOf("字号大小" to 10..22, "代码字号" to 10..16, "终端字号" to 10..20).forEach { (title, range) ->
                        var size by remember { mutableIntStateOf(range.first) }
                        SettingsFontRow(title, "说明", size, range) { size = it }
                    }
                }
            }
        }
        listOf("字号大小" to 12, "代码字号" to 6, "终端字号" to 10).forEach { (title, steps) ->
            rule.onNodeWithContentDescription("$title：减小字号").assertIsNotEnabled()
            repeat(steps) { rule.onNodeWithContentDescription("$title：增大字号").performClick() }
            rule.onNodeWithContentDescription("$title：增大字号").assertIsNotEnabled()
            rule.onNodeWithContentDescription("$title：减小字号").assertIsEnabled()
        }
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun familyDraftCommitsOnDoneOrBlurAndImmediatelyChangesAllRoleConsumers() {
        var textFont: FontFamily? = null
        var codeFont: FontFamily? = null
        var terminalFont: FontFamily? = null
        rule.setContent {
            PocketTheme {
                val body = MaterialTheme.typography.bodyMedium.fontFamily
                val code = T.Mono.fontFamily
                val terminal = LocalFonts.terminal
                SideEffect { textFont = body; codeFont = code; terminalFont = terminal }
                Column {
                    SettingsFontFamilyRow("正文", "说明", Pocket.prefs.textFontFamily, "textFontFamily") { Pocket.prefs.textFontFamily = it }
                    SettingsFontFamilyRow("代码", "说明", Pocket.prefs.codeFontFamily, "codeFontFamily") { Pocket.prefs.codeFontFamily = it }
                    SettingsFontFamilyRow("终端", "说明", Pocket.prefs.terminalFontFamily, "terminalFontFamily") { Pocket.prefs.terminalFontFamily = it }
                }
            }
        }
        rule.onNodeWithTag("textFontFamily").performTextReplacement("  serif , monospace  ")
        rule.runOnIdle { assertEquals("", Pocket.prefs.textFontFamily) }
        rule.onNodeWithTag("textFontFamily").performImeAction()
        rule.runOnIdle {
            assertEquals("serif, monospace", Pocket.prefs.textFontFamily)
            assertEquals(FontFamily.Serif, textFont)
        }
        rule.onNodeWithTag("codeFontFamily").performClick().performTextReplacement("monospace")
        rule.onNodeWithTag("terminalFontFamily").performClick()
        rule.runOnIdle { assertEquals(FontFamily.Monospace, codeFont) }
        rule.onNodeWithTag("terminalFontFamily").performTextReplacement("serif")
        rule.onNodeWithTag("terminalFontFamily").performImeAction()
        rule.runOnIdle { assertEquals(FontFamily.Serif, terminalFont) }
        rule.onNodeWithTag("textFontFamily").performClick().performTextReplacement("")
        rule.onNodeWithTag("textFontFamily").performImeAction()
        rule.runOnIdle { assertEquals(FontFamily.Default, textFont) }
    }

    @Test fun fontFieldsFollowDesktopOrderAndNewPageMountStartsCollapsed() {
        var page by mutableIntStateOf(0)
        rule.setContent { PocketTheme { key(page) { GeneralSettingsScreen {} } } }
        rule.onNodeWithTag("textFontFamily").assertDoesNotExist()
        rule.onNodeWithTag("fontSettingsToggle").performScrollTo().performClick()
        val textY = rule.onNodeWithTag("textFontFamily").fetchSemanticsNode().positionInRoot.y
        val codeY = rule.onNodeWithTag("codeFontFamily").fetchSemanticsNode().positionInRoot.y
        val codeSizeY = rule.onNodeWithText("代码字号").fetchSemanticsNode().positionInRoot.y
        val terminalY = rule.onNodeWithTag("terminalFontFamily").fetchSemanticsNode().positionInRoot.y
        val terminalSizeY = rule.onNodeWithText("终端字号").fetchSemanticsNode().positionInRoot.y
        assertTrue(textY < codeY && codeY < codeSizeY && codeSizeY < terminalY && terminalY < terminalSizeY)
        rule.onNodeWithTag("textFontFamily").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { page++ }
        rule.onNodeWithTag("textFontFamily").assertDoesNotExist()
    }
}
