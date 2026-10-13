package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Session actions require both an open session and the matching advertised capability. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class MoreSheetTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        installHostSettingsFixture()
        Pocket.profiles.value = listOf(Profile("p1", "desk", host = "100.106.128.87", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.catalog.value = null
        Pocket.ops.value = setOf("session.export", "sessionFeedback.record")
        Pocket.sessions.value = emptyList()
        Pocket.chat.value = null
        Pocket.approvals.value = emptyList()
    }

    @Test fun commandExecutionDoesNotAdvertiseSessionFeedback() {
        Pocket.ops.value = setOf("session.export", "commands.execute")
        rule.setContent { PocketTheme { MoreSheet(chat = ChatState("s1"), onDismiss = {}, onFeedback = {}, onExport = {}) } }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("反馈").assertDoesNotExist()
    }

    @Test fun moreWithoutSessionDisablesSessionActions() {
        rule.setContent {
            PocketTheme {
                MoreSheet(chat = null, onDismiss = {}, onFeedback = {}, onExport = {})
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()

        rule.onNodeWithText("下载 Session 日志").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("反馈").assertIsDisplayed().assertIsNotEnabled()
    }
}
