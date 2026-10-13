package dev.dsh.pocket.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
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
    private val platform = mutableStateOf<String?>(null)
    private val terminal = AnsiTerminal()
    private lateinit var clipboard: ClipboardManager

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        Pocket.init(context)
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
        Pocket.prefs.terminalFontSize = 13
        clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        terminal.resize(40, 16)
        terminal.write("original output")
        rule.setContent {
            PocketTheme {
                RightPanelFrame("zsh", onDismiss = {}) {
                    TerminalView(terminal, revision.longValue, Modifier.fillMaxSize(), enabled.value, platform.value,
                        onResize = { _, _ -> }, onInput = { sent += it })
                }
            }
        }
    }

    private fun key(description: String) = rule.onNodeWithContentDescription(description)
    private fun setClipboard(text: String) = rule.runOnIdle {
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    private fun selectVisibleLine() {
        key("终端输出，长按选择文本").performTouchInput {
            down(Offset(1f, 1f))
            advanceEventTime(700)
            moveTo(Offset(600f, 1f), delayMillis = 100)
            up()
        }
    }

    @Test fun shortcutsRetainInputFocusAndRemainReachableOnNarrowScreen() {
        key("Show keyboard").performClick()
        val input = key("终端键盘输入")
        input.assertIsFocused().performTextInput("echo ")
        key("Tab").assertIsDisplayed().performTouchInput { click() }
        input.assertIsFocused().performTextInput("ok")
        key("Cursor up").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performTouchInput { click() }
        input.assertIsFocused()
        assertEquals(listOf("echo ", "\t", "ok", "\u001b[A"), sent)
    }

    @Test fun multilinePasteRequiresConfirmationAndCancelSendsNothing() {
        setClipboard("echo first\necho second\n")
        key("Paste").performClick()
        rule.onNodeWithText("取消").performClick()
        assertTrue(sent.isEmpty())
        key("Paste").performClick()
        assertTrue(sent.isEmpty())
        rule.onNodeWithText("确认粘贴").performClick()
        assertEquals(listOf("echo first\necho second\n"), sent)
    }

    @Test fun keyboardClipboardCannotBypassMultilineConfirmationButEnterStillWorks() {
        key("Show keyboard").performClick()
        val input = key("终端键盘输入")
        input.performTextInput("echo first\necho second\n")
        rule.onNodeWithText("确认粘贴").assertIsDisplayed()
        assertTrue(sent.isEmpty())
        rule.onNodeWithText("确认粘贴").performClick()
        key("Show keyboard").performClick()
        input.performTextInput("\n")
        assertEquals(listOf("echo first\recho second\r", "\r"), sent)
    }

    @Test fun disconnectCancelsPendingPasteButKeepsOutputCopyable() {
        setClipboard("exit\r")
        key("Paste").performClick()
        rule.runOnIdle { enabled.value = false }
        rule.onNodeWithText("确认粘贴").assertDoesNotExist()
        key("Paste").assertIsNotEnabled()
        selectVisibleLine()
        key("Copy selection").performClick()
        rule.runOnIdle {
            assertTrue(sent.isEmpty())
            assertEquals("original output", clipboard.primaryClip!!.getItemAt(0).text.toString().trim())
        }
    }

    @Test fun selectingFreezesOutputAndReturningToKeyboardRestoresInput() {
        key("Show keyboard").performClick()
        selectVisibleLine()
        key("终端键盘输入").assertIsNotEnabled()
        key("Ctrl+D").assertIsNotEnabled()
        rule.runOnIdle {
            terminal.write(" changed")
            revision.longValue++
        }
        key("Copy selection").performClick()
        rule.runOnIdle {
            assertEquals("original output", clipboard.primaryClip!!.getItemAt(0).text.toString().trim())
            assertTrue(sent.isEmpty())
        }
        key("Done").performClick()
        key("终端键盘输入").assertIsEnabled().assertIsFocused().performTextInput("pwd")
        assertEquals(listOf("pwd"), sent)
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp-xxhdpi")
    fun controlsStayAboveOutputWhenViewportIsShort() {
        val output = key("终端输出，长按选择文本").fetchSemanticsNode().boundsInRoot
        assertTrue(output.height > 0)
        for (description in listOf("Esc", "Tab", "Ctrl+C", "Ctrl+D", "Paste", "Show keyboard",
                "Cursor up", "Cursor down", "Copy selection")) {
            assertTrue(key(description).fetchSemanticsNode().boundsInRoot.bottom <= output.top)
            key(description).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        key("Paste").assertIsDisplayed()
        key("Copy selection").assertIsDisplayed()
    }

    @Test fun macAndWindowsKeepShellControlsDistinctFromClipboard() {
        for (os in listOf("darwin", "win32", "linux", null)) {
            rule.runOnIdle { platform.value = os; sent.clear() }
            setClipboard("paste text")
            key("Ctrl+C").performClick()
            key("Ctrl+D").performClick()
            key("Paste").performClick()
            assertEquals(listOf("\u0003", "\u0004", "paste text"), sent)
            selectVisibleLine()
            key("Copy selection").performClick()
            if (os == "darwin") key("Ctrl+C").assertIsNotEnabled()
            else key("Ctrl+C").performClick()
            rule.runOnIdle {
                assertEquals("original output", clipboard.primaryClip!!.getItemAt(0).text.toString().trim())
                assertEquals(3, sent.size)
            }
            key("Done").performClick()
        }
    }

    @Test fun tapMovesActualCursorAndLongPressImmediatelySelectsTouchedWord() {
        val output = key("终端输出，长按选择文本")
        output.performTouchInput { click(Offset(1f, 1f)) }
        assertEquals(listOf("\u001b[D".repeat("original output".length)), sent)
        rule.runOnIdle { sent.clear() }
        output.performTouchInput { longClick(Offset(1f, 1f)) }
        key("终端键盘输入").assertIsNotEnabled()
        rule.runOnIdle { terminal.write(" changed"); revision.longValue++ }
        key("Copy selection").performClick()
        rule.runOnIdle {
            assertEquals("original", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun draggingAfterLongPressExtendsSelectionWithoutSendingInput() {
        key("终端输出，长按选择文本").performTouchInput {
            down(Offset(1f, 1f))
            advanceEventTime(700)
            moveTo(Offset(600f, 1f), delayMillis = 100)
            up()
        }
        key("Copy selection").performClick()
        rule.runOnIdle {
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertEquals("original output", copied.trim())
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun tapOnAnotherOutputRowNeverSendsHistoryNavigation() {
        rule.runOnIdle { terminal.write("\r\ncurrent"); revision.longValue++ }
        key("终端输出，长按选择文本").performTouchInput { click(Offset(1f, 1f)) }
        assertTrue(sent.isEmpty())
    }

    @Test fun mouseApplicationReceivesClickButLongPressIsLocalSelection() {
        rule.runOnIdle {
            terminal.write("\u001b[?1000;1006h")
            revision.longValue++
        }
        val output = key("终端输出，长按选择文本")
        output.performTouchInput { click(Offset(1f, 1f)) }
        assertEquals(listOf("\u001b[<0;1;1M\u001b[<0;1;1m"), sent)
        output.performTouchInput { longClick(Offset(1f, 1f)) }
        key("Copy selection").performClick()
        rule.runOnIdle {
            assertEquals("original", clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertEquals(1, sent.size)
        }
    }
}
