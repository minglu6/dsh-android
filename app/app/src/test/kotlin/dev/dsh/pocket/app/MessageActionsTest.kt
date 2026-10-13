package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.StepRecord
import dev.dsh.pocket.protocol.StepUsage
import dev.dsh.pocket.protocol.ToolStatus
import dev.dsh.pocket.protocol.TurnRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.After
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
        InspectorNavigation.reset()
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.transcriptView = TranscriptView.Standard
        Pocket.prefs.collapseTiming = CollapseTiming.Completion
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

    @After fun clearInspectorNavigation() { InspectorNavigation.reset() }

    @Test fun inspectorPickerConsumesActualMessageActionsAndOutsideButtons() {
        var like: String? = null
        var outsideClicks = 0
        rule.setContent {
            PocketTheme {
                InspectorChatSurface("s1") {
                    Column(Modifier.fillMaxSize()) {
                        TextButton({ outsideClicks++ }, Modifier.testTag("outsideAction")) { Text("action") }
                        Box(Modifier.weight(1f)) {
                            Transcript(Pocket.chat.value!!, {}, onLike = { like = it })
                        }
                    }
                }
            }
        }
        rule.runOnIdle { InspectorNavigation.begin("s1") }
        rule.onNodeWithTag("outsideAction").performTouchInput { click() }
        rule.runOnIdle {
            assertEquals(0, outsideClicks)
            assertTrue(InspectorNavigation.picking)
            assertNull(InspectorNavigation.pickedItemKey)
        }
        rule.onNodeWithTag("messageLike").performTouchInput { click() }
        rule.runOnIdle {
            assertEquals("a1", InspectorNavigation.pickedItemKey)
            assertFalse(InspectorNavigation.picking)
            assertNull(like)
        }
    }

    @Test fun inspectorBackCancelsWithoutPickingOrClearingSession() {
        lateinit var back: OnBackPressedDispatcher
        rule.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            PocketTheme { InspectorChatSurface("s1") { Transcript(Pocket.chat.value!!, {}) } }
        }
        rule.runOnIdle { InspectorNavigation.begin("s1") }
        rule.runOnIdle { back.onBackPressed() }
        rule.runOnIdle {
            assertFalse(InspectorNavigation.picking)
            assertNull(InspectorNavigation.pickedItemKey)
            assertEquals("s1", InspectorNavigation.sessionId)
        }
    }

    @Test fun inspectorRevealScrollsActualTranscriptToOffscreenItem() {
        val state = Pocket.chat.value!!.copy(items = (0..35).map { Item.User("u$it", "Message $it") })
        rule.setContent { PocketTheme { InspectorChatSurface("s1") { Transcript(state, {}) } } }
        rule.onNodeWithText("Message 35").assertIsDisplayed()
        rule.onNodeWithText("Message 0").assertDoesNotExist()
        rule.runOnIdle { InspectorNavigation.reveal("s1", "u0") }
        rule.onNodeWithText("Message 0").assertIsDisplayed()
    }

    @Test fun inspectorRevealOpensCollapsedProcessAndPicksExactMemberWithoutOpeningTool() {
        val first = Item.Tool("t1", "c1", "read", "{}", ToolStatus.Ok, turn = 1, step = 1)
        val second = Item.Tool("t2", "c2", "edit", "{}", ToolStatus.Ok, turn = 1, step = 2)
        val state = Pocket.chat.value!!.copy(
            items = listOf(Item.User("u1", "question"), first, second, Item.Text("a1", "answer", false, turn = 1, step = 2)),
            turns = listOf(TurnRecord(1, 1, 8, 1000, 5000, "completed")),
        )
        var opened: Item.Tool? = null
        rule.setContent { PocketTheme { InspectorChatSurface("s1") { Transcript(state, { opened = it }) } } }
        rule.onNodeWithTag("processTool:t2").assertDoesNotExist()
        rule.runOnIdle { InspectorNavigation.reveal("s1", "t2") }
        rule.onNodeWithTag("processTool:t2").assertIsDisplayed()
        rule.runOnIdle { InspectorNavigation.begin("s1") }
        rule.onNodeWithTag("processTool:t2").performTouchInput { click() }
        rule.runOnIdle {
            assertEquals("t2", InspectorNavigation.pickedItemKey)
            assertFalse(InspectorNavigation.picking)
            assertNull(opened)
        }
    }

    @Test fun inspectorSessionSwitchDiscardsPickerAndReveal() {
        val state = mutableStateOf(Pocket.chat.value!!)
        rule.setContent {
            PocketTheme { InspectorChatSurface(state.value.sessionId) { Transcript(state.value, {}) } }
        }
        rule.runOnIdle {
            InspectorNavigation.begin("s1")
            InspectorNavigation.revealItemKey = "a1"
            state.value = state.value.copy(sessionId = "s2")
        }
        rule.runOnIdle {
            assertNull(InspectorNavigation.sessionId)
            assertNull(InspectorNavigation.pickedItemKey)
            assertNull(InspectorNavigation.revealItemKey)
            assertFalse(InspectorNavigation.picking)
        }
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
        val proc = ChatRow.Process("p1", listOf(tool), listOf(first, last), "已调用工具", true, true,
            members = members)
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
        rule.onNodeWithTag("processHeader:p1").assertIsDisplayed()
    }

    @Test fun reasoningPreviewUsesTheLatestCompletedParagraphWhileStreaming() {
        assertEquals("", reasoningPreview("partial first line", true))
        assertEquals("first", reasoningPreview("**first**\n\nunfinished", true))
        assertEquals("second", reasoningPreview("first\n\nsecond\ncontinued", true))
        assertEquals("first", reasoningPreview("**first**\n\nsecond", false))
    }
    @Test fun failedTurnCannotFoldAwayItsProcessGroups() {
        val failure = Item.Tool("failed", "c1", "bash", "{}", ToolStatus.Error, error = "permission denied", turn = 1, step = 1)
        val state = ChatState("failed-session", loaded = true, items = listOf(failure),
            turns = listOf(TurnRecord(1, startSeq = 1, endSeq = 4, startedAt = 1000, endedAt = 2000, endReason = "error")))
        rule.setContent { PocketTheme { Transcript(state, {}) } }
        rule.onNodeWithTag("processHeader:turn-1-process").assertHasNoClickAction()
        rule.onNodeWithTag("processHeader:failed-group").assertIsDisplayed().performClick()
        rule.onNodeWithTag("processTool:failed").assertIsDisplayed()
        rule.onNodeWithText("permission denied").assertIsDisplayed()
    }

    @Test fun wholeTurnDisclosureKeepsFinalAnswerAndIndependentProcessGroups() {
        val first = Item.Reasoning("r1", "First preview\n\nFirst details", 1000, false, turn = 1, step = 1)
        val intermediate = Item.Text("a1", "Let me inspect the project", false, turn = 1, step = 1)
        val tool = Item.Tool("t1", "c1", "read", "{}", ToolStatus.Ok, result = "file contents", turn = 1, step = 2)
        val final = Item.Text("a2", "Final project overview", false, turn = 1, step = 3)
        val state = ChatState("completed-session", loaded = true, items = listOf(first, intermediate, tool, final),
            turns = listOf(TurnRecord(1, startSeq = 1, endSeq = 8, startedAt = 1000, endedAt = 7000)))
        rule.setContent { PocketTheme { Transcript(state, {}) } }
        rule.onNodeWithText(final.text).assertIsDisplayed()
        rule.onNodeWithText(intermediate.text).assertDoesNotExist()
        rule.onNodeWithTag("processHeader:r1-group").assertDoesNotExist()
        rule.onNodeWithTag("processHeader:turn-1-process").performClick()
        rule.onNodeWithText(intermediate.text).assertIsDisplayed()
        rule.onNodeWithTag("processHeader:r1-group").assertIsDisplayed()
        rule.onNodeWithTag("processHeader:t1-group").assertIsDisplayed()
        rule.onNodeWithTag("reasoningBody:r1").assertDoesNotExist()
        rule.onNodeWithTag("processHeader:r1-group").performClick()
        rule.onNodeWithTag("reasoningHeader:r1").performClick()
        rule.onNodeWithTag("reasoningBody:r1").assertIsDisplayed()
        rule.onNodeWithTag("processTool:t1").assertDoesNotExist()
        rule.onNodeWithTag("processHeader:turn-1-process").performClick()
        rule.onNodeWithText(final.text).assertIsDisplayed()
        rule.onNodeWithText(intermediate.text).assertDoesNotExist()
    }

    @Test fun assistantCompletionActionsWaitForHostTurnEnd() {
        val answer = Item.Text("answer", "Project overview", false, messageId = "m", turn = 1, step = 1)
        val state = mutableStateOf(ChatState("turn-actions", loaded = true, running = true,
            items = listOf(answer), turns = listOf(TurnRecord(1, startSeq = 1, startedAt = 1000))))
        rule.setContent { PocketTheme { Transcript(state.value, {}) } }
        rule.onNodeWithTag("messageCopy", useUnmergedTree = true).assertDoesNotExist()
        rule.runOnIdle {
            state.value = state.value.copy(running = false,
                turns = listOf(TurnRecord(1, startSeq = 1, endSeq = 3, startedAt = 1000, endedAt = 2000)))
        }
        rule.onNodeWithTag("messageCopy", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun runningStatusSurvivesReasoningAndToolTransitionsUntilTurnEnds() {
        val state = mutableStateOf(ChatState("live-session", loaded = true, running = true,
            items = listOf(Item.Reasoning("r", "Working\n", null, true, turn = 1, step = 1)),
            turns = listOf(TurnRecord(1, startSeq = 1, startedAt = 1000))))
        rule.setContent { PocketTheme { Transcript(state.value, {}) } }
        rule.onNodeWithTag("runningStatus").assertIsDisplayed()
        rule.runOnIdle {
            state.value = state.value.copy(items = listOf(
                Item.Tool("t", "c", "bash", "{}", ToolStatus.Running, turn = 1, step = 1)))
        }
        rule.onNodeWithTag("runningStatus").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(running = false) }
        rule.onNodeWithTag("runningStatus").assertDoesNotExist()
    }

    @Test fun completedTurnWithoutProcessDoesNotOfferEmptyDisclosure() {
        val proc = ChatRow.Process("empty", emptyList(), emptyList(), "已完成", true, false,
            turnSummary = true, canCollapse = false)
        rule.setContent { PocketTheme { ProcessGroupRow(proc) {} } }
        rule.onNodeWithTag("processHeader:empty").assertHasNoClickAction()
        rule.onNodeWithText("已完成").assertIsDisplayed()
    }
}
