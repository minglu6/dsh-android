package dev.dsh.pocket.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rename + cancel on the connections manage sheet; persist local Profile.name. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ProfileRenameTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        val p = Profile("p1", "电脑", nodeId = "abcdefghijklmnopqrstuvwxyz01")
        Pocket.profiles.value = listOf(p)
        Pocket.prefs.profiles = listOf(p)
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.routes.value = emptyMap()
    }

    @Test fun clipProfileNameTrimsAndTruncatesTo64() {
        assertEquals("Mac", clipProfileName("  Mac  "))
        assertEquals("x".repeat(64), clipProfileName("x".repeat(80)))
        assertThrows(IllegalStateException::class.java) { clipProfileName("   ") }
    }

    @Test fun renameProfilePersistsLocalName() {
        Pocket.renameProfile("p1", "  书房电脑  ")
        assertEquals("书房电脑", Pocket.profiles.value.single().name)
        assertEquals("书房电脑", Pocket.prefs.profiles.single().name)

        Pocket.renameProfile("p1", "y".repeat(80))
        assertEquals(64, Pocket.profiles.value.single().name.length)
        assertEquals(Pocket.prefs.profiles.single().name, Pocket.profiles.value.single().name)
    }

    @Test fun manageSheetRenameSaveUpdatesTitle() {
        rule.setContent {
            PocketTheme {
                val profiles by Pocket.profiles.collectAsState()
                ProfileManageSheet(
                    profile = profiles.single(),
                    status = Dot.Online,
                    isActive = true,
                    onDismiss = {},
                )
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()

        rule.onNodeWithText("电脑").assertIsDisplayed()
        rule.onNodeWithText("重命名").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("保存").assertIsDisplayed()
        rule.onNodeWithText("取消").assertIsDisplayed()
        rule.onNodeWithTag("profileRenameInput").performTextReplacement("Mac mini")
        rule.onNodeWithText("保存").performClick()
        rule.waitForIdle()

        assertEquals("Mac mini", Pocket.profiles.value.single().name)
        assertEquals("Mac mini", Pocket.prefs.profiles.single().name)
        rule.onNodeWithText("Mac mini").assertIsDisplayed()
        rule.onNodeWithText("删除").assertIsDisplayed()
    }

    @Test fun manageSheetRenameCancelKeepsName() {
        rule.setContent {
            PocketTheme {
                val profiles by Pocket.profiles.collectAsState()
                ProfileManageSheet(
                    profile = profiles.single(),
                    status = Dot.Online,
                    isActive = true,
                    onDismiss = {},
                )
            }
        }
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()

        rule.onNodeWithText("重命名").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("profileRenameInput").performTextReplacement("临时名")
        rule.onNodeWithText("取消").performClick()
        rule.waitForIdle()

        assertEquals("电脑", Pocket.profiles.value.single().name)
        assertEquals("电脑", Pocket.prefs.profiles.single().name)
        rule.onNodeWithText("电脑").assertIsDisplayed()
        rule.onNodeWithText("删除").assertIsDisplayed()
        rule.onNodeWithText("重命名").assertIsDisplayed()
        rule.onNodeWithText("保存").assertDoesNotExist()
    }
}
