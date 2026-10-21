package dev.dsh.pocket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
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
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class SessionTitleTest {
    @get:Rule val rule = createComposeRule()
    @Before fun setup() {
        installTestIdlePolicy()
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
    }

    @Test fun clickingTruncatedConversationTitleRevealsEveryCharacterAndBackDismisses() {
        val title = "地面洞察报告生成逻辑梳理与 Agent Skill 准备"
        rule.setContent { PocketTheme {
            SessionTitleAction("session", title, System.currentTimeMillis() - 3 * 3_600_000L,
                false, SidebarActivity.Idle, Modifier.width(90.dp))
        } }
        val clipped = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(title, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(clipped) }
        assertTrue(clipped.single().hasVisualOverflow)
        rule.onNodeWithTag("sessionTitle").performClick()
        rule.onNodeWithTag("sessionTitleFull").assertTextEquals(title).assertIsDisplayed()
        val full = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag("sessionTitleFull").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(full) }
        assertFalse(full.single().hasVisualOverflow)
        rule.onNodeWithText("3小时前").assertIsDisplayed()
        rule.onNodeWithText("空闲").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithTag("sessionTitlePopover").assertDoesNotExist()
    }

    @Test fun openCardReflectsLiveRenameAndStatusButNeverLeaksAcrossSessions() {
        val id = mutableStateOf("one")
        val title = mutableStateOf("Original conversation title")
        val activity = mutableStateOf(SidebarActivity.Running)
        rule.setContent { PocketTheme {
            SessionTitleAction(id.value, title.value, null, false, activity.value, Modifier.width(90.dp))
        } }
        rule.onNodeWithTag("sessionTitle").performClick()
        rule.onNodeWithText("运行中").assertIsDisplayed()
        rule.runOnIdle { title.value = "Renamed complete title"; activity.value = SidebarActivity.AwaitingApproval }
        rule.onNodeWithTag("sessionTitleFull").assertTextEquals("Renamed complete title")
        rule.onNodeWithText("等待审批").assertIsDisplayed()
        rule.onNodeWithText("运行中").assertDoesNotExist()
        rule.runOnIdle { id.value = "two"; title.value = "Other session" }
        rule.onNodeWithTag("sessionTitlePopover").assertDoesNotExist()
    }

    private fun marqueeRange() = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        .fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]

    @Test fun deselectingScrolledRecordRestoresDefaultEllipsisFromTheBeginning() {
        val selected = mutableStateOf(false)
        val title = "完整的长会话记录标题：" + "需要看清的内容".repeat(8)
        rule.setContent { PocketTheme {
            SessionRecordTitle(title, T.Ink, Modifier.width(140.dp), selected.value)
        } }
        fun layout(): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(title, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single()
        }
        assertTrue(layout().isLineEllipsized(0))
        rule.runOnIdle { selected.value = true }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(40_000)
        assertFalse(layout().isLineEllipsized(0))
        rule.runOnIdle { selected.value = false }
        rule.waitForIdle()
        val restored = layout()
        assertTrue(restored.isLineEllipsized(0))
        assertEquals(0, restored.getLineStart(0))
        assertEquals(title, restored.layoutInput.text.text)
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)).assertDoesNotExist()
    }

    @Test fun shortRecordTitlesStayStillAndTitleTouchesStillOpenTheContainingRow() {
        var opened = 0
        val title = mutableStateOf("Short title")
        rule.setContent { PocketTheme {
            Box(Modifier.width(180.dp).clickable { opened++ }) {
                SessionRecordTitle(title.value, T.Ink)
            }
        } }
        assertEquals(0f, marqueeRange().maxValue(), 0f)
        rule.onNodeWithText("Short title").performTouchInput { click() }
        rule.runOnIdle { assertEquals(1, opened); title.value = "New short title" }
        assertEquals(0f, marqueeRange().value(), 0f)
        rule.onNodeWithText("New short title").assertIsDisplayed()
    }
}
