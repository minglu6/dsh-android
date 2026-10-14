package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class OfflineSettingsTest {
    @get:Rule val rule = createComposeRule()
    private var previousActive: String? = null
    private var previousStatus = emptyMap<String, Dot>()
    private var previousCollapse = CollapseTiming.Completion
    private var previousOps = emptySet<String>()

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        previousActive = Pocket.active.value
        previousStatus = Pocket.status.value
        previousCollapse = Pocket.prefs.collapseTiming
        previousOps = Pocket.ops.value
        Pocket.active.value = "offline-settings-test"
        Pocket.status.value = mapOf("offline-settings-test" to Dot.Online)
        Pocket.ops.value = setOf("plugins.inventory", "schedule.catalog")
        installHostSettingsFixture()
    }

    @After fun restore() {
        Pocket.prefs.collapseTiming = previousCollapse
        Pocket.prefs.hostSettings.clear()
        Pocket.active.value = previousActive
        Pocket.status.value = previousStatus
        Pocket.ops.value = previousOps
    }

    private fun disconnect() = rule.runOnIdle {
        Pocket.status.value = mapOf("offline-settings-test" to Dot.Offline)
        Pocket.ops.value = emptySet()
        Pocket.prefs.hostSettings.clear()
    }

    @Test fun disconnectDisablesHostSettingsButKeepsLocalControlsUsable() {
        rule.setContent { PocketTheme { GeneralSettingsScreen {} } }
        rule.onNodeWithText(L.language()).assertIsEnabled()
        disconnect()
        rule.onNodeWithText(L.language()).assertIsNotEnabled().performClick()
        rule.onNodeWithText(L.dark()).assertIsNotEnabled().performClick()
        rule.onNodeWithContentDescription("字号大小：增大字号").assertIsNotEnabled().performClick()
        rule.runOnIdle { assertNull(Pocket.prefs.hostSettings.error) }
        rule.onRoot().captureRoboImage("build/screenshots/settings-offline.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        rule.onNodeWithTag("fontSettingsToggle").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithTag("textFontFamily").assertIsNotEnabled()
        rule.onNodeWithText("工作步骤收起时机").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText(collapseText(CollapseTiming.NextInput)).performClick()
        rule.runOnIdle { assertEquals(CollapseTiming.NextInput, Pocket.prefs.collapseTiming) }
    }

    @Test fun openChoiceSheetDisablesOnDisconnectAndRecoversWithoutSubmitting() {
        rule.setContent { PocketTheme { GeneralSettingsScreen {} } }
        rule.onNodeWithText(L.language()).performClick()
        rule.onNodeWithText(AppLanguage.En.label).assertIsEnabled()
        disconnect()
        rule.onNodeWithText(AppLanguage.En.label).assertIsNotEnabled().performClick()
        rule.runOnIdle { assertNull(Pocket.prefs.hostSettings.error) }
        rule.runOnIdle {
            Pocket.status.value = mapOf("offline-settings-test" to Dot.Online)
            installHostSettingsFixture()
        }
        rule.onNodeWithText(AppLanguage.En.label).assertIsEnabled()
        rule.runOnIdle { assertEquals(AppLanguage.Zh, Pocket.prefs.language) }
        rule.onNodeWithText(AppLanguage.En.label).performClick()
        rule.runOnIdle { assertEquals(AppLanguage.En, Pocket.prefs.language) }
    }

    @Test fun fontDraftSurvivesDisconnectWithoutBlurSavingAndRequiresExplicitCommit() {
        rule.setContent { PocketTheme { GeneralSettingsScreen {} } }
        rule.onNodeWithTag("fontSettingsToggle").performScrollTo().performClick()
        rule.onNodeWithTag("textFontFamily").performScrollTo().performTextReplacement("serif")
        disconnect()
        rule.onNodeWithTag("textFontFamily").assertIsNotEnabled().assertTextEquals("serif")
        rule.runOnIdle { assertNull(Pocket.prefs.hostSettings.error) }
        rule.runOnIdle {
            Pocket.status.value = mapOf("offline-settings-test" to Dot.Online)
            installHostSettingsFixture()
        }
        rule.onNodeWithTag("textFontFamily").assertIsEnabled().assertTextEquals("serif")
        rule.runOnIdle { assertEquals("", Pocket.prefs.textFontFamily) }
        rule.onNodeWithTag("textFontFamily").performScrollTo().performClick().performImeAction()
        rule.runOnIdle { assertEquals("serif", Pocket.prefs.textFontFamily) }
    }

    @Test fun hostNavigationDisablesButConnectionManagementStaysAvailable() {
        var connectionsOpened = 0
        var modelsOpened = 0
        rule.setContent { PocketTheme {
            SettingsScreen(onBack = {}, onGeneral = {}, onConnections = { connectionsOpened++ },
                onModels = { modelsOpened++ })
        } }
        rule.onNodeWithText(L.modelsNav()).assertIsEnabled()
        disconnect()
        rule.onNodeWithText(L.modelsNav()).assertIsNotEnabled().performClick()
        rule.onNodeWithText("内置插件").assertIsNotEnabled()
        rule.onNodeWithText(L.scheduleNav()).assertIsNotEnabled()
        rule.onNodeWithText("连接").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, connectionsOpened); assertEquals(0, modelsOpened) }
        rule.onRoot().captureRoboImage("build/screenshots/settings-navigation-offline.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
        rule.runOnIdle {
            Pocket.status.value = mapOf("offline-settings-test" to Dot.Online)
            Pocket.ops.value = setOf("plugins.inventory", "schedule.catalog")
        }
        rule.onNodeWithText(L.modelsNav()).assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, modelsOpened) }
    }
}
