package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.mutableStateMapOf
import dev.dsh.pocket.protocol.Approval
import dev.dsh.pocket.protocol.UserQuestion
import dev.dsh.pocket.protocol.UserQuestionItem
import dev.dsh.pocket.protocol.UserQuestionOption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        installHostSettingsFixture()
        Pocket.profiles.value = listOf(Profile("p1", "desk", host = "100.106.128.87", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.catalog.value = null
        Pocket.ops.value = emptySet()
        Pocket.sessions.value = emptyList()
        Pocket.chat.value = null
        Pocket.approvals.value = emptyList()
        Pocket.userQuestions.value = emptyList()
    }

    @Test fun newSessionSendStaysEnabledWhenDraftNonEmpty() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.waitForIdle()

        // Desktop locale input.send = 发送消息 (also exposed as testTag composerSend).
        val send = rule.onNodeWithTag("composerSend")
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
        rule.onNodeWithTag("composerSend").assertIsDisplayed()
    }
    @Test fun questionNavigationRetainsEveryAnswerAndSubmitLocksUntilSettlement() {
        val q = UserQuestion("q", "s1", "call", listOf(
            UserQuestionItem("one", "First question", options = listOf(UserQuestionOption("A"))),
            UserQuestionItem("two", "Second question", options = listOf(UserQuestionOption("B"))),
        ))
        val drafts = mutableStateMapOf<String, QuestionDraft>()
        var submitted: List<JsonObject>? = null
        var calls = 0
        var settle: ((Boolean) -> Unit)? = null
        rule.setContent { PocketTheme {
            UserQuestionCard(q, drafts, onDismiss = {}, onAnswer = { _, answers, done ->
                calls++; submitted = answers; settle = done
            })
        } }
        rule.onNodeWithText("A").performClick()
        rule.onNodeWithText(L.nextQuestion()).performScrollTo().performClick()
        rule.onNodeWithText("B").performClick()
        rule.onNodeWithText("上一题").performScrollTo().performClick()
        rule.onNodeWithText(L.nextQuestion()).performScrollTo().performClick()
        rule.onNodeWithText("提交答案").performScrollTo().performClick()
        rule.onNodeWithText("提交答案").assertIsNotEnabled()
        assertEquals(1, calls)
        assertEquals("A", submitted!![0]["selected"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("B", submitted!![1]["selected"]!!.jsonArray.single().jsonPrimitive.content)
        rule.runOnIdle { settle!!(false) }
        rule.onNodeWithText("提交答案").assertIsEnabled()
        rule.onNodeWithText("提交答案").performClick()
        assertEquals(2, calls)
    }

    @Test fun planDiscussionOnlyDismissesWithoutAnswer() {
        var dismissed = 0
        var answers = 0
        val q = UserQuestion("plan", "s1", "call", listOf(UserQuestionItem("plan", "Review plan",
            options = listOf(UserQuestionOption("Approve")), intentKind = "plan-review", approveLabel = "Approve")))
        rule.setContent { PocketTheme {
            UserQuestionCard(q, mutableStateMapOf(), onDismiss = { dismissed++ },
                onAnswer = { _, _, _ -> answers++ })
        } }
        rule.onNodeWithText("继续讨论").performScrollTo().performClick()
        assertEquals(1, dismissed)
        assertEquals(0, answers)
    }

    @Test fun approvalCannotReenterAndFailureRestoresControls() {
        var calls = 0
        var settle: ((Boolean) -> Unit)? = null
        rule.setContent { PocketTheme {
            ApprovalCard(Approval("approval", "s1", "bash", null, "Requires workspace write access")) { _, _, done ->
                calls++; settle = done
            }
        } }
        rule.onNodeWithText("允许一次").performClick()
        rule.onNodeWithText("拒绝").assertIsNotEnabled()
        rule.onNodeWithText("提交中…").assertIsNotEnabled()
        assertEquals(1, calls)
        rule.runOnIdle { settle!!(false) }
        rule.onNodeWithText("允许一次").assertIsEnabled()
        rule.onNodeWithText("拒绝").performClick()
        assertEquals(2, calls)
    }

    @Test fun triggerAndReplacementRespectCaretAndSuffix() {
        assertNull(composerTrigger("mail@example.com", 9))
        assertNull(composerTrigger("path/src", 8))
        assertNull(composerTrigger("explain /goal", 13))
        assertEquals("src", composerTrigger("look @src after", 9)?.query)
        assertEquals("look @src/Main.kt  after" to 18, replaceComposerToken("look @src after", 9, "@src/Main.kt"))
        assertEquals("/model  suffix" to 7, replaceComposerToken("/mo suffix", 3, "/model"))
        assertEquals("folder name", composerTrigger("read @\"folder name", 18)?.query)
        assertEquals("@\"folder name/a.kt\"", fileReferenceToken("folder name/a.kt", false))
        assertEquals("@src/", fileReferenceToken("src", true))
        assertNull(fileReferenceToken("invalid\"name", false))
        assertNull(fileReferenceToken("invalid\nname", false))
        assertEquals("goal", builtinCommandName("@deepseek-ai/dsh-command-goal"))
        assertNull(builtinCommandName("custom-goal"))
    }
}
