package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.protocol.PocketError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native dialog interaction and failure-state regressions. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class FeedbackDialogTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
        Pocket.active.value = "feedback-test"
        Pocket.status.value = mapOf("feedback-test" to Dot.Online)
    }

    @Test fun emptyFormSubmitsOnceAndSuccessCloses() {
        val open = mutableStateOf(true)
        var calls = 0
        lateinit var complete: (Result<Unit>) -> Unit
        rule.setContent {
            PocketTheme {
                if (open.value) FeedbackDialog({ open.value = false }) { category, note, callback ->
                    calls++
                    assertNull(category)
                    assertEquals("", note)
                    complete = callback
                }
            }
        }
        rule.onNodeWithText(L.feedbackHint()).assertIsDisplayed()
        rule.onNodeWithText(L.feedbackCategory()).assertDoesNotExist()
        rule.onNodeWithText(L.feedbackDetail()).assertDoesNotExist()
        val submit = rule.onNodeWithTag("feedbackSubmit")
        submit.assertIsEnabled().performTouchInput { doubleClick() }
        rule.runOnIdle { assertEquals(1, calls) }
        submit.assertIsNotEnabled()
        rule.onNodeWithText("正在提交…").assertIsDisplayed()
        rule.runOnIdle { complete(Result.success(Unit)) }
        rule.onNodeWithTag("feedbackDialog").assertDoesNotExist()
    }

    @Test fun categoriesAreOptionalSingleSelectionAndRemainEditableOffline() {
        Pocket.status.value = mapOf("feedback-test" to Dot.Offline)
        rule.setContent { PocketTheme { FeedbackDialog({}) { _, _, _ -> error("Offline submission") } } }
        val task = rule.onNodeWithTag("feedbackCategory:task-result")
        val other = rule.onNodeWithTag("feedbackCategory:other")
        task.assertIsOff().performClick().assertIsOn()
        other.performClick().assertIsOn()
        task.assertIsOff()
        other.performClick().assertIsOff()
        rule.onNodeWithTag("feedbackDetail").performTextInput("本地草稿")
        rule.onNodeWithTag("feedbackDetail").assertTextContains("本地草稿")
        rule.onNodeWithTag("feedbackSubmit").assertIsNotEnabled()
        rule.runOnIdle { Pocket.status.value = mapOf("feedback-test" to Dot.Online) }
        rule.onNodeWithTag("feedbackSubmit").assertIsEnabled()
    }

    @Test fun failurePreservesDraftAndShowsWarningForSixSeconds() {
        lateinit var complete: (Result<Unit>) -> Unit
        rule.setContent { PocketTheme { FeedbackDialog({}) { _, _, callback -> complete = callback } } }
        rule.onNodeWithTag("feedbackCategory:task-result").performClick()
        rule.onNodeWithTag("feedbackDetail").performTextInput("保留类别与原始描述")
        rule.onNodeWithTag("feedbackSubmit").performClick()
        rule.onNodeWithTag("feedbackCategory:task-result").assertIsNotEnabled()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { complete(Result.failure(PocketError("note-too-large", "private upstream error"))) }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("feedbackFailure").assertIsDisplayed()
        val warning = rule.onNodeWithTag("feedbackFailure").getUnclippedBoundsInRoot()
        val submit = rule.onNodeWithTag("feedbackSubmit").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue("Failure must not obscure retry", warning.top >= submit.bottom)
        rule.onNodeWithText("描述太长，请缩短后再提交").assertIsDisplayed()
        rule.onNodeWithText("private upstream error", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("feedbackCategory:task-result").assertIsOn().assertIsEnabled()
        rule.onNodeWithTag("feedbackDetail").assertTextContains("保留类别与原始描述")
        rule.mainClock.advanceTimeBy(5900)
        rule.onNodeWithTag("feedbackFailure").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(200)
        rule.onNodeWithTag("feedbackFailure").assertDoesNotExist()
        rule.onNodeWithTag("feedbackSubmit").assertIsEnabled()
    }

    @Test fun closingDuringSubmissionIgnoresLateReceiptAfterReopening() {
        val open = mutableStateOf(true)
        val callbacks = mutableListOf<(Result<Unit>) -> Unit>()
        var closes = 0
        rule.setContent {
            PocketTheme {
                if (open.value) FeedbackDialog({ closes++; open.value = false }) { _, _, complete -> callbacks += complete }
            }
        }
        rule.onNodeWithTag("feedbackSubmit").performClick()
        rule.onNodeWithTag("feedbackClose").assertIsEnabled().performClick()
        rule.onNodeWithTag("feedbackDialog").assertDoesNotExist()
        rule.runOnIdle { open.value = true }
        rule.onNodeWithTag("feedbackDetail").performTextInput("新的表单")
        rule.onNodeWithTag("feedbackSubmit").performClick()
        rule.runOnIdle { callbacks[0](Result.success(Unit)) }
        rule.onNodeWithTag("feedbackDialog").assertIsDisplayed()
        rule.onNodeWithTag("feedbackDetail").assertTextContains("新的表单")
        rule.onNodeWithTag("feedbackSubmit").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(1, closes)
            callbacks[1](Result.success(Unit))
        }
        rule.onNodeWithTag("feedbackDialog").assertDoesNotExist()
        rule.runOnIdle { assertEquals(2, closes) }
    }

    @Test fun conflictAndUnknownFailuresUseLocalizedCopyWithoutLeakingDetails() {
        Pocket.prefs.language = AppLanguage.En
        lateinit var complete: (Result<Unit>) -> Unit
        rule.setContent { PocketTheme { FeedbackDialog({}) { _, _, callback -> complete = callback } } }
        rule.onNodeWithText("Submit feedback").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        rule.onNodeWithText("Add details to help us improve. Your submission will include the current conversation log.").assertIsDisplayed()
        rule.onNodeWithTag("feedbackSubmit").performClick()
        rule.onNodeWithText("Submitting…").assertIsDisplayed()
        rule.runOnIdle { complete(Result.failure(PocketError("version-conflict", "private conflict"))) }
        rule.onNodeWithText("This feedback changed elsewhere; the latest state is shown").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(6100)
        rule.onNodeWithTag("feedbackSubmit").performClick()
        rule.runOnIdle { complete(Result.failure(IllegalStateException("private network details"))) }
        rule.onNodeWithText("Could not save feedback").assertIsDisplayed()
        rule.onNodeWithText("private", substring = true).assertDoesNotExist()
    }


    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h720dp-xhdpi")
    fun narrowEnglishDialogKeepsLongLabelsAndSubmitReachable() {
        Pocket.prefs.language = AppLanguage.En
        rule.setContent { PocketTheme { FeedbackDialog({}) { _, _, _ -> } } }
        rule.onNodeWithTag("feedbackCategory:security-privacy-permission").performScrollTo().performClick().assertIsOn()
        rule.onNodeWithTag("feedbackDetail").performScrollTo().performTextInput((1..30).joinToString("\n") { "Line $it" })
        val detailBounds = rule.onNodeWithTag("feedbackDetail").getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(detailBounds.bottom - detailBounds.top <= 280.dp)
        rule.onNodeWithTag("feedbackSubmit").performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("feedbackClose").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }
}
