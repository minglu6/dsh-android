package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression: new/empty home (no open session) must still render official dsh ⋯ items
 * (下载 Session 日志 / 反馈) instead of an empty themed bottom sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class MoreSheetTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        Pocket.profiles.value = listOf(Profile("p1", "desk", host = "100.106.128.87", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.catalog.value = null
        Pocket.ops.value = setOf("session.export", "commands.execute")
        Pocket.sessions.value = emptyList()
        Pocket.chat.value = null
        Pocket.approvals.value = emptyList()
    }

    @Test fun moreWithoutSessionRendersDesktopItems() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("更多").performClick()
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()

        rule.onNodeWithText("下载 Session 日志").assertIsDisplayed()
        rule.onNodeWithText("反馈").assertIsDisplayed()
        // Session-only rows stay hidden on the empty home — not grayed invent-fillers.
        rule.onNodeWithText("重命名").assertDoesNotExist()
        rule.onNodeWithText("会话统计").assertDoesNotExist()
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
