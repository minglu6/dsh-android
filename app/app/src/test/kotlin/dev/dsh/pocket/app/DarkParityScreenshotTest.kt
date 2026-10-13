package dev.dsh.pocket.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.dsh.pocket.protocol.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class DarkParityScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private fun out(name: String) = "build/screenshots/$name.png"

    private fun show(name: String, content: @Composable () -> Unit) {
        rule.setContent { PocketTheme { Box(Modifier.fillMaxSize()) { content() } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(out(name))
    }

    @Before fun state() {
        runCatching { Pocket.prefs.nearbyWifiDeclined = true }
        runCatching { Pocket.prefs.appearance = Appearance.Dark }
        val p = Profile("p1", "Mac mini", host = "100.106.128.87", port = 7690)
        Pocket.profiles.value = listOf(p, Profile("p2", "书房 NAS", host = "192.168.1.20", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online, "p2" to Dot.Offline)
        Pocket.ops.value = setOf("models.providers", "plugins.list", "schedule.catalog")
    }

    @Test fun dark_connections() = show("05-connections-dark") {
        ConnectionsScreen(
            null, {}, true, {}, {},
            nearbySeed = listOf(DiscoveredHost("客厅 Mac", "192.168.1.42", 7690, "f".repeat(64))),
        )
    }

    @Test fun dark_pair() {
        Pocket.profiles.value = emptyList()
        rule.setContent {
            PocketTheme {
                Box(Modifier.fillMaxSize()) {
                    PairSheet(PairingLink("100.106.128.87", 7690, "752314"), onDismiss = {}, onPaired = {})
                }
            }
        }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        captureScreenRoboImage(out("07-pair-sheet-dark"))
    }

    @Test fun dark_settings() = show("08-settings-dark") {
        SettingsScreen({}, {}, {}, {}, {}, {})
    }
}
