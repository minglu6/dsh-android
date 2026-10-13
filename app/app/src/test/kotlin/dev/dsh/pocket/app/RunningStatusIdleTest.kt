package dev.dsh.pocket.app

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
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
class RunningStatusIdleTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.nearbyWifiDeclined = true
        Pocket.profiles.value = emptyList()
        Pocket.active.value = null
    }

    @Test fun elapsedClockAndShimmerDoNotBlockWaitForIdle() {
        rule.setContent {
            PocketTheme {
                RunningStatus(System.currentTimeMillis() - 42_000, showDivider = false)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("runningStatus").assertIsDisplayed()
    }

    @Test fun indeterminateProgressDoesNotBlockWaitForIdle() {
        rule.setContent { PocketTheme { CircularProgressIndicator() } }
        rule.waitForIdle()
    }

    @Test fun connectionsPollingDoesNotBlockWaitForIdle() {
        rule.setContent { PocketTheme { ConnectionsScreen(null, {}, false, {}, {}) } }
        rule.waitForIdle()
        rule.onNodeWithTag("connectionsScreen").assertIsDisplayed()
    }
}
