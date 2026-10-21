package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule as runtimeComposeRule
import dev.dsh.pocket.protocol.Item
import dev.dsh.pocket.protocol.jsonOf
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
class HistoryViewsPagingTest {
    @get:Rule val rule = runtimeComposeRule()

    @Before fun setup() {
        installTestIdlePolicy()
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.chat.value = null
        InspectorNavigation.reset()
    }

    @After fun cleanup() {
        Pocket.chat.value = null
        InspectorNavigation.reset()
    }

    private fun pull(tag: String) {
        rule.onNodeWithTag(tag).performTouchInput {
            swipe(Offset(centerX, height * .15f), Offset(centerX, height * .9f), 600)
        }
        rule.waitForIdle()
    }

    private fun messages(prefix: String, count: Int) = (0 until count).map {
        Item.User("$prefix-$it", "$prefix message $it")
    }

    private fun log(session: String = "inspector", count: Int = 1) = ChatState(
        session, loaded = true, hasMore = true, firstSeq = 100,
        inspectorRecords = (0 until count).map {
            jsonOf("seq" to 100 + it, "time" to 1234L, "type" to "custom/record-$it", "data" to jsonOf("value" to "row-$it"))
        },
    )

    @Test fun trajectoryFillsShortAndNonRenderedPagesAndStopsAtTheLastPage() {
        val state = mutableStateOf(ChatState("short", loaded = true, hasMore = true, firstSeq = 100))
        var requests = 0
        rule.setContent { PocketTheme {
            TrajectoryView(state.value, onLoadOlder = {
                requests++
                state.value = if (requests == 1) state.value.copy(firstSeq = 50)
                else state.value.copy(firstSeq = 1, hasMore = false, items = messages("old", 1))
            })
        } }
        rule.onNodeWithTag("trajectory-row-old-0").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, requests) }
        pull("trajectoryHistoryPull")
        rule.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun trajectoryWaitsForTheNewPageLayoutBeforeDecidingTheWindowIsStillShort() {
        val state = mutableStateOf(ChatState("fill", loaded = true, hasMore = true, firstSeq = 100))
        var requests = 0
        rule.setContent { PocketTheme {
            TrajectoryView(state.value, onLoadOlder = {
                requests++
                state.value = state.value.copy(firstSeq = 50, items = messages("loaded", 50))
            })
        } }
        rule.onNodeWithTag("trajectory-row-loaded-0").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun trajectoryFailureDoesNotSpinAndANewPullRetriesTheSameCursorOnce() {
        val state = mutableStateOf(ChatState("retry", loaded = true, hasMore = true, firstSeq = 100,
            items = messages("current", 1)))
        var requests = 0
        rule.setContent { PocketTheme {
            TrajectoryView(state.value, onLoadOlder = {
                requests++
                state.value = state.value.copy(loadingOlder = true)
            })
        } }
        rule.runOnIdle { assertEquals(1, requests); state.value = state.value.copy(loadingOlder = false) }
        rule.runOnIdle { state.value = state.value.copy(items = state.value.items + Item.Text("live", "live output", true)) }
        rule.runOnIdle { assertEquals(1, requests) }
        pull("trajectoryHistoryPull")
        rule.runOnIdle { assertEquals(2, requests) }
        pull("trajectoryHistoryPull")
        rule.runOnIdle { assertEquals(2, requests); state.value = state.value.copy(loadingOlder = false) }
        rule.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun trajectoryBackwardBoundaryPreservesTheVisibleRowAndDoesNotFollowLiveOutput() {
        val original = messages("current", 50)
        val state = mutableStateOf(ChatState("anchor", loaded = true, hasMore = true, firstSeq = 100, items = original))
        var requests = 0
        rule.setContent { PocketTheme {
            TrajectoryView(state.value, onLoadOlder = {
                requests++
                state.value = state.value.copy(loadingOlder = true)
            })
        } }
        val list = rule.onNodeWithTag("trajectoryHistory")
        list.performScrollToIndex(20)
        rule.runOnIdle { assertEquals(0, requests) }
        list.performScrollToIndex(0)
        rule.runOnIdle { assertEquals(1, requests) }
        val before = rule.onNodeWithTag("trajectory-row-current-0").fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle {
            state.value = state.value.copy(items = messages("earlier", 5) + original, firstSeq = 50,
                hasMore = false, loadingOlder = false)
        }
        val after = rule.onNodeWithTag("trajectory-row-current-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 1f)
        rule.runOnIdle { state.value = state.value.copy(items = state.value.items + Item.Text("new", "new live output", true)) }
        rule.onNodeWithTag("trajectory-row-current-0").assertIsDisplayed()
        rule.onNodeWithTag("trajectory-row-current-49").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun trajectorySwitchResetsTheFailedBoundaryAndSelection() {
        val state = mutableStateOf(ChatState("A", loaded = true, hasMore = true, firstSeq = 100, items = messages("A", 1)))
        var requests = 0
        rule.setContent { PocketTheme { TrajectoryView(state.value, onLoadOlder = { requests++ }) } }
        rule.runOnIdle { assertEquals(1, requests) }
        rule.onNodeWithTag("trajectory-row-A-0").performClick()
        rule.onNodeWithContentDescription(TrajL.detailsClose).assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(sessionId = "B", items = messages("B", 1)) }
        rule.onNodeWithContentDescription(TrajL.detailsClose).assertDoesNotExist()
        rule.onNodeWithTag("trajectory-row-B-0").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun inspectorDoesNotPageOnOpenOrForwardScrollAndPullIsSingleFlight() {
        Pocket.chat.value = log(count = 60)
        var requests = 0
        rule.setContent { PocketTheme {
            SessionInspectorSheet("inspector", onDismiss = {}, onLoadOlder = {
                requests++
                Pocket.chat.value = Pocket.chat.value!!.copy(loadingOlder = true)
            })
        } }
        rule.runOnIdle { assertEquals(0, requests) }
        rule.onNodeWithTag("inspectorRows").performScrollToIndex(0)
        rule.onNodeWithTag("inspectorRows").performTouchInput { swipeUp() }
        rule.runOnIdle { assertEquals(0, requests) }
        rule.onNodeWithTag("inspectorRows").performScrollToIndex(0)
        pull("inspectorHistoryPull")
        rule.runOnIdle { assertEquals(1, requests) }
        pull("inspectorHistoryPull")
        rule.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun inspectorFailureDoesNotSpinAndPullRetriesWithoutClearingSelection() {
        Pocket.chat.value = log()
        var requests = 0
        rule.setContent { PocketTheme {
            SessionInspectorSheet("inspector", onDismiss = {}, onLoadOlder = {
                requests++
                Pocket.chat.value = Pocket.chat.value!!.copy(loadingOlder = true)
            })
        } }
        rule.onNodeWithText("custom/record-0").performClick()
        rule.onNodeWithTag("inspectorDetail").assertIsDisplayed()
        pull("inspectorHistoryPull")
        rule.runOnIdle { assertEquals(1, requests); Pocket.chat.value = Pocket.chat.value!!.copy(loadingOlder = false) }
        rule.runOnIdle { Pocket.chat.value = Pocket.chat.value!!.copy(items = messages("live", 1)) }
        rule.runOnIdle { assertEquals(1, requests) }
        pull("inspectorHistoryPull")
        rule.runOnIdle { assertEquals(2, requests) }
        rule.onNodeWithTag("inspectorDetail").assertIsDisplayed()
        rule.runOnIdle { Pocket.chat.value = Pocket.chat.value!!.copy(hasMore = false, loadingOlder = false) }
        pull("inspectorHistoryPull")
        rule.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun inspectorBackwardGesturePagesWithoutMovingTheAnchorAndFollowLatestStillWorks() {
        val original = log(count = 60)
        Pocket.chat.value = original
        var requests = 0
        rule.setContent { PocketTheme {
            SessionInspectorSheet("inspector", onDismiss = {}, onLoadOlder = {
                requests++
                Pocket.chat.value = Pocket.chat.value!!.copy(loadingOlder = true)
            })
        } }
        rule.onNodeWithText("custom/record-59").assertIsDisplayed()
        // Move toward older rows; no toolbar pagination action is involved.
        repeat(6) { pull("inspectorHistoryPull") }
        rule.runOnIdle { assertEquals(1, requests) }
        val before = rule.onNodeWithText("custom/record-0").fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle {
            Pocket.chat.value = original.copy(firstSeq = 50, hasMore = false,
                inspectorRecords = listOf(jsonOf("seq" to 50, "time" to 1000L, "type" to "custom/older", "data" to jsonOf())) + original.inspectorRecords)
        }
        val after = rule.onNodeWithText("custom/record-0").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 1f)
        rule.onNodeWithText("custom/record-59").assertDoesNotExist()
        rule.onNodeWithTag("inspectorFollowLatest").performClick()
        rule.onNodeWithText("custom/record-59").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun inspectorEmptyFilterAndGroupedViewsCanPullWithoutAutomaticallyDrainingHistory() {
        Pocket.chat.value = log()
        var requests = 0
        rule.setContent { PocketTheme {
            SessionInspectorSheet("inspector", onDismiss = {}, onLoadOlder = { requests++ })
        } }
        rule.onNodeWithTag("inspectorFilterButton").performClick()
        rule.onNodeWithTag("inspectorFilter").performTextInput("missing")
        rule.onNodeWithTag("inspectorApplyFilter").performClick()
        rule.runOnIdle { assertEquals(0, requests) }
        pull("inspectorHistoryPull")
        rule.runOnIdle { assertEquals(1, requests) }
        rule.onNodeWithTag("inspectorPresentation").performClick()
        rule.onNodeWithTag("inspectorGroups").performClick()
        rule.runOnIdle { assertEquals(1, requests) }
        pull("inspectorHistoryPull")
        rule.runOnIdle { assertEquals(2, requests) }
    }

    @Test fun inspectorSessionSwitchResetsPagingAndDoesNotStartAnAutomaticRequest() {
        val requested = mutableStateOf("A")
        Pocket.chat.value = log("A")
        var requests = 0
        rule.setContent { PocketTheme {
            SessionInspectorSheet(requested.value, onDismiss = {}, onLoadOlder = { requests++ })
        } }
        pull("inspectorHistoryPull")
        rule.runOnIdle { assertEquals(1, requests); Pocket.chat.value = log("B"); requested.value = "B" }
        rule.runOnIdle { assertEquals(1, requests) }
        pull("inspectorHistoryPull")
        rule.runOnIdle { assertEquals(2, requests) }
        rule.onNodeWithTag("inspectorLoadEarlier").assertDoesNotExist()
    }
}
