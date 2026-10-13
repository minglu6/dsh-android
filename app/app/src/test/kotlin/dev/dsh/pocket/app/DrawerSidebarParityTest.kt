package dev.dsh.pocket.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drawer mirrors desktop ui-sidebar panellist + ui-workspace section header
 * (plugins / schedules under 新会话; search / sliders / folder+ on 会话 header).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class DrawerSidebarParityTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        Pocket.init(RuntimeEnvironment.getApplication())
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.sessionGroupBy = SessionGroupBy.Workspace
        Pocket.prefs.sessionOrderBy = SessionOrderBy.Updated
        Pocket.prefs.archivedFilter = ArchivedFilter.Default
        Pocket.ops.value = setOf("plugins.list", "schedule.catalog", "sessions.search", "workspace.state")
        Pocket.profiles.value = listOf(Profile("p1", "Mac mini", host = "100.106.128.87", port = 7690))
        Pocket.active.value = "p1"
        Pocket.status.value = mapOf("p1" to Dot.Online)
        Pocket.sessions.value = emptyList()
        Pocket.chat.value = null
    }

    @Test
    fun panelRowsAndSectionHeaderMatchDesktopChrome() {
        rule.setContent {
            PocketTheme {
                SessionDrawer({}, {}, {}, {})
            }
        }
        rule.onNodeWithText("新会话").assertIsDisplayed()
        rule.onNodeWithTag("drawerPanel:插件").assertIsDisplayed()
        rule.onNodeWithTag("drawerPanel:自动化任务").assertIsDisplayed()
        rule.onNodeWithText("工作区").assertIsDisplayed()
        rule.onNodeWithText("会话").assertDoesNotExist()
        rule.onNodeWithTag("drawerHeader:搜索").assertIsDisplayed()
        rule.onNodeWithTag("drawerHeader:视图选项").assertIsDisplayed()
        rule.onNodeWithTag("drawerHeader:添加工作区").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h792dp-xxhdpi")
    fun connectionIdentityFitsToTheRightOfSettings() {
        Pocket.profiles.value = listOf(Profile("p1", "Mac mini with a long remote name", host = "100.106.128.87", port = 7690))
        rule.setContent { PocketTheme { SessionDrawer({}, {}, {}, {}) } }
        rule.waitForIdle()
        val footer = rule.onNodeWithTag("drawerFooter").fetchSemanticsNode().boundsInRoot
        val settings = rule.onNodeWithTag("drawerSettings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val connection = rule.onNodeWithTag("drawerConnection").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val address = rule.onNodeWithText("100.106.128.87:7690", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(connection.left >= settings.right)
        assertTrue(connection.right <= footer.right)
        assertTrue(address.left >= connection.left && address.right <= connection.right)
        assertTrue(kotlin.math.abs(settings.center.y - connection.center.y) < 1f)
    }

    @Test
    fun viewOptionsSheetListsDesktopChoices() {
        rule.setContent {
            PocketTheme {
                ViewOptionsSheet(
                    groupBy = SessionGroupBy.Workspace,
                    orderBy = SessionOrderBy.Updated,
                    archivedFilter = ArchivedFilter.Default,
                    onDismiss = {},
                    onGroup = {},
                    onOrder = {},
                    onArchived = {},
                )
            }
        }
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        rule.onNodeWithText("视图选项").assertExists()
        rule.onNodeWithText("分组方式").assertExists()
        rule.onNodeWithText("按工作区").assertExists()
        rule.onNodeWithText("按工作区树").assertExists()
        rule.onNodeWithText("单列表").assertExists()
        rule.onNodeWithText("排序方式").assertExists()
        rule.onNodeWithText("手动排序").assertExists()
        rule.onNodeWithText("最近更新").assertExists()
        rule.onNodeWithText("筛选会话").assertExists()
        rule.onNodeWithText("隐藏已归档").assertExists()
        rule.onNodeWithText("全部对话（显示已归档）").assertExists()
        rule.onNodeWithText("仅显示已归档").assertExists()
    }

    @Test
    fun flatGroupByShowsSessionsSectionTitle() {
        Pocket.prefs.sessionGroupBy = SessionGroupBy.Flat
        rule.setContent {
            PocketTheme {
                SessionDrawer({}, {}, {}, {})
            }
        }
        rule.onNodeWithText("会话").assertIsDisplayed()
        rule.onNodeWithText("工作区").assertDoesNotExist()
    }

    @Test
    fun prefsRoundTripViewOptions() {
        Pocket.prefs.sessionGroupBy = SessionGroupBy.Flat
        Pocket.prefs.sessionOrderBy = SessionOrderBy.Manual
        Pocket.prefs.archivedFilter = ArchivedFilter.Only
        assertEquals(SessionGroupBy.Flat, Pocket.prefs.sessionGroupBy)
        assertEquals(SessionOrderBy.Manual, Pocket.prefs.sessionOrderBy)
        assertEquals(ArchivedFilter.Only, Pocket.prefs.archivedFilter)
    }
}
