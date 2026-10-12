package dev.dsh.pocket.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression: Panel row taps must navigate without clearing the sheet via onDismiss. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PanelSheetTest {
    @get:Rule val rule = createComposeRule()

    @Before fun ops() {
        Pocket.ops.value = setOf("files.list", "terminal.create", "jobs.list")
    }

    @Test fun panelRowsNavigateWithoutDismiss() {
        var files = false
        var terminal = false
        var jobs = false
        var dismissed = false
        rule.setContent {
            PocketTheme {
                PanelSheet(
                    cwd = "/tmp/ws",
                    onDismiss = { dismissed = true },
                    onFiles = { files = true },
                    onTerminal = { terminal = true },
                    onJobs = { jobs = true },
                )
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()

        rule.onNodeWithText("工作区文件").performClick()
        rule.waitForIdle()
        assertTrue(files)
        assertFalse(dismissed)

        dismissed = false
        rule.onNodeWithText("终端").performClick()
        rule.waitForIdle()
        assertTrue(terminal)
        assertFalse(dismissed)

        dismissed = false
        rule.onNodeWithText("后台任务").performClick()
        rule.waitForIdle()
        assertTrue(jobs)
        assertFalse(dismissed)
    }

    @Test fun panelEmptyStateWhenOpsMissing() {
        Pocket.ops.value = emptySet()
        rule.setContent {
            PocketTheme {
                PanelSheet("/tmp/ws", {}, {}, {}, {})
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
        rule.onNodeWithText("当前连接未提供面板").assertExists()
    }
}
