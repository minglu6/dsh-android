package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import dev.dsh.pocket.protocol.SessionAddress
import dev.dsh.pocket.protocol.jsonOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xxhdpi")
class HeaderLayoutTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var restore: () -> Unit
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(name: String) = Pocket.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(Pocket) as MutableStateFlow<T>

    @Before fun setup() {
        installHostSettingsFixture()
        val chat = Pocket.chat.value
        val active = Pocket.active.value
        val status = Pocket.status.value
        val ops = Pocket.ops.value
        val presets = Pocket.presetRoster.value
        val team = Pocket.team.value
        val subagents = Pocket.subagents.value
        val language = Pocket.prefs.language
        val views = Pocket.prefs.showCodeWorkView
        restore = {
            Pocket.chat.value = chat; Pocket.active.value = active; Pocket.status.value = status
            Pocket.ops.value = ops; Pocket.presetRoster.value = presets
            flow<AgentTeam?>("_team").value = team
            flow<List<SubagentEntry>?>("_subagents").value = subagents
            Pocket.prefs.language = language; Pocket.prefs.showCodeWorkView = views
        }
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.showCodeWorkView = true
        Pocket.active.value = "layout"
        Pocket.status.value = mapOf("layout" to Dot.Online)
        Pocket.ops.value = emptySet()
        Pocket.presetRoster.value = listOf(jsonOf("id" to "standard"))
        Pocket.chat.value = ChatState("root", title = "地面洞察报告生成逻辑梳理与 Agent Skill 准备", loaded = true, agentPreset = "standard")
        flow<AgentTeam?>("_team").value = AgentTeam("root", listOf(AgentTeamMember("root", "lead", "lead", "active", "inactive", true, SessionAddress("root"))), emptyList())
        flow<List<SubagentEntry>?>("_subagents").value = (1..6).map { SubagentEntry("child-$it", "Worker $it", "continuable", SessionAddress("child-$it", "root", "continuable"), "inactive", false) }
    }
    @After fun cleanup() = restore()

    @Test fun compactIconsAndCountShareTitleRowWithoutSqueezingNavigation() {
        rule.setContent { PocketTheme { ChatRoot({}) } }
        val team = rule.onNodeWithContentDescription("智能体团队").assertIsDisplayed()
        val preset = rule.onNodeWithContentDescription("标准模式").assertIsDisplayed().assertHasNoClickAction()
        val count = rule.onNodeWithContentDescription("6 个子智能体").assertIsDisplayed()
        val tab = rule.onNodeWithText("轨迹").assertIsDisplayed()
        val title = rule.onNodeWithText("地面洞察报告生成逻辑梳理与 Agent Skill 准备").assertIsDisplayed()
        rule.onNodeWithText("智能体团队").assertDoesNotExist()
        rule.onNodeWithText("标准模式").assertDoesNotExist()
        rule.onNodeWithText("子智能体 6").assertIsDisplayed()
        val teamBounds = team.getUnclippedBoundsInRoot()
        val presetBounds = preset.getUnclippedBoundsInRoot()
        val countBounds = count.getUnclippedBoundsInRoot()
        val tabBounds = tab.getUnclippedBoundsInRoot()
        val titleBounds = title.getUnclippedBoundsInRoot()
        assertTrue((titleBounds.right - titleBounds.left).value > 24f)
        assertTrue(teamBounds.left >= titleBounds.right)
        assertTrue(presetBounds.left >= teamBounds.right)
        assertTrue(countBounds.left >= presetBounds.right)
        assertTrue(presetBounds.right - presetBounds.left <= teamBounds.right - teamBounds.left)
        for (bounds in listOf(teamBounds, presetBounds, countBounds)) {
            assertTrue(bounds.bottom <= tabBounds.top)
            val centerY = (bounds.top + bounds.bottom) / 2
            assertTrue(centerY >= titleBounds.top && centerY <= titleBounds.bottom)
        }
        assertTrue(countBounds.right <= rule.onNodeWithContentDescription("更多").getUnclippedBoundsInRoot().left)
        team.performClick()
        rule.onNodeWithTag("teamPanel").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        count.performClick()
        rule.onNodeWithTag("subagentPanel").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        preset.performTouchInput { click() }
        rule.onNodeWithText("Agent 预设").assertDoesNotExist()
    }
}
