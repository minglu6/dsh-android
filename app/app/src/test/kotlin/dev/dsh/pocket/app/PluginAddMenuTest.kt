package dev.dsh.pocket.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import dev.dsh.pocket.protocol.FrameTransport
import dev.dsh.pocket.protocol.PocketClient
import dev.dsh.pocket.protocol.PocketJson
import dev.dsh.pocket.protocol.Route
import dev.dsh.pocket.protocol.get
import dev.dsh.pocket.protocol.str
import kotlinx.coroutines.flow.emptyFlow

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PluginAddMenuTest {
    @get:Rule val rule = createComposeRule()

    @Before fun state() {
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
    }

    @Test fun openingAddMenuReloadsHostRosterAndCreatorNeverInstallsOrSends() {
        val requests = mutableListOf<String>()
        val client = PocketClient()
        val onFrame = client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) {
                val request = PocketJson.parseToJsonElement(text)
                val op = request["op"].str().orEmpty()
                requests += op
                val result = when (op) {
                    "plugins.list" -> """{"managementAvailable":true,"bundles":[],"plugins":[]}"""
                    "presets.list" -> """{"presets":[{"id":"cordis"}]}"""
                    else -> error("Unexpected mutation: $op")
                }
                onFrame.invoke(client, """{"id":${request["id"]},"ok":true,"result":$result}""")
            }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        val clientField = Pocket.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val previousClient = clientField.get(Pocket)
        val previousActive = Pocket.active.value
        val previousStatus = Pocket.status.value
        val previousOps = Pocket.ops.value
        val previousRoster = Pocket.presetRoster.value
        clientField.set(Pocket, client)
        Pocket.active.value = "plugins-menu"
        Pocket.status.value = mapOf("plugins-menu" to Dot.Online)
        Pocket.ops.value = setOf("plugins.list", "plugins.install", "presets.list", "sessions.create")
        Pocket.presetRoster.value = emptyList()
        var creators = 0
        try {
            rule.setContent { PocketTheme { PluginsSettingsScreen({}, { creators++ }) } }
            rule.onNodeWithContentDescription("选择添加插件方式").performClick()
            rule.onNodeWithText("进入创造模式，制作属于你的 DSH 插件").assertIsDisplayed()
            assertEquals(1, requests.count { it == "presets.list" })
            assertEquals(0, creators)
            androidx.test.espresso.Espresso.pressBack()
            rule.onNodeWithContentDescription("选择添加插件方式").performClick()
            rule.onNodeWithText("让 Agent 创建插件").performClick()
            assertEquals(2, requests.count { it == "presets.list" })
            assertEquals(1, creators)
        } finally {
            client.close()
            clientField.set(Pocket, previousClient)
            Pocket.active.value = previousActive
            Pocket.status.value = previousStatus
            Pocket.ops.value = previousOps
            Pocket.presetRoster.value = previousRoster
        }
    }

    @Test fun pluginExplanationOnlyAppearsAfterInfoClickAndDismissesOnBack() {
        val description = "在这里配置官方插件，安装和管理其他插件。内置插件列表及运行状态可在「设置 → 内置插件」中查看"
        rule.setContent { PocketTheme { PluginPageIntro() } }
        rule.onNodeWithText("安装、启用和配置插件").assertIsDisplayed()
        rule.onNodeWithText(description).assertDoesNotExist()
        rule.onNodeWithContentDescription("插件说明").performClick()
        rule.onNodeWithText(description).assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithText(description).assertDoesNotExist()
        rule.onNodeWithText("安装、启用和配置插件").assertIsDisplayed()
    }

    @Test fun primaryAndMenuInstallShareActionWhileCreatorOnlyDispatchesAfterSelection() {
        var installs = 0
        var creators = 0
        var opens = 0
        rule.setContent { PocketTheme {
            PluginAddMenu(true, true, "进入创造模式，制作属于你的 DSH 插件", onOpen = { opens++ },
                onInstall = { installs++ }, onCreatePlugin = { creators++ })
        } }
        rule.onNodeWithText("添加插件").performClick()
        assertEquals(1, installs)
        rule.onNodeWithContentDescription("选择添加插件方式").performClick()
        rule.onNodeWithText("安装第三方插件").assertIsDisplayed()
        rule.onNodeWithText("让 Agent 创建插件").assertIsDisplayed()
        rule.onNodeWithText("支持 npm 包、Git 仓库和本地目录").assertIsDisplayed()
        rule.onNodeWithText("进入创造模式，制作属于你的 DSH 插件").assertIsDisplayed()
        assertEquals(1, opens)
        assertEquals(0, creators)
        androidx.test.espresso.Espresso.pressBack()
        assertEquals(1, installs)
        assertEquals(0, creators)
        rule.onNodeWithContentDescription("选择添加插件方式").performClick()
        rule.onNodeWithText("安装第三方插件").performClick()
        assertEquals(2, installs)
        rule.onNodeWithText("安装第三方插件").assertDoesNotExist()
        rule.onNodeWithContentDescription("选择添加插件方式").performClick()
        rule.onNodeWithText("让 Agent 创建插件").performClick()
        assertEquals(1, creators)
        assertEquals(2, installs)
        assertEquals(3, opens)
        rule.onNodeWithText("让 Agent 创建插件").assertDoesNotExist()
    }

    @Test fun unavailableCreatorAndDisconnectedHostCannotDispatch() {
        val online = mutableStateOf(true)
        rule.setContent { PocketTheme {
            PluginAddMenu(online.value, false, "当前配置未提供创造模式", onOpen = {},
                onInstall = { error("unexpected install") }, onCreatePlugin = { error("unexpected creator") })
        } }
        rule.onNodeWithContentDescription("选择添加插件方式").performClick()
        rule.onNodeWithText("让 Agent 创建插件").assertIsNotEnabled()
        rule.runOnIdle { online.value = false }
        rule.onNodeWithText("安装第三方插件").assertIsNotEnabled()
        rule.onNodeWithText("让 Agent 创建插件").assertIsNotEnabled()
        androidx.test.espresso.Espresso.pressBack()
        rule.onNodeWithText("添加插件").assertIsNotEnabled()
        rule.onNodeWithContentDescription("选择添加插件方式").assertIsNotEnabled()
    }
}
