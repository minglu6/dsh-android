package dev.dsh.pocket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.StepRecord
import dev.dsh.pocket.protocol.StepUsage
import dev.dsh.pocket.protocol.ToolStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
class TrajectoryInteractionTest {
    @get:Rule val rule = createComposeRule()

    private val readArgs = """{"path":"src/config.kt","offset":12,"limit":40}"""
    private val readResult = "12: val enabled = true\n13: val retries = 3"
    private val bashArgs = """{"command":"git status --short","timeout":30}"""
    private val bashResult = " M src/config.kt"
    private val secondConfig = """{"provider":"deepseek","model":"deepseek-v4.1-expert","temperature":0.2}"""
    private val rowKeys = listOf("u1", "a10", "read-tool", "u2", "a20", "bash-tool")

    // Local completed history: no global chat mutation, live timers, history paging, or Host RPC.
    private fun state() = ChatState(
        sessionId = "trajectory-interaction-fixture",
        loaded = true,
        hasMore = false,
        items = listOf(
            Item.User("u1", "检查配置文件", at = 1_000),
            Item.Text("t10.0", "", false),
            Item.Tool(
                "read-tool", "read-call", "read", readArgs, ToolStatus.Ok,
                result = readResult, startedAt = 1_200, endedAt = 1_240,
                turn = 1, step = 1,
            ),
            Item.User("u2", "再检查工作区变更", at = 2_000),
            Item.Text("t20.0", "接着检查工作区。", false),
            Item.Tool(
                "bash-tool", "bash-call", "bash", bashArgs, ToolStatus.Ok,
                result = bashResult, startedAt = 2_300, endedAt = 2_380,
                turn = 2, step = 1,
            ),
        ),
        steps = listOf(
            StepRecord(
                seq = 10, turn = 1, step = 1, stepStartTime = 1_010, messageTime = 1_180,
                replyText = "", reasoningText = "", usage = StepUsage(inputTokens = 120, outputTokens = 1297),
                provider = "deepseek", model = "deepseek-v4.1-flash", toolCallIds = listOf("read-call"),
                requestConfigJson = """{"provider":"deepseek","model":"deepseek-v4.1-flash"}""",
            ),
            StepRecord(
                seq = 20, turn = 2, step = 1, stepStartTime = 2_010, messageTime = 2_280,
                firstTokenTime = 2_110,
                replyText = "接着检查工作区。", reasoningText = "", usage = StepUsage(inputTokens = 240, outputTokens = 24),
                provider = "deepseek", model = "deepseek-v4.1-expert", toolCallIds = listOf("bash-call"),
                requestConfigJson = secondConfig,
            ),
        ),
    )

