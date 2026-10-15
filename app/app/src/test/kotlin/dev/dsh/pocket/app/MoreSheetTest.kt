package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
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

    @Test fun settingsKeepsPresetEntryWithoutDuplicatingSidebarAutomation() {
        Pocket.ops.value += "schedule.catalog"
        rule.setContent { PocketTheme {
            SettingsScreen(onBack = {}, onGeneral = {}, onConnections = {})
        } }
        rule.onNodeWithText(L.scheduleNav()).assertDoesNotExist()
        rule.onNodeWithText("Agent 预设").assertIsDisplayed()
    }

    @Test fun sessionMenuExportsAndOpensFeedbackWithoutDuplicatingSidebarActions() {
        Pocket.ops.value += setOf("sessions.rename", "sessions.fork")
        var exports = 0
        var feedback = 0
        var dismissed = 0
        rule.setContent { PocketTheme {
            MoreSheet(ChatState("s1"), onDismiss = { dismissed++ }, onFeedback = { feedback++ }, onExport = { exports++ })
        } }
        rule.onNodeWithText("重命名").assertDoesNotExist()
        rule.onNodeWithText("在新对话中分支").assertDoesNotExist()
        rule.onNodeWithText("会话统计").assertDoesNotExist()
        rule.onNodeWithText("已加载历史统计").assertDoesNotExist()
        assertEquals(0, exports)
        assertEquals(0, feedback)
        rule.onNodeWithText("下载 Session 日志").performClick()
        assertEquals(1, exports)
        assertEquals(1, dismissed)
        rule.onNodeWithText("反馈").performClick()
        assertEquals(1, feedback)
    }

    @Test fun offlineSessionActionsCannotDispatch() {
        Pocket.status.value = mapOf("p1" to Dot.Offline)
        rule.setContent { PocketTheme {
            MoreSheet(ChatState("s1"), onDismiss = {}, onFeedback = { error("offline feedback") }, onExport = { error("offline export") })
        } }
        rule.onNodeWithText("下载 Session 日志").assertIsNotEnabled()
        rule.onNodeWithText("反馈").assertIsNotEnabled()
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
