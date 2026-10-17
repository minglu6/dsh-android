package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import dev.dsh.pocket.protocol.Approval
import dev.dsh.pocket.protocol.ModelCatalog
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.UserQuestion
import dev.dsh.pocket.protocol.UserQuestionItem
import dev.dsh.pocket.protocol.UserQuestionOption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
        Pocket.model.value = emptyMap()
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
        rule.onNodeWithText("B").performClick()
        assertEquals(0, calls)
        rule.onNodeWithText("上一题").performClick()
        rule.onNodeWithText(L.nextQuestion()).performClick()
        rule.onNodeWithText("提交答案").performClick()
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
        rule.onNodeWithText("继续讨论").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, answers)
    }

    @Test fun recommendedDefaultPreservesWireLabelAndRequiresExplicitSubmit() {
        val label = "Use existing setup （ReCoMmEnDeD）"
        val q = UserQuestion("q", "s1", "call", listOf(UserQuestionItem("one", "Choose",
            options = listOf(UserQuestionOption(label), UserQuestionOption("Other")))))
        var submitted: List<JsonObject>? = null
        var submittedId: String? = null
        rule.setContent { PocketTheme {
            UserQuestionCard(q, mutableStateMapOf(), onDismiss = {}, onAnswer = { id, answers, _ ->
                submittedId = id; submitted = answers
            })
        } }
        rule.onNodeWithTag("questionOption:0").assertIsSelected()
        assertNull(submitted)
        rule.onNodeWithText("提交答案").performClick()
        assertEquals("call", submittedId)
        assertEquals(label, submitted!!.single()["selected"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun onlyMarkedFirstOptionCreatesDefaultAndExplicitEmptyDraftWins() {
        val item = UserQuestionItem("one", "Choose", options = listOf(
            UserQuestionOption("First"), UserQuestionOption("Second (推荐)")))
        assertFalse(initialQuestionDraft(item).answered)
        assertTrue(parseRecommendedLabel("First (recommended)  ").second)
        assertTrue(parseRecommendedLabel("First（推荐）").second)
        assertFalse(parseRecommendedLabel("Recommended approach").second)
        val recommended = item.copy(options = listOf(UserQuestionOption("First (推荐)")))
        val q = UserQuestion("q", "s1", "call", listOf(recommended))
        assertTrue(questionAnswers(q, mapOf("one" to QuestionDraft())).single()["selected"]!!.jsonArray.isEmpty())
    }

    @Test fun singleSelectAndCustomReplaceEachOtherWhileMultiselectRetainsBoth() {
        val drafts = mutableStateMapOf<String, QuestionDraft>()
        val q = UserQuestion("q", "s1", "call", listOf(
            UserQuestionItem("one", "Single", options = listOf(UserQuestionOption("A (推荐)"))),
            UserQuestionItem("two", "Multiple", multiSelect = true, options = listOf(UserQuestionOption("B"))),
        ))
        var submitted: List<JsonObject>? = null
        rule.setContent { PocketTheme {
            UserQuestionCard(q, drafts, onDismiss = {}, onAnswer = { _, answers, _ -> submitted = answers })
        } }
        rule.onNodeWithTag("questionCustom").performScrollTo().performTextInput("custom one")
        rule.onNodeWithTag("questionOption:0").assertIsNotSelected().performScrollTo().performClick()
        assertEquals(setOf("A (推荐)"), drafts["one"]!!.selected)
        assertEquals("", drafts["one"]!!.custom)
        rule.onNodeWithTag("questionOption:0").performClick()
        rule.onNodeWithTag("questionCustom").performScrollTo().performTextInput(" custom two ")
        assertEquals(setOf("B"), drafts["two"]!!.selected)
        rule.onNodeWithText("提交答案").performClick()
        assertEquals("B", submitted!![1]["selected"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("custom two", submitted!![1]["custom"]!!.jsonPrimitive.content)
    }

    @Test fun finalSubmitChecksEarlierDraftAndSkippingOnlyClearsCurrentAnswer() {
        val drafts = mutableStateMapOf<String, QuestionDraft>()
        val q = UserQuestion("q", "s1", "call", listOf(
            UserQuestionItem("one", "First question", options = listOf(UserQuestionOption("A"))),
            UserQuestionItem("two", "Second question", options = listOf(UserQuestionOption("B"))),
        ))
        var calls = 0
        var submitted: List<JsonObject>? = null
        rule.setContent { PocketTheme {
            UserQuestionCard(q, drafts, onDismiss = {}, onAnswer = { _, answers, _ -> calls++; submitted = answers })
        } }
        rule.onNodeWithText("A").performClick()
        rule.onNodeWithText("B").performClick()
        rule.onNodeWithText("上一题").performClick()
        rule.onNodeWithTag("questionCustom").performScrollTo().performTextInput(" ")
        // External draft changes can occur while a restored page is on the final question.
        rule.runOnIdle { drafts["one"] = QuestionDraft(selected = setOf("A")) }
        rule.onNodeWithText(L.nextQuestion()).performClick()
        rule.runOnIdle { drafts["one"] = QuestionDraft() }
        rule.onNodeWithText("提交答案").performClick()
        assertEquals(0, calls)
        rule.onNodeWithText("First question").assertIsDisplayed()
        rule.onNodeWithText(L.skip()).performClick()
        assertTrue(drafts["one"]!!.skipped)
        assertEquals(setOf("B"), drafts["two"]!!.selected)
        rule.onNodeWithText("提交答案").performClick()
        assertEquals(1, calls)
        assertTrue(submitted!![0]["selected"]!!.jsonArray.isEmpty())
        assertEquals("B", submitted!![1]["selected"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun offlineAndSubmittingDisableEveryAnswerMutationAndFailureRetainsDraft() {
        val online = androidx.compose.runtime.mutableStateOf(false)
        val drafts = mutableStateMapOf<String, QuestionDraft>()
        val q = UserQuestion("q", "s1", null, listOf(UserQuestionItem("one", "Choose",
            options = listOf(UserQuestionOption("A")))))
        var calls = 0
        var settle: ((Boolean) -> Unit)? = null
        rule.setContent { PocketTheme {
            UserQuestionCard(q, drafts, onDismiss = {}, enabled = online.value, onAnswer = { id, _, done ->
                assertEquals("q", id); calls++; settle = done
            })
        } }
        rule.onNodeWithTag("questionOption:0").assertIsNotEnabled().performClick()
        rule.onNodeWithTag("questionCustom").assertIsNotEnabled()
        rule.onNodeWithText(L.skip()).assertIsNotEnabled()
        rule.onNodeWithText("提交答案").assertIsNotEnabled()
        assertTrue(drafts.isEmpty())
        rule.runOnIdle { online.value = true }
        rule.onNodeWithTag("questionCustom").performScrollTo().performTextInput("keep this")
        rule.onNodeWithText("提交答案").performClick()
        rule.onNodeWithTag("questionOption:0").assertIsNotEnabled()
        rule.onNodeWithTag("questionCustom").assertIsNotEnabled()
        rule.onNodeWithText(L.skip()).assertIsNotEnabled()
        rule.onNodeWithText("收起").assertIsNotEnabled()
        rule.runOnIdle { settle!!(false) }
        assertEquals("keep this", drafts["one"]!!.custom)
        rule.onNodeWithText("提交答案").assertIsEnabled().performClick()
        assertEquals(2, calls)
    }

    @Test fun longQuestionScrollsWithoutHidingHeaderOrSubmit() {
        val q = UserQuestion("q", "s1", "call", listOf(UserQuestionItem("one",
            (1..60).joinToString("\n") { "Question context $it" },
            options = listOf(UserQuestionOption("A", description = "Choice detail")))))
        rule.setContent { PocketTheme { UserQuestionCard(q, mutableStateMapOf(), onDismiss = {}) } }
        rule.onNodeWithTag("questionHeader").assertIsDisplayed()
        rule.onNodeWithText("提交答案").assertIsDisplayed()
        rule.onNodeWithTag("questionCustom").performScrollTo()
        rule.onNodeWithTag("questionHeader").assertIsDisplayed()
        rule.onNodeWithText("提交答案").assertIsDisplayed()
    }

    @Test fun collapseAndReopenRetainPageAndDrafts() {
        Pocket.chat.value = ChatState("s1", loaded = true)
        Pocket.userQuestions.value = listOf(UserQuestion("q", "s1", "call", listOf(
            UserQuestionItem("one", "First question", options = listOf(UserQuestionOption("A"))),
            UserQuestionItem("two", "Second question", options = listOf(UserQuestionOption("B"))),
        )))
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithText("A").performClick()
        rule.onNodeWithTag("questionCustom").performScrollTo().performTextInput("saved")
        rule.onNodeWithText("收起").performClick()
        rule.onNodeWithTag("userQuestionCard").assertDoesNotExist()
        rule.onNodeWithText("问题等待回答 · 重新打开").performClick()
        rule.onNodeWithText("Second question").assertIsDisplayed()
        rule.onNodeWithTag("questionCustom").assertTextEquals("saved")
        rule.onNodeWithText("上一题").performClick()
        rule.onNodeWithTag("questionOption:0").assertIsSelected()
    }

    @Test fun restoringQuestionProgressRetainsSkippedSelectedAndCustomAnswers() {
        val restoration = StateRestorationTester(rule)
        val q = UserQuestion("q", "s1", "call", listOf(
            UserQuestionItem("one", "Skip me"),
            UserQuestionItem("two", "Multiple", multiSelect = true, options = listOf(UserQuestionOption("B"))),
            UserQuestionItem("three", "Final question", options = listOf(UserQuestionOption("C (推荐)"))),
        ))
        var submitted: List<JsonObject>? = null
        restoration.setContent { PocketTheme {
            val drafts = rememberSaveable(saver = questionDraftSaver) {
                mutableMapOf<String, SnapshotStateMap<String, QuestionDraft>>()
            }
            UserQuestionCard(q, drafts.getOrPut(q.questionId) { mutableStateMapOf() }, onDismiss = {},
                onAnswer = { _, answers, _ -> submitted = answers })
        } }
        rule.onNodeWithText(L.skip()).performClick()
        rule.onNodeWithText("B").performClick()
        rule.onNodeWithTag("questionCustom").performScrollTo().performTextInput("extra")
        rule.onNodeWithText(L.nextQuestion()).performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Final question").assertIsDisplayed()
        rule.onNodeWithText("提交答案").performClick()
        assertTrue(submitted!![0]["selected"]!!.jsonArray.isEmpty())
        assertNull(submitted!![0]["custom"])
        assertEquals("B", submitted!![1]["selected"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("extra", submitted!![1]["custom"]!!.jsonPrimitive.content)
        assertEquals("C (推荐)", submitted!![2]["selected"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun continuedProjectionRearmsInFlightAnswerWithoutDiscardingDraft() {
        val state = androidx.compose.runtime.mutableStateOf("open")
        val drafts = mutableStateMapOf<String, QuestionDraft>()
        val q = UserQuestion("q", "s1", "call", listOf(UserQuestionItem("one", "Choose",
            options = listOf(UserQuestionOption("A")))))
        var calls = 0
        rule.setContent { PocketTheme {
            UserQuestionCard(q.copy(state = state.value), drafts, onDismiss = {},
                onAnswer = { _, _, _ -> calls++ })
        } }
        rule.onNodeWithText("A").performClick()
        rule.onNodeWithText("提交答案").performClick()
        rule.onNodeWithText("提交答案").assertIsNotEnabled()
        rule.runOnIdle { state.value = "continued" }
        rule.onNodeWithTag("questionOption:0").assertIsSelected()
        rule.onNodeWithText("提交答案").assertIsEnabled().performClick()
        assertEquals(2, calls)
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

    @Test fun disconnectDisablesComposerModelWithoutOpeningPicker() {
        Pocket.chat.value = ChatState("s1", loaded = true)
        Pocket.catalog.value = ModelCatalog.parse(PocketJson.parseToJsonElement("""{
          "default":{"provider":"p","model":"pro"},
          "groups":[{"id":"p","name":"Provider","models":[{"id":"pro","name":"DeepSeek Pro"}]}]
        }"""))
        Pocket.model.value = mapOf("s1" to ModelChoice("p", "pro", "DeepSeek Pro", null))
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("composerModel").assertIsEnabled()
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("DeepSeek Pro")
        rule.runOnIdle { Pocket.status.value = mapOf("p1" to Dot.Offline) }
        rule.onNodeWithTag("composerModel").assertIsNotEnabled().performClick()
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("DeepSeek Pro")
        rule.onNodeWithTag("modelPicker").assertDoesNotExist()
    }

    @Test fun disconnectKeepsSelectModelPlaceholderWhenUncached() {
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("选择模型")
        rule.runOnIdle { Pocket.status.value = mapOf("p1" to Dot.Offline) }
        rule.onNodeWithTag("composerModel").assertIsNotEnabled().performClick()
        rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("选择模型")
        rule.onNodeWithTag("modelPicker").assertDoesNotExist()
    }

    @Test fun disconnectDisablesApprovalUntilConnectionReturns() {
        Pocket.chat.value = ChatState("s1", running = true, loaded = true)
        Pocket.approvals.value = listOf(Approval("approval", "s1", "bash", null, "Review command"))
        rule.setContent { PocketTheme { ChatRoot(onMenu = {}) } }
        rule.onNodeWithText("允许一次").assertIsEnabled()
        rule.runOnIdle { Pocket.status.value = mapOf("p1" to Dot.Offline) }
        rule.onNodeWithText("允许一次").assertIsNotEnabled()
        rule.onNodeWithText("拒绝").assertIsNotEnabled()
        rule.runOnIdle { Pocket.status.value = mapOf("p1" to Dot.Online) }
        rule.onNodeWithText("允许一次").assertIsEnabled()
    }

    @Test fun triggerAndReplacementRespectCaretAndSuffix() {
        assertNull(composerTrigger("mail@example.com", 9))
        assertNull(composerTrigger("path/src", 8))
        assertEquals("goal", composerTrigger("explain /goal", 13)?.query)
        assertEquals("model", composerTrigger("(/model", 7)?.query)
        assertNull(composerTrigger("https://host", 12))
        assertNull(composerTrigger("https:/host", 11))
        assertNull(composerTrigger("name/model", 10))
        assertNull(composerTrigger("中文/model", 8))
        assertNull(composerTrigger("_/model", 7))
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
