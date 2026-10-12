package dev.dsh.pocket.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import dev.dsh.pocket.protocol.Item
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Message action row must be icon-only (desktop MessageIconActions):
 * locale strings are contentDescription / tooltip, not visible Text buttons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class MessageActionsTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        Pocket.init(RuntimeEnvironment.getApplication())
        Pocket.profiles.value = listOf(Profile("p1", "desk", host = "100.106.128.87", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.catalog.value = null
        Pocket.ops.value = setOf("feedback.set", "sessions.fork")
        Pocket.sessions.value = emptyList()
        Pocket.chat.value = ChatState(
            sessionId = "s1",
            title = "你好",
            loaded = true,
            running = false,
            items = listOf(
                Item.User("u1", "你好", at = System.currentTimeMillis(), seq = 1, messageSourceJson = """{"kind":"user"}"""),
                Item.Text("a1", "你好！有什么可以帮你的吗？", false, at = System.currentTimeMillis(), seq = 2, messageId = "m1"),
            ),
        )
        Pocket.approvals.value = emptyList()
        Pocket.feedback.value = emptyMap()
    }

    @Test fun assistantRowUsesIconContentDescriptionsNotVisibleLabels() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()

        // User + assistant both expose copy; assistant also exposes feedback icons.
        rule.onAllNodesWithTag("messageCopy").assertCountEquals(2)
        rule.onNodeWithTag("messageLike").assertIsDisplayed()
        rule.onNodeWithTag("messageDislike").assertIsDisplayed()

        rule.onAllNodesWithContentDescription("复制").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("好的回答").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("有问题的回答").assertCountEquals(1)

        // Visible text buttons must not appear (icons only).
        rule.onAllNodesWithText("复制", substring = false).assertCountEquals(0)
        rule.onAllNodesWithText("在新对话中分支").assertCountEquals(0)
        rule.onAllNodesWithText("好的回答").assertCountEquals(0)
        rule.onAllNodesWithText("有问题的回答").assertCountEquals(0)
    }
}
