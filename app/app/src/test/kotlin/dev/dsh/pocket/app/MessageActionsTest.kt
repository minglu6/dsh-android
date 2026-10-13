package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.StepRecord
import dev.dsh.pocket.protocol.StepUsage
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TurnRecord
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
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.transcriptView = TranscriptView.Standard
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
        // Official UserMessageNodeView never wires a branch action.
        rule.onAllNodesWithTag("messageBranch").assertCountEquals(1)
    }

    @Test fun settledTurnDoesNotAppendSeparateUsageRow() {
        val answer = Item.Text("a1", "回答正文", false, at = System.currentTimeMillis(),
            seq = 3, messageId = "m1", turn = 1, step = 1)
        val state = Pocket.chat.value!!.copy(
            items = listOf(answer),
            turns = listOf(TurnRecord(1, 1, 4, 1000, 2000, "completed")),
            steps = listOf(StepRecord(
                seq = 3, turn = 1, step = 1, stepStartTime = 1000, messageTime = 2000,
                replyText = answer.text, reasoningText = "",
                usage = StepUsage(inputTokens = 202_000, outputTokens = 2_000),
                provider = "deepseek", model = "flash", toolCallIds = emptyList(),
            )),
        )
        rule.setContent { PocketTheme { Transcript(state, {}) } }
        rule.onNodeWithText(answer.text).assertIsDisplayed()
        rule.onNodeWithTag("messageCopy").assertIsDisplayed()
        rule.onNodeWithTag("turnUsage:1").assertDoesNotExist()
    }

    @Test fun actionOrderClipboardAndCallbacksRemainFunctional() {
        var branch: Long? = null
        var like: String? = null
        var dislike: String? = null
        lateinit var clipboard: ClipboardManager
        rule.setContent {
            clipboard = LocalClipboardManager.current
            PocketTheme {
                Transcript(Pocket.chat.value!!, {}, canFork = true, onFork = { branch = it },
                    onLike = { like = it }, onDislike = { dislike = it })
            }
        }
        val copies = rule.onAllNodesWithTag("messageCopy")
        val clocks = rule.onAllNodesWithTag("messageClock")
        assertTrue(clocks[0].fetchSemanticsNode().boundsInRoot.right <= copies[0].fetchSemanticsNode().boundsInRoot.left)
        val controls = listOf(copies[1], rule.onNodeWithTag("messageLike"), rule.onNodeWithTag("messageDislike"),
            rule.onNodeWithTag("messageBranch"), clocks[1])
        controls.zipWithNext().forEach { (left, right) ->
            assertTrue(left.fetchSemanticsNode().boundsInRoot.right <= right.fetchSemanticsNode().boundsInRoot.left)
        }
        controls.dropLast(1).zipWithNext().forEach { (left, right) ->
            val leftBounds = left.getUnclippedBoundsInRoot()
            val rightBounds = right.getUnclippedBoundsInRoot()
            assertTrue("Message actions must remain a compact cluster",
                rightBounds.left - leftBounds.left <= 40.dp)
        }
        copies[1].performTouchInput { click() }
        rule.runOnIdle { assertEquals("你好！有什么可以帮你的吗？", clipboard.getText()?.text) }
        rule.onNodeWithTag("messageLike").performTouchInput { click() }
        rule.onNodeWithTag("messageDislike").performTouchInput { click() }
        rule.onNodeWithTag("messageBranch").performTouchInput { click() }
        rule.runOnIdle {
            assertEquals("m1", like)
            assertEquals("m1", dislike)
            assertEquals(2L, branch)
        }
    }

    @Test fun earlierAssistantBranchExplainsWhyItIsUnavailable() {
        val state = Pocket.chat.value!!.copy(items = Pocket.chat.value!!.items +
            Item.User("u2", "下一轮", at = System.currentTimeMillis(), seq = 3, messageSourceJson = """{"kind":"user"}"""))
        rule.setContent { PocketTheme { Transcript(state, {}, canFork = true) } }
        rule.onNodeWithTag("messageBranch", useUnmergedTree = true).assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, L.branchUnavailable()))
    }

    @Test fun processPreservesOrderAndIndependentReasoningDisclosure() {
        val first = Item.Reasoning("r1", "First preview\n\nFirst details", 1000, false)
        val tool = Item.Tool("t1", "c1", "job_list", "{}", ToolStatus.Ok, result = """{"jobs":[]}""")
        val last = Item.Reasoning("r2", "Last preview\n\nLast details", 1000, false)
        val members = listOf(first, tool, last)
        val proc = ChatRow.Process("p1", listOf(tool), listOf(first, last), "已完成，用时 6 秒", true, true,
            members = members, steps = listOf(ProcessStep("s1", 1, listOf(first, tool)), ProcessStep("s2", 2, listOf(last))),
            turnSummary = true)
        var opened: Item.Tool? = null
        rule.setContent { PocketTheme { ProcessGroupRow(proc) { opened = it } } }
        val firstRow = rule.onNodeWithTag("reasoningHeader:r1")
        val toolRow = rule.onNodeWithTag("processTool:t1")
        val lastRow = rule.onNodeWithTag("reasoningHeader:r2")
        assertTrue(firstRow.fetchSemanticsNode().boundsInRoot.bottom <= toolRow.fetchSemanticsNode().boundsInRoot.top)
        assertTrue(toolRow.fetchSemanticsNode().boundsInRoot.bottom <= lastRow.fetchSemanticsNode().boundsInRoot.top)
        rule.onNodeWithTag("reasoningBody:r1").assertDoesNotExist()
        firstRow.performClick()
        rule.onNodeWithTag("reasoningBody:r1").assertIsDisplayed()
        rule.onNodeWithTag("reasoningBody:r2").assertDoesNotExist()
        toolRow.performClick()
        rule.runOnIdle { assertEquals(tool, opened) }
        rule.onNodeWithTag("processHeader:p1").performClick()
        rule.onNodeWithTag("reasoningHeader:r1").assertDoesNotExist()
        rule.onNodeWithText("已完成，用时 6 秒").assertIsDisplayed()
    }

    @Test fun reasoningPreviewUsesTheLatestCompletedParagraphWhileStreaming() {
        assertEquals("", reasoningPreview("partial first line", true))
        assertEquals("first", reasoningPreview("**first**\n\nunfinished", true))
        assertEquals("second", reasoningPreview("first\n\nsecond\ncontinued", true))
        assertEquals("first", reasoningPreview("**first**\n\nsecond", false))
    }
    @Test fun failedTurnCannotHideItsFailureDetails() {
        val failure = Item.Tool("failed", "c1", "bash", "{}", ToolStatus.Error, error = "permission denied")
        val proc = ChatRow.Process("failed-turn", listOf(failure), emptyList(), "处理失败", true, true,
            members = listOf(failure), turnSummary = true, canCollapse = false)
        rule.setContent { PocketTheme { ProcessGroupRow(proc) {} } }
        rule.onNodeWithTag("processHeader:failed-turn").assertHasNoClickAction()
        rule.onNodeWithText("处理失败").assertIsDisplayed()
        rule.onNodeWithText("permission denied").assertIsDisplayed()
    }

    @Test fun completedTurnWithoutProcessDoesNotOfferEmptyDisclosure() {
        val proc = ChatRow.Process("empty", emptyList(), emptyList(), "已完成", true, false,
            turnSummary = true, canCollapse = false)
        rule.setContent { PocketTheme { ProcessGroupRow(proc) {} } }
        rule.onNodeWithTag("processHeader:empty").assertHasNoClickAction()
        rule.onNodeWithText("已完成").assertIsDisplayed()
    }
}
