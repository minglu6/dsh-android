package dev.dsh.pocket.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
@Config(sdk = [34], qualifiers = "w320dp-h740dp-xxhdpi")
class TerminalInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val sent = mutableListOf<String>()
    private val enabled = mutableStateOf(true)
    private val revision = mutableLongStateOf(0)
    private val terminal = AnsiTerminal()
    private lateinit var clipboard: ClipboardManager

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        Pocket.init(context)
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        terminal.resize(40, 16)
        terminal.write("original output")
        rule.setContent {
            PocketTheme {
                RightPanelFrame("zsh", onDismiss = {}) {
                    TerminalView(terminal, revision.longValue, Modifier.fillMaxSize(), enabled.value,
                        onResize = { _, _ -> }, onInput = { sent += it })
                }
            }
        }
    }

    private fun key(description: String) = rule.onNodeWithContentDescription(description)
    private fun setClipboard(text: String) = rule.runOnIdle {
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    @Test fun shortcutsRetainInputFocusAndRemainReachableOnNarrowScreen() {
        key("打开键盘").performClick()
        val input = key("终端键盘输入")
        input.assertIsFocused().performTextInput("echo ")
        key("Tab · 补全").assertIsDisplayed().performTouchInput { click() }
        input.assertIsFocused().performTextInput("ok")
        key("上一条命令").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        input.assertIsFocused()
        assertEquals(listOf("echo ", "\t", "ok", "\u001b[A"), sent)
    }

    @Test fun multilinePasteRequiresConfirmationAndCancelSendsNothing() {
        setClipboard("echo first\necho second\n")
        key("粘贴").performClick()
        rule.onNodeWithText("取消").performClick()
        assertTrue(sent.isEmpty())
        key("粘贴").performClick()
        assertTrue(sent.isEmpty())
        rule.onNodeWithText("确认粘贴").performClick()
        assertEquals(listOf("echo first\necho second\n"), sent)
    }

    @Test fun keyboardClipboardCannotBypassMultilineConfirmationButEnterStillWorks() {
        key("打开键盘").performClick()
        val input = key("终端键盘输入")
        input.performTextInput("echo first\necho second\n")
        rule.onNodeWithText("确认粘贴").assertIsDisplayed()
        assertTrue(sent.isEmpty())
        rule.onNodeWithText("确认粘贴").performClick()
        key("打开键盘").performClick()
        input.performTextInput("\n")
        assertEquals(listOf("echo first\recho second\r", "\r"), sent)
    }

    @Test fun disconnectCancelsPendingPasteButKeepsOutputCopyable() {
        setClipboard("exit\r")
        key("粘贴").performClick()
        rule.runOnIdle { enabled.value = false }
        rule.onNodeWithText("确认粘贴").assertDoesNotExist()
        key("粘贴").assertIsNotEnabled()
        key("选择文本").performClick()
        key("复制全部").performClick()
        rule.runOnIdle {
            assertTrue(sent.isEmpty())
            assertEquals("original output", clipboard.primaryClip!!.getItemAt(0).text.toString().trim())
        }
    }

    @Test fun selectingFreezesOutputAndReturningToKeyboardRestoresInput() {
        key("打开键盘").performClick()
        key("选择文本").performClick()
        key("终端键盘输入").assertIsNotEnabled()
        key("Ctrl+C · 中断").assertDoesNotExist()
        rule.runOnIdle {
            terminal.write(" changed")
            revision.longValue++
        }
        key("复制全部").performClick()
        rule.runOnIdle {
            assertEquals("original output", clipboard.primaryClip!!.getItemAt(0).text.toString().trim())
            assertTrue(sent.isEmpty())
        }
        key("键盘").performClick()
        key("终端键盘输入").assertIsEnabled().assertIsFocused().performTextInput("pwd")
        assertEquals(listOf("pwd"), sent)
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp-xxhdpi")
    fun controlsStayAboveOutputWhenViewportIsShort() {
        val output = key("终端输出，长按选择文本").fetchSemanticsNode().boundsInRoot
        val esc = key("Esc · 退出当前操作").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val arrow = key("上一条命令").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(esc.bottom <= arrow.top)
        assertTrue(arrow.bottom <= output.top)
        assertTrue(output.height > 0)
        key("粘贴").assertIsDisplayed()
        key("选择文本").assertIsDisplayed()
        key("更多按键").assertIsDisplayed().performClick()
        rule.onNodeWithText("回车").assertIsDisplayed()
    }
}
