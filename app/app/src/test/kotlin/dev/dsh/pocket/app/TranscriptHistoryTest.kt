package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule as runtimeComposeRule
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.ToolStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.After
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
class TranscriptHistoryTest {
    @get:Rule val rule = runtimeComposeRule()

    @Before fun setup() {
        installTestIdlePolicy()
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.transcriptView = TranscriptView.Standard
        InspectorNavigation.reset()
    }
    @After fun cleanup() { InspectorNavigation.reset() }

    @Test
    fun foldedShortWindowLoadsOlderMessagesWithoutAnImpossibleScrollGesture() {
        val state = mutableStateOf(ChatState("short", loaded = true, running = true, hasMore = true, firstSeq = 100,
            items = listOf(Item.User("latest", "最新请求"), Item.Tool("tool", "call", "read", "{}", ToolStatus.Running))))
        var pages = 0
        rule.setContent { PocketTheme {
            Transcript(state.value, {}, onLoadOlder = {
                pages++
                state.value = state.value.copy(items = listOf(Item.User("older", "之前的聊天记录")) + state.value.items,
                    firstSeq = 1, hasMore = false)
            })
        } }
        rule.onNodeWithText("之前的聊天记录").assertIsDisplayed()
        rule.onNodeWithText("最新请求").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, pages) }
    }

    @Test fun pagesContainingOnlyNonRenderedEventsDoNotStrandHistory() {
        val state = mutableStateOf(ChatState("empty-window", loaded = true, hasMore = true, firstSeq = 100))
        var pages = 0
        rule.setContent { PocketTheme {
            Transcript(state.value, {}, onLoadOlder = {
                pages++
                state.value = if (pages == 1) state.value.copy(firstSeq = 50)
                    else state.value.copy(items = listOf(Item.User("original", "最早的问题")), firstSeq = 1, hasMore = false)
            })
        } }
        rule.onNodeWithText("最早的问题").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, pages) }
    }

    @Test fun failedPageDoesNotLoopOnTheSameBoundaryAsLiveOutputChanges() {
        val state = mutableStateOf(ChatState("failed-page", loaded = true, hasMore = true, firstSeq = 100,
            items = listOf(Item.User("latest", "当前请求"))))
        var pages = 0
        rule.setContent { PocketTheme {
            Transcript(state.value, {}, onLoadOlder = { pages++; state.value = state.value.copy(loadingOlder = true) })
        } }
        rule.runOnIdle { assertEquals(1, pages); state.value = state.value.copy(loadingOlder = false) }
        rule.runOnIdle { state.value = state.value.copy(items = state.value.items + Item.Text("live", "继续生成", true)) }
        rule.onNodeWithText("继续生成").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, pages) }
    }

    @Test fun pagingContinuesWhenOlderContentExpandsTheSameTopRow() {
        val state = mutableStateOf(ChatState("growing-row", loaded = true, hasMore = true, firstSeq = 100,
            items = listOf(Item.User("row", "折叠后的最新内容"))))
        var pages = 0
        rule.setContent { PocketTheme {
            Transcript(state.value, {}, onLoadOlder = {
                pages++
                state.value = if (pages == 1) state.value.copy(firstSeq = 50, loadingOlder = true,
                    items = listOf(Item.User("row", "早期工具过程\\n".repeat(100))))
                else state.value.copy(firstSeq = 1, hasMore = false, items = listOf(Item.User("first", "先前的问题")) + state.value.items)
            })
        } }
        rule.runOnIdle { assertEquals(1, pages); state.value = state.value.copy(loadingOlder = false) }
        rule.runOnIdle { assertEquals(2, pages) }
        rule.onNodeWithTag("transcriptHistory").performScrollToIndex(0)
        rule.onNodeWithText("先前的问题").assertIsDisplayed()
    }

    @Test fun reachingTopLoadsHistoryAndDoesNotJumpBackToTheLatestMessage() {
        val messages = (0..29).map { Item.User("u$it", "Message $it") }
        val state = mutableStateOf(ChatState("scroll", loaded = true, hasMore = true, firstSeq = 100, items = messages))
        var pages = 0
        rule.setContent { PocketTheme {
            Transcript(state.value, {}, onLoadOlder = {
                pages++
                state.value = state.value.copy(items = (1..5).map { Item.User("old$it", "Earlier $it") } + messages,
                    firstSeq = 50, hasMore = false)
            })
        } }
        rule.onNodeWithText("Message 29").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, pages) }
        rule.onNodeWithTag("transcriptHistory").performScrollToIndex(0)
        rule.onNodeWithText("Message 0").assertIsDisplayed()
        rule.onNodeWithText("Message 29").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, pages) }
    }

    @Test fun pullRetriesFailedBoundaryOnceWithoutRepeatingBusyOrFinalPages() {
        val state = mutableStateOf(ChatState("pull-retry", loaded = true, hasMore = true, firstSeq = 100,
            items = listOf(Item.User("latest", "当前请求"))))
        var pages = 0
        rule.setContent { PocketTheme {
            Transcript(state.value, {}, onLoadOlder = {
                pages++
                state.value = state.value.copy(loadingOlder = true)
            })
        } }
        rule.runOnIdle { assertEquals(1, pages) }
        rule.onNodeWithTag("transcriptHistory").performTouchInput { swipeDown() }
        rule.runOnIdle { assertEquals(1, pages); state.value = state.value.copy(loadingOlder = false) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, pages) }
        rule.onNodeWithTag("transcriptHistory").performTouchInput { swipeDown() }
        rule.runOnIdle {
            assertEquals(2, pages)
            state.value = state.value.copy(firstSeq = 1, hasMore = false, loadingOlder = false,
                items = listOf(Item.User("older", "之前的记录")) + state.value.items)
        }
        rule.onNodeWithText("之前的记录").assertIsDisplayed()
        rule.onNodeWithTag("transcriptHistory").performTouchInput { swipeDown() }
        rule.runOnIdle { assertEquals(2, pages) }
    }
}
