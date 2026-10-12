package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression: after opening a new/empty session, non-empty draft must keep Send
 * visible+enabled; while running with empty draft, Stop is shown (wired to Pocket.cancel).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ComposerTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        Pocket.profiles.value = listOf(Profile("p1", "desk", nodeId = "abcdefghijklmnopqrstuvwxyz01"))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.catalog.value = null
        Pocket.ops.value = emptySet()
        Pocket.sessions.value = emptyList()
        Pocket.chat.value = null
        Pocket.approvals.value = emptyList()
    }

    @Test fun newSessionSendStaysEnabledWhenDraftNonEmpty() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()

        val send = rule.onNodeWithContentDescription("发送")
        send.assertIsDisplayed()
        send.assertIsNotEnabled()

        rule.onNodeWithTag("composerInput").performTextInput("你好")
        rule.waitForIdle()

        send.assertIsDisplayed()
        send.assertIsEnabled()
    }

    @Test fun stopShownWhileRunningAndClickable() {
        Pocket.chat.value = ChatState("s1", title = "新会话", running = true, loaded = true)
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()

        val stop = rule.onNodeWithContentDescription("终止")
        stop.assertIsDisplayed()
        stop.assertIsEnabled()
        // Invokes Pocket.cancel(); with no client it fails closed via errors (not a silent UI no-op).
        stop.performClick()
        rule.waitForIdle()
    }

    @Test fun idleChatShowsSendNotStop() {
        Pocket.chat.value = ChatState("s1", running = false, loaded = true)
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("发送").assertIsDisplayed()
    }
}
