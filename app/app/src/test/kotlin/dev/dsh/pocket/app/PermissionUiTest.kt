package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertContentDescriptionEquals
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.performClick
import dev.dsh.pocket.protocol.PermissionCatalog
import dev.dsh.pocket.protocol.PermissionOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PermissionUiTest {
    @get:Rule val rule = createComposeRule()
    private val team = PermissionOption("team", "团队权限", "主机提供的限制说明")
    private val auto = PermissionOption("auto", "Auto review")
    private val directory = PermissionCatalog(listOf(team, auto), listOf(team), "team")

    private fun settle() {
        rule.mainClock.advanceTimeBy(1500)
        rule.waitForIdle()
    }

    @Test fun customHostPresentationAndLiveAutoAreSelectableOnce() {
        val picks = mutableListOf<String>()
        rule.setContent {
            PocketTheme {
                PermissionPickerSheet("team", directory, false, null, {}, {}, { picks.add(it) })
            }
        }
        settle()
        rule.onNodeWithText("团队权限").assertExists()
        rule.onNodeWithText("主机提供的限制说明").assertExists()
        rule.onNodeWithText("自动审查").performClick()
        rule.onNodeWithText("自动审查").assertIsNotEnabled()
        assertEquals(listOf("auto"), picks)
    }

    @Test fun autoIsAbsentWithoutLiveHostContribution() {
        rule.setContent {
            PocketTheme {
                PermissionPickerSheet("team", directory.copy(options = listOf(team)), false, null, {}, {}, {})
            }
        }
        settle()
        rule.onNodeWithText("自动审查").assertDoesNotExist()
        rule.onNodeWithText("团队权限").assertExists()
    }

    @Test fun defaultsUseOnlyEligibleHostOptions() {
        rule.setContent {
            PocketTheme {
                PermissionPickerSheet("team", directory, false, null, {}, {}, {}, forDefaults = true)
            }
        }
        settle()
        rule.onNodeWithText("自动审查").assertDoesNotExist()
        rule.onNodeWithText("团队权限").assertExists()
    }

    @Test fun failedRefreshWithdrawsOldOptionsInsteadOfAllowingStaleSelection() {
        val error = mutableStateOf<String?>(null)
        rule.setContent {
            PocketTheme {
                PermissionPickerSheet("team", directory, false, error.value, {}, {}, {})
            }
        }
        settle()
        rule.onNodeWithText("自动审查").assertExists()
        rule.runOnIdle { error.value = "主机已断开" }
        rule.onNodeWithText("自动审查").assertDoesNotExist()
        rule.onNodeWithText("团队权限").assertDoesNotExist()
        rule.onNodeWithText("主机已断开").assertExists()
        rule.onNodeWithText("重新加载").assertExists()
    }

    @Test fun autoRiskRequiresAcknowledgementAndSubmitsOnlyOnce() {
        var confirmed = 0
        rule.setContent {
            PocketTheme { PermissionRiskSheet("auto", {}, { confirmed++ }) }
        }
        settle()
        rule.onNodeWithText("启用自动审查").assertIsNotEnabled()
        rule.onNodeWithText("我已了解这些风险，并愿意继续").performClick()
        rule.onNodeWithText("启用自动审查").assertIsEnabled().performClick()
        rule.onNodeWithText("启用自动审查").assertIsNotEnabled()
        assertEquals(1, confirmed)
    }

    @Test fun cancelNeverConfirmsEvenAfterAcknowledgement() {
        var dismissed = 0
        var confirmed = 0
        rule.setContent {
            PocketTheme { PermissionRiskSheet("danger-full-access", { dismissed++ }, { confirmed++ }) }
        }
        settle()
        rule.onNodeWithText("我已了解风险，并愿意继续").performClick()
        rule.onNodeWithText("取消").performClick()
        assertEquals(1, dismissed)
        assertEquals(0, confirmed)
    }

    @Test fun losingEligibilityClearsAcknowledgement() {
        val enabled = mutableStateOf(true)
        rule.setContent {
            PocketTheme { PermissionRiskSheet("auto", {}, {}, enabled = enabled.value) }
        }
        settle()
        rule.onNodeWithText("我已了解这些风险，并愿意继续").performClick()
        rule.onNodeWithText("启用自动审查").assertIsEnabled()
        rule.runOnIdle { enabled.value = false }
        rule.waitForIdle()
        rule.runOnIdle { enabled.value = true }
        rule.onNodeWithText("启用自动审查").assertIsNotEnabled()
    }

    @Test fun newConversationKeepsDraftWhilePermissionDirectoryLoads() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.defaultPermission = "workspace-write"
        Pocket.active.value = "permission-test"
        Pocket.status.value = mapOf("permission-test" to Dot.Online)
        Pocket.chat.value = null
        Pocket.ops.value = setOf("permissions.catalog", "permissions.set")
        Pocket.permissionCatalog.value = null
        Pocket.permissionCatalogLoading.value = true
        Pocket.permissionCatalogError.value = null
        rule.setContent { PocketTheme { ChatRoot({}) } }
        rule.onNodeWithTag("composerInput").performTextInput("preserved draft")
        rule.onNodeWithTag("composerSend").assertIsNotEnabled()
        rule.runOnIdle {
            val options = listOf(PermissionOption("workspace-write", "workspace-write"))
            Pocket.permissionCatalog.value = PermissionCatalog(options, options, "workspace-write")
            Pocket.permissionCatalogLoading.value = false
        }
        rule.onNodeWithTag("composerSend").assertIsEnabled()
        rule.onNodeWithTag("composerInput").assertTextContains("preserved draft")
    }

    @Test fun disconnectDisablesComposerPermissionWithoutOpeningPicker() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.active.value = "permission-session-test"
        Pocket.status.value = mapOf("permission-session-test" to Dot.Online)
        Pocket.chat.value = ChatState("s1", loaded = true)
        Pocket.permission.value = mapOf("s1" to "workspace-write")
        Pocket.ops.value = setOf("permissions.catalog", "permissions.set")
        rule.setContent { PocketTheme { ChatRoot({}) } }
        rule.onNodeWithTag("composerPermission").assertIsEnabled()
        rule.runOnIdle { Pocket.status.value = mapOf("permission-session-test" to Dot.Offline) }
        rule.onNodeWithTag("composerPermission").assertIsNotEnabled().performClick()
        rule.onNodeWithText("访问模式").assertDoesNotExist()
    }

    @Test fun autoChosenForOneConversationDoesNotBecomeNextConversationDefault() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.defaultPermission = "workspace-write"
        Pocket.active.value = "permission-session-test"
        Pocket.status.value = mapOf("permission-session-test" to Dot.Online)
        Pocket.chat.value = null
        Pocket.ops.value = setOf("permissions.catalog", "permissions.set")
        val workspace = PermissionOption("workspace-write", "workspace-write")
        val live = PermissionCatalog(listOf(workspace, auto), listOf(workspace), "workspace-write")
        Pocket.permissionCatalog.value = live
        Pocket.permissionCatalogLoading.value = false
        Pocket.permissionCatalogError.value = null
        Pocket.permissionChanging.value = false
        rule.setContent { PocketTheme { ChatRoot({}) } }
        rule.onNodeWithTag("composerPermission").performClick()
        settle()
        runBlocking { Pocket.reloadPermissionCatalog().join() }
        rule.runOnIdle {
            Pocket.permissionCatalog.value = live
            Pocket.permissionCatalogError.value = null
        }
        rule.onNodeWithText("自动审查").performClick()
        settle()
        rule.onNodeWithText("我已了解这些风险，并愿意继续").performClick()
        rule.onNodeWithText("启用自动审查").performClick()
        settle()
        rule.onNodeWithTag("composerPermission").assertContentDescriptionEquals("自动审查")
        rule.runOnIdle {
            Pocket.permission.value = mapOf("created" to "auto")
            Pocket.chat.value = ChatState("created", loaded = true)
        }
        rule.waitForIdle()
        rule.runOnIdle { Pocket.chat.value = null }
        rule.onNodeWithTag("composerPermission").assertContentDescriptionEquals("工作区内修改")
        assertEquals("workspace-write", Pocket.prefs.defaultPermission)
    }

    @Test fun customizedBuiltInLabelIsNotOverwritten() {
        assertEquals("只读审计", permissionLabel("read-only", PermissionOption("read-only", "只读审计")))
        assertEquals("仅可查看", permissionLabel("read-only"))
        assertEquals("team-audit", permissionLabel("team-audit"))
    }
}
