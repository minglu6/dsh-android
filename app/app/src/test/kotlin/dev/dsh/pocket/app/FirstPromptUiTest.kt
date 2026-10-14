package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.dsh.pocket.protocol.Item
import kotlinx.coroutines.flow.MutableStateFlow
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
class FirstPromptUiTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var restore: () -> Unit
    @Suppress("UNCHECKED_CAST")
    private fun firstPrompt() = Pocket.javaClass.getDeclaredField("_firstPrompt").apply { isAccessible = true }
        .get(Pocket) as MutableStateFlow<FirstPrompt?>

    @Before fun setUp() {
        val chat = Pocket.chat.value
        val first = firstPrompt().value
        val active = Pocket.active.value
        val status = Pocket.status.value
        val approvals = Pocket.approvals.value
        val questions = Pocket.userQuestions.value
        restore = {
            Pocket.chat.value = chat
            firstPrompt().value = first
            Pocket.active.value = active
            Pocket.status.value = status
            Pocket.approvals.value = approvals
            Pocket.userQuestions.value = questions
        }
        installHostSettingsFixture()
        Pocket.chat.value = null
        Pocket.active.value = "first-prompt-ui"
        Pocket.status.value = mapOf("first-prompt-ui" to Dot.Online)
        Pocket.approvals.value = emptyList()
        Pocket.userQuestions.value = emptyList()
        firstPrompt().value = FirstPrompt("first-id", "第一条消息")
    }

    @After fun tearDown() { restore() }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    @Test fun messageStaysVisibleThroughCreationAndSnapshotWhileNextDraftSurvives() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithText("第一条消息").assertIsDisplayed()
        rule.onNodeWithTag("firstPromptStatus").assertIsDisplayed()
            .captureRoboImage("build/screenshots/first-prompt-sending.png")
        rule.onNodeWithTag("composerInput").performTextInput("下一条草稿")
        rule.onNodeWithTag("composerSend").assertIsNotEnabled()
        rule.runOnIdle {
            Pocket.chat.value = ChatState("real-session", items = listOf(
                Item.User("local-first-id", "第一条消息", pending = true, requestId = "first-id"),
            ))
        }
        rule.onNodeWithText("第一条消息").assertIsDisplayed()
        rule.onNodeWithTag("composerInput").assertTextEquals("下一条草稿")
        rule.runOnIdle {
            Pocket.chat.value = Pocket.chat.value!!.copy(loaded = true)
            firstPrompt().value = null
        }
        rule.onNodeWithText("第一条消息").assertIsDisplayed()
        rule.onNodeWithTag("composerInput").assertTextEquals("下一条草稿")
        rule.onNodeWithTag("firstPromptStatus").assertDoesNotExist()
    }

    @Test fun failedCreationKeepsMessageAndCanRestoreEditableText() {
        firstPrompt().value = FirstPrompt("first-id", "第一条消息", error = "连接已断开")
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithText("第一条消息").assertIsDisplayed()
        rule.onNodeWithText("发送失败：连接已断开").assertIsDisplayed()
        rule.onNodeWithText("重新编辑").performClick()
        rule.waitUntil(5000) { firstPrompt().value == null }
        rule.onNodeWithTag("composerInput").assertTextEquals("第一条消息")
        rule.onNodeWithTag("firstPromptStatus").assertDoesNotExist()
    }

    @Test fun switchingToAnotherConversationDoesNotCarryItsDraft() {
        firstPrompt().value = null
        Pocket.chat.value = ChatState("existing", loaded = true)
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("composerInput").performTextInput("不要发到其他会话")
        rule.runOnIdle { Pocket.chat.value = ChatState("other", loaded = true) }
        rule.onNodeWithTag("composerInput").assertTextEquals("")
    }

    @Test fun welcomeToFirstMessageKeepsComposerFocus() {
        firstPrompt().value = null
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("composerInput").performTextInput("下一条草稿")
        rule.onNodeWithTag("composerInput").assertIsFocused()
        rule.runOnIdle { firstPrompt().value = FirstPrompt("first-id", "第一条消息") }
        rule.onNodeWithText("第一条消息").assertIsDisplayed()
        rule.onNodeWithTag("composerInput").assertIsFocused()
        rule.runOnIdle {
            Pocket.chat.value = ChatState("real-session", items = listOf(
                Item.User("local-first-id", "第一条消息", pending = true, requestId = "first-id"),
            ))
        }
        rule.onNodeWithTag("composerInput").assertIsFocused()
        rule.onNodeWithTag("composerInput").assertTextEquals("下一条草稿")
    }
}
