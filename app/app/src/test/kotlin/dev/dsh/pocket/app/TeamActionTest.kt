package dev.dsh.pocket.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.protocol.SessionAddress
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class TeamActionTest {
    @get:Rule val rule = createComposeRule()
    @Before fun setup() {
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
    }
    private fun member(id: String, current: Boolean = false, phase: String = "active") =
        AgentTeamMember(id, id, if (current) "lead" else "teammate", phase,
            if (phase == "active") "inactive" else phase, current,
            if (current) SessionAddress(id) else SessionAddress(id, "lead", "continuable"))

    @Test fun onlyAvailableNonCurrentMembersNavigate() {
        val selected = mutableListOf<String>()
        val members = listOf(member("lead", current = true), member("ready"), member("failed", phase = "failed"), member("pending", phase = "provisioning"))
        rule.setContent { PocketTheme { TeamPanel(AgentTeam("lead", members, emptyList()), false, true) { selected += it.id } } }
        rule.onNodeWithTag("teamMember:lead").assertIsNotEnabled()
        rule.onNodeWithTag("teamMember:failed").assertIsNotEnabled()
        rule.onNodeWithTag("teamMember:pending").assertIsNotEnabled()
        rule.onNodeWithTag("teamMember:ready").performClick()
        assertEquals(listOf("ready"), selected)
    }

    @Test fun subagentSelectionPreservesAddressAndOfflineOrExternalCannotOpen() {
        val online = mutableStateOf(false)
        val address = SessionAddress("child", "parent", "continuable")
        val selected = mutableListOf<SessionAddress>()
        val entries = listOf(SubagentEntry("child", "Investigate", "continuable", address, "inactive", false),
            SubagentEntry("remote", "External", "external", null, "inactive", false))
        rule.setContent { PocketTheme { SubagentPanel(entries, online.value) { selected += it } } }
        rule.onNodeWithTag("subagent:child").assertIsNotEnabled()
        online.value = true
        rule.onNodeWithTag("subagent:remote").assertIsNotEnabled()
        rule.onNodeWithTag("subagent:child").performClick()
        assertEquals(listOf(address), selected)
    }

    @Test fun anchoredPopupBackDismissesWithoutSelectingMember() {
        var open by mutableStateOf(true)
        val selected = mutableListOf<String>()
        rule.setContent { PocketTheme {
            Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
                Box(Modifier.size(48.dp)) {
                    Text("Anchor")
                    if (open) HeaderPopup(320, 680, { open = false }) {
                        TeamPanel(AgentTeam("lead", listOf(member("lead", current = true), member("ready")), emptyList()), false, true) { selected += it.id }
                    }
                }
            }
        } }
        rule.onNodeWithTag("teamPanel").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithTag("teamPanel").assertDoesNotExist()
        assertEquals(emptyList(), selected)
    }
}
