package dev.dsh.pocket.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.protocol.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawerWidthTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        installHostSettingsFixture()
        Pocket.profiles.value = listOf(Profile("p1", "Mac mini", host = "100.106.128.87", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.sessions.value = listOf(
            SessionSummary("s1", "很长很长的会话标题用来验证省略号", "/Users/felix/src/very-long-workspace-name", false, false, 1, null),
        )
        Pocket.chat.value = null
        Pocket.approvals.value = emptyList()
    }

    @Test fun drawerWidthForUsesEightyPercentUntilCap() {
        assertEquals(288.dp, drawerWidthFor(360.dp))
        assertEquals(320.dp, drawerWidthFor(412.dp))
        assertEquals(320.dp, drawerWidthFor(500.dp))
        assertEquals(DrawerMaxWidth, drawerWidthFor(800.dp))
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
    fun composeDrawerIsEightyPercentOnPhone() {
        rule.setContent { PocketTheme { Box(Modifier.fillMaxSize()) { SessionDrawer({}, {}, {}, {}) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("sessionDrawer").assertWidthIsEqualTo(288.dp)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w500dp-h900dp-xxhdpi")
    fun composeDrawerCapsAt320OnLargeScreen() {
        rule.setContent { PocketTheme { Box(Modifier.fillMaxSize()) { SessionDrawer({}, {}, {}, {}) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("sessionDrawer").assertWidthIsEqualTo(320.dp)
    }
}
