package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class ConnectionsUiTest {
    @get:Rule val rule = createComposeRule()
    private val current = Profile("switch-current", "当前主机", host = "127.0.0.1", port = 7690)
    private val target = Profile("switch-target", "另一台主机", host = "127.0.0.1", port = 7691)

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.nearbyWifiDeclined = true
        Pocket.profiles.value = listOf(current, target)
        Pocket.active.value = current.id
        Pocket.status.value = mapOf(current.id to Dot.Online, target.id to Dot.Offline)
        Pocket.routes.value = emptyMap()
    }

    @Test fun switchButtonDirectlySelectsTargetWithoutOpeningManagement() {
        var navigations = 0
        rule.setContent { PocketTheme { ConnectionsScreen(null, {}, true, {}, { navigations++ }) } }
        rule.onNodeWithText("切换").performClick()
        rule.runOnIdle {
            assertEquals(target.id, Pocket.active.value)
            assertEquals(target.id, Pocket.prefs.activeProfile)
            assertEquals(1, navigations)
        }
        rule.onNodeWithText("重命名").assertDoesNotExist()
        rule.onNodeWithText("重新配对").assertDoesNotExist()
    }

    @Test fun managementArrowOpensActionsWithoutSwitchingDevice() {
        var navigations = 0
        rule.setContent { PocketTheme { ConnectionsScreen(null, {}, true, {}, { navigations++ }) } }
        rule.onNodeWithContentDescription("另一台主机，更多").performClick()
        rule.onNodeWithText("重命名").assertIsDisplayed()
        rule.onNodeWithText("重新配对").assertIsDisplayed()
        rule.onNodeWithText("删除").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(current.id, Pocket.active.value)
            assertEquals(0, navigations)
        }
        rule.onNodeWithText("重命名").performClick()
        rule.onNodeWithText("保存").assertIsDisplayed()
    }

    @Test fun currentDeviceHasNoSwitchEvenWhenOffline() {
        Pocket.profiles.value = listOf(current)
        Pocket.status.value = mapOf(current.id to Dot.Offline)
        rule.setContent { PocketTheme { ConnectionsScreen(null, {}, true, {}, {}) } }
        rule.onAllNodesWithText("切换").fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test fun incompleteNonCurrentDeviceShowsDisabledSwitch() {
        Pocket.profiles.value = listOf(current, Profile("broken", "待修复主机"))
        rule.setContent { PocketTheme { ConnectionsScreen(null, {}, true, {}, {}) } }
        rule.onNodeWithText("切换").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(current.id, Pocket.active.value) }
    }
}
