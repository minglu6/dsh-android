package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule as runtimeComposeRule
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.TurnOutlineEntry
import dev.dsh.pocket.protocol.TurnRecord
import org.junit.After
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
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class TranscriptNavigationTest {
    @get:Rule val rule = runtimeComposeRule()

    @Before fun setup() {
        installTestIdlePolicy()
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        InspectorNavigation.reset()
    }
    @After fun cleanup() { InspectorNavigation.reset() }

    private fun items(turns: IntRange): List<Item> = turns.flatMap { turn ->
        listOf(Item.User("u$turn", "Question $turn", seq = turn * 10L + 1),
            Item.Text("a$turn", "Answer $turn", false, seq = turn * 10L + 2, turn = turn))
    }
    private fun records(turns: IntRange) = turns.map { TurnRecord(it, startSeq = it * 10L, endSeq = it * 10L + 9) }
    private fun state(id: String, turns: IntRange, earliest: Int = turns.first) = ChatState(
        id, loaded = true, items = items(turns), turns = records(turns),
        firstSeq = turns.first * 10L, hasMore = earliest < turns.first,
        turnOutline = (earliest..turns.last).map { TurnOutlineEntry(it, it * 10L, "Question $it", "Answer $it") },
    )
    private fun jump(turn: Int) {
        rule.onNodeWithTag("transcriptTurnNavigator").performScrollToIndex(0)
        rule.onNodeWithTag("transcript-turn-$turn").performClick()
    }

    @Test fun readingOlderOutputPausesFollowAndReturnToBottomResumesIt() {
        val chat = mutableStateOf(state("reading", 1..20))
        rule.setContent { PocketTheme { Transcript(chat.value, {}) } }
        rule.onNodeWithText("Answer 20").assertIsDisplayed()
        rule.onNodeWithTag("transcriptToBottom").assertDoesNotExist()
        rule.onNodeWithTag("transcriptHistory").performTouchInput { swipeDown() }
        rule.onNodeWithTag("transcriptToBottom").assertIsDisplayed()
        rule.runOnIdle { chat.value = state("reading", 1..21) }
        rule.onNodeWithText("Answer 21").assertDoesNotExist()
        rule.onNodeWithTag("transcriptToBottom").performClick()
        rule.onNodeWithText("Answer 21").assertIsDisplayed()
        rule.onNodeWithTag("transcriptToBottom").assertDoesNotExist()
        rule.runOnIdle { chat.value = state("reading", 1..22) }
        rule.onNodeWithText("Answer 22").assertIsDisplayed()
    }

    @Test fun selectingLoadedTurnJumpsToItsPromptWithoutFollowingNewOutput() {
        val chat = mutableStateOf(state("loaded", 1..20))
        rule.setContent { PocketTheme { Transcript(chat.value, {}) } }
        jump(1)
        rule.onNodeWithText("Question 1").assertIsDisplayed()
        rule.onNodeWithTag("transcript-turn-1").assertIsSelected()
        rule.onNodeWithTag("transcriptToBottom").assertIsDisplayed()
        rule.runOnIdle { chat.value = state("loaded", 1..21) }
        rule.onNodeWithText("Question 1").assertIsDisplayed()
        rule.onNodeWithText("Answer 21").assertDoesNotExist()
    }

    @Test fun unloadedTurnPagesUntilItsRealAnchorArrives() {
        val chat = mutableStateOf(state("unloaded", 10..20, earliest = 1))
        var pages = 0
        rule.setContent { PocketTheme { Transcript(chat.value, {}, onLoadOlder = {
            pages++
            chat.value = if (pages == 1) chat.value.copy(firstSeq = 50)
                else state("unloaded", 1..20)
        }) } }
        jump(1)
        rule.onNodeWithText("Question 1").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, pages) }
        rule.onNodeWithTag("transcript-turn-1").assertIsSelected()
    }

    @Test fun partiallyLoadedTurnStillFetchesItsOpeningBeforeLanding() {
        val initial = state("partial", 10..20, earliest = 1)
        val chat = mutableStateOf(initial.copy(
            items = listOf(Item.Text("partial-answer", "Partial first-turn output", false, seq = 16, turn = 1)) + initial.items,
            turns = listOf(TurnRecord(1, startSeq = 10, endSeq = 19)) + initial.turns,
            firstSeq = 16,
        ))
        var pages = 0
        rule.setContent { PocketTheme { Transcript(chat.value, {}, onLoadOlder = {
            pages++
            chat.value = chat.value.copy(items = items(1..1) + initial.items, firstSeq = 10, hasMore = false)
        }) } }
        jump(1)
        rule.onNodeWithText("Question 1").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, pages) }
    }

    @Test fun failedHistoryJumpStopsAndCanBeRetriedExplicitly() {
        val chat = mutableStateOf(state("failed", 10..20, earliest = 1))
        var pages = 0
        rule.setContent { PocketTheme { Transcript(chat.value, {}, onLoadOlder = {
            pages++
            chat.value = chat.value.copy(loadingOlder = true)
        }) } }
        jump(1)
        rule.runOnIdle { assertEquals(1, pages); chat.value = chat.value.copy(loadingOlder = false) }
        rule.runOnIdle { chat.value = chat.value.copy(items = items(10..21), turns = records(10..21)) }
        rule.runOnIdle { assertEquals(1, pages) }
        jump(1)
        rule.runOnIdle { assertEquals(2, pages) }
    }

    @Test fun returningToBottomCancelsPendingTurnLandingWhenHistoryArrivesLate() {
        val chat = mutableStateOf(state("cancelled", 10..20, earliest = 1))
        rule.setContent { PocketTheme { Transcript(chat.value, {}, onLoadOlder = {
            chat.value = chat.value.copy(loadingOlder = true)
        }) } }
        jump(1)
        rule.onNodeWithTag("transcriptHistory").performTouchInput { swipeDown() }
        rule.onNodeWithTag("transcriptToBottom").performClick()
        rule.runOnIdle { chat.value = state("cancelled", 1..20) }
        rule.onNodeWithText("Answer 20").assertIsDisplayed()
        rule.onNodeWithText("Question 1").assertDoesNotExist()
    }

    @Test fun switchingSessionsDiscardsThePendingNavigation() {
        val chat = mutableStateOf(state("old", 10..20, earliest = 1))
        rule.setContent { PocketTheme { Transcript(chat.value, {}, onLoadOlder = {
            chat.value = chat.value.copy(loadingOlder = true)
        }) } }
        jump(1)
        rule.runOnIdle { chat.value = state("new", 30..40) }
        rule.onNodeWithText("Answer 40").assertIsDisplayed()
        rule.onNodeWithText("Question 1").assertDoesNotExist()
        rule.onNodeWithTag("transcriptToBottom").assertDoesNotExist()
    }
}