    @Before fun setUp() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
    }

    private fun show() {
        val fixture = state()
        rule.setContent {
            PocketTheme {
                Box(Modifier.fillMaxSize().background(T.Bg)) { TrajectoryView(fixture) }
            }
        }
    }

    private fun row(key: String) = rule.onNodeWithTag("trajectory-row-$key")

    private fun tab(label: String) = rule.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
    )

    private fun assertCopiedJson(expected: String) {
        rule.onNodeWithTag("trajectory-json-copy").performClick()
        rule.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(copied))
        }
    }

    private fun assertToolDetail(key: String, args: String, result: String, turn: Int) {
        show()
        row(key).assertIsDisplayed().performClick()
        rule.onNodeWithText("${TrajL.turnLabel(turn)} · ${TrajL.groupStep(1)}").assertIsDisplayed()
        tab(TrajL.tabPayload).performClick()
        assertCopiedJson(args)
        tab(TrajL.tabResult).performClick()
        rule.onNodeWithText(result).assertIsDisplayed()
    }

    @Test fun readRowOpensItsFullArgumentsAndResult() {
        assertToolDetail("read-tool", readArgs, readResult, turn = 1)
    }

    @Test fun secondTurnToolOpensItsOwnArgumentsAndResult() {
        assertToolDetail("bash-tool", bashArgs, bashResult, turn = 2)
    }

    @Test fun requestDotOpensTheMatchingInspectorInsteadOfTheAssistantRow() {
        show()
        rule.onNodeWithContentDescription(TrajL.requestLabel(2), useUnmergedTree = true)
            .assertIsDisplayed().performTouchInput { click() }
        rule.onNodeWithText(TrajL.requestLabel(2)).assertIsDisplayed()
        rule.onNodeWithText("deepseek-v4.1-expert").assertIsDisplayed()
        tab(TrajL.tabPayload).assertDoesNotExist()
        tab(TrajL.tabOptions).performClick()
        assertCopiedJson(secondConfig)
    }

    @Test fun searchFiltersRowsByNonPrimaryArgumentAndClearingRestoresBothTurns() {
        show()
        rowKeys.forEach { row(it).assertIsDisplayed() }
        val search = rule.onNodeWithContentDescription(TrajL.toolbarSearch)
        search.performTextInput("limit")
        row("read-tool").assertIsDisplayed()
        rowKeys.filterNot { it == "read-tool" }.forEach { row(it).assertDoesNotExist() }

        search.performTextReplacement("not-in-this-history")
        rowKeys.forEach { row(it).assertDoesNotExist() }
        search.performTextClearance()
        rowKeys.forEach { row(it).assertIsDisplayed() }
    }

    @Test fun callsFoldAndUnfoldWithoutHidingUserOrAssistantRows() {
        show()
        rule.onNodeWithText(TrajL.toolbarCalls).performClick()
        row("read-tool").assertDoesNotExist()
        row("bash-tool").assertDoesNotExist()
        listOf("u1", "a10", "u2", "a20").forEach { row(it).assertIsDisplayed() }
        rule.onNodeWithContentDescription(TrajL.requestLabel(2), useUnmergedTree = true).assertIsDisplayed()

        rule.onNodeWithText(TrajL.toolbarCalls).performClick()
        rowKeys.forEach { row(it).assertIsDisplayed() }
        row("bash-tool").performClick()
        tab(TrajL.tabResult).performClick()
        rule.onNodeWithText(bashResult).assertIsDisplayed()
    }

    @Test fun turnsFoldAndUnfoldWhilePreservingTheCallsFoldState() {
        show()
        rule.onNodeWithText(TrajL.toolbarCalls).performClick()
        rule.onNodeWithText(TrajL.toolbarTurns).performClick()
        rowKeys.forEach { row(it).assertDoesNotExist() }
        rule.onNodeWithText(TrajL.turnLabel(1)).assertIsDisplayed()
        rule.onNodeWithText(TrajL.turnLabel(2)).assertIsDisplayed()

        rule.onNodeWithText(TrajL.toolbarTurns).performClick()
        listOf("u1", "a10", "u2", "a20").forEach { row(it).assertIsDisplayed() }
        row("read-tool").assertDoesNotExist()
        row("bash-tool").assertDoesNotExist()
        rule.onNodeWithText(TrajL.toolbarCalls).performClick()
        rowKeys.forEach { row(it).assertIsDisplayed() }
    }

    @Test fun toolOnlyAssistantPreviewOpensOnlyItsOwnCall() {
        show()
        row("a10").performClick()
        rule.onNodeWithText("1297 tok").assertIsDisplayed()
        rule.onNodeWithTag("trajectory-preview-bash-tool").assertDoesNotExist()
        rule.onNodeWithTag("trajectory-preview-read-tool").assertIsDisplayed().performClick()
        tab(TrajL.tabPayload).performClick()
        assertCopiedJson(readArgs)
        rule.onNodeWithContentDescription(TrajL.detailsClose).performClick()
        row("a10").assertIsDisplayed()
    }

    @Test fun assistantTimingNavigatesToRequestTimingAndDotResetsToOverview() {
        show()
        row("a20").performClick()
        tab(TrajL.tabTiming).assertDoesNotExist()
        rule.onNodeWithText(TrajL.timingRequest).performClick()
        tab(TrajL.tabTiming).assertIsSelected()
        rule.onNodeWithText("100 毫秒").assertIsDisplayed()
        rule.onNodeWithText("170 毫秒").assertIsDisplayed()
        rule.onNodeWithContentDescription(TrajL.detailsClose).performClick()
        rule.onNodeWithContentDescription(TrajL.requestLabel(1), useUnmergedTree = true).performClick()
        tab(TrajL.tabSummary).assertIsSelected()
        rule.onNodeWithText("deepseek-v4.1-flash").assertIsDisplayed()
    }

    @Test fun assistantSourceOpensRequestOverview() {
        show()
        row("a20").performClick()
        rule.onNodeWithText(TrajL.requestLabel(2)).performClick()
        tab(TrajL.tabSummary).assertIsSelected()
        rule.onNodeWithText("deepseek-v4.1-expert").assertIsDisplayed()
    }

}
