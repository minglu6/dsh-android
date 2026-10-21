package dev.dsh.pocket.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class TurnNavigatorInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setup() {
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
    }

    private val items = listOf(
        TranscriptTurnMark(3, "Third prompt", "Third response", "user-3", 30),
        TranscriptTurnMark(17, "Missing prompt", "Missing response", seq = 170),
        TranscriptTurnMark(28, "Last prompt", "Last response", "answer-28", 280),
    )

    @Test fun hidesUntilThereAreAtLeastTwoKnownTurns() {
        val shown = mutableStateOf(emptyList<TranscriptTurnMark>())
        rule.setContent { PocketTheme { TranscriptTurnNavigator(shown.value, null, null, {}) } }
        rule.onNodeWithTag("transcriptTurnNavigator").assertDoesNotExist()
        rule.runOnIdle { shown.value = items.take(1) }
        rule.onNodeWithTag("transcriptTurnNavigator").assertDoesNotExist()
        rule.runOnIdle { shown.value = items.take(2) }
        rule.onNodeWithTag("transcriptTurnNavigator").assertIsDisplayed()
    }

    @Test fun loadedAndUnloadedTicksBothNavigateWithTheirRealAnchors() {
        val selected = mutableListOf<TranscriptTurnMark>()
        rule.setContent { PocketTheme { TranscriptTurnNavigator(items, 3, null, { selected += it }) } }
        rule.onNodeWithTag("transcript-turn-3").assertContentDescriptionEquals("跳转到第3轮").performClick()
        rule.onNodeWithTag("transcript-turn-17").assertContentDescriptionEquals("加载并跳转到第17轮").performClick()
        rule.runOnIdle { assertEquals(items.take(2), selected) }
    }

    @Test fun currentAndBusyStatesRemainReadableWithoutAnAnimation() {
        val active = mutableStateOf(3)
        rule.setContent { PocketTheme { TranscriptTurnNavigator(items, active.value, 17, {}) } }
        rule.onNodeWithTag("transcript-turn-3").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "当前轮次"))
        rule.onNodeWithTag("transcript-turn-17").assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正在加载"))
        rule.runOnIdle { active.value = 17 }
        rule.onNodeWithTag("transcript-turn-3").assertIsNotSelected()
        rule.onNodeWithTag("transcript-turn-17").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "正在加载"))
    }

    @Test fun longPressShowsBoundedPreviewWithoutNavigatingAndBackDismisses() {
        val prompt = "A very long question ".repeat(30)
        val response = "A detailed response with enough words to span more than three lines. ".repeat(30)
        val selected = mutableListOf<TranscriptTurnMark>()
        val previewItems = listOf(items[0].copy(prompt = prompt, response = response), items[1])
        rule.setContent { PocketTheme { TranscriptTurnNavigator(previewItems, 3, null, { selected += it }) } }
        rule.onNodeWithTag("transcript-turn-3").performTouchInput { longClick() }
        rule.onNodeWithTag("transcriptTurnPreview").assertIsDisplayed()
        val promptLayouts = mutableListOf<TextLayoutResult>()
        val responseLayouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(prompt).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(promptLayouts) }
        rule.onNodeWithText(response).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(responseLayouts) }
        rule.runOnIdle {
            assertEquals(1, promptLayouts.single().lineCount)
            assertEquals(3, responseLayouts.single().lineCount)
            assertTrue(selected.isEmpty())
        }
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithTag("transcriptTurnPreview").assertDoesNotExist()
        rule.onNodeWithTag("transcript-turn-17").performClick()
        rule.runOnIdle { assertEquals(listOf(items[1]), selected) }
    }

    @Test fun emptyPromptPreviewUsesRealTurnLabelAndOmitsEmptyResponse() {
        rule.setContent { PocketTheme {
            TranscriptTurnNavigator(listOf(items[0], items[1].copy(prompt = "", response = "")), null, null, {})
        } }
        rule.onNodeWithTag("transcript-turn-17").performTouchInput { longClick() }
        rule.onNodeWithTag("transcriptTurnPreview").assertIsDisplayed()
        rule.onNodeWithText("第17轮").assertIsDisplayed()
    }

    @Test fun manyTurnsKeepFixedTouchPitchAndScrollIndependentlyToLastTurn() {
        val many = (1..120).map { TranscriptTurnMark(it * 2, "Prompt $it", "", seq = it * 10L) }
        val selected = mutableListOf<TranscriptTurnMark>()
        rule.setContent { PocketTheme {
            Box(Modifier.height(240.dp)) { TranscriptTurnNavigator(many, 2, null, { selected += it }) }
        } }
        rule.onNodeWithTag("transcript-turn-240").assertDoesNotExist()
        rule.onNodeWithTag("transcriptTurnNavigator").performScrollToIndex(119)
        rule.onNodeWithTag("transcript-turn-240").assertIsDisplayed().performClick()
        rule.onNodeWithTag("transcript-turn-2").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf(many.last()), selected) }
    }

    @Test fun activeTurnFollowsButManualRailScrollingDoesNotSnapBack() {
        val many = (1..100).map { TranscriptTurnMark(it, "Prompt $it", "", seq = it * 10L) }
        val active = mutableStateOf(1)
        rule.setContent { PocketTheme {
            Box(Modifier.height(240.dp)) { TranscriptTurnNavigator(many, active.value, null, {}) }
        } }
        rule.runOnIdle { active.value = 80 }
        rule.onNodeWithTag("transcript-turn-80").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithTag("transcriptTurnNavigator").performScrollToIndex(0)
        rule.onNodeWithTag("transcript-turn-1").assertIsDisplayed()
        rule.onNodeWithTag("transcript-turn-80").assertDoesNotExist()
        rule.runOnIdle { active.value = 90 }
        rule.onNodeWithTag("transcript-turn-90").assertIsDisplayed().assertIsSelected()
    }

    @Test fun activeChangeDoesNotMoveMarksWhileFingerIsDown() {
        val many = (1..100).map { TranscriptTurnMark(it, "Prompt $it", "", seq = it * 10L) }
        val active = mutableStateOf(1)
        rule.setContent { PocketTheme {
            Box(Modifier.height(240.dp)) { TranscriptTurnNavigator(many, active.value, null, {}) }
        } }
        rule.onNodeWithTag("transcript-turn-1").performTouchInput { down(center) }
        rule.runOnIdle { active.value = 80 }
        rule.onNodeWithTag("transcript-turn-1").assertIsDisplayed()
        rule.onNodeWithTag("transcript-turn-80").assertDoesNotExist()
        rule.onNodeWithTag("transcriptTurnNavigator").performTouchInput { up() }
        rule.onNodeWithTag("transcript-turn-80").assertIsDisplayed().assertIsSelected()
    }
}
