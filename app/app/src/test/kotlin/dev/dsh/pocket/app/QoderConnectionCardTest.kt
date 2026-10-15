package dev.dsh.pocket.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import dev.dsh.pocket.protocol.*
import kotlinx.serialization.json.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class QoderConnectionCardTest {
    @get:Rule val rule = createComposeRule()
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.ops.value = setOf("models.settingsMutate", "credentials.describe", "credentials.set")
    }

    @Test fun pushedConfigurationPreservesDraftAndCancelReopensLatestWithoutReadingToken() {
        val requests = mutableListOf<String>()
        withClient(requests) {
            var namespace by mutableStateOf(json("""{"ns":"llm-qoder","revision":1,"value":{"vpcInstance":"initial"}}"""))
            var online by mutableStateOf(true)
            rule.setContent { PocketTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    QoderConnectionCard(namespace, online, true, onBusy = {}, onChanged = {})
                }
            } }
            rule.onNodeWithText(QoderText.Edit.zh).performClick()
            rule.onNodeWithContentDescription(QoderText.Pat.zh).assertTextEquals("")
            rule.onNodeWithContentDescription(QoderText.Pat.zh).performTextInput("unsaved-token")
            rule.onNodeWithContentDescription(QoderText.Vpc.zh).performTextReplacement("draft-instance")
            rule.onNodeWithText(QoderText.Advanced.zh).performScrollTo().performClick()
            rule.onNodeWithContentDescription(QoderText.Gateway.zh).performTextInput("https://draft.test")
            rule.runOnIdle {
                namespace = json("""{"ns":"llm-qoder","revision":2,"value":{"vpcInstance":"latest","baseURL":"https://latest.test"}}""")
                Pocket.modelSettingsRevision.value++
            }
            rule.onNodeWithContentDescription(QoderText.Vpc.zh).assertTextEquals("draft-instance")
            rule.onNodeWithContentDescription(QoderText.Gateway.zh).assertTextEquals("https://draft.test")
            rule.runOnIdle { online = false }
            rule.onNodeWithContentDescription(QoderText.Pat.zh).assertIsNotEnabled()
            rule.onNodeWithText(QoderText.Save.zh).performScrollTo().assertIsNotEnabled()
            rule.onNodeWithText(QoderText.Cancel.zh).assertIsEnabled().performClick()
            rule.runOnIdle { online = true }
            rule.onNodeWithText(QoderText.Edit.zh).performScrollTo().performClick()
            rule.onNodeWithContentDescription(QoderText.Pat.zh).assertTextEquals("")
            rule.onNodeWithContentDescription(QoderText.Vpc.zh).assertTextEquals("latest")
            rule.onNodeWithContentDescription(QoderText.Gateway.zh).assertDoesNotExist()
            rule.onNodeWithText(QoderText.Advanced.zh).performScrollTo().performClick()
            rule.onNodeWithContentDescription(QoderText.Gateway.zh).assertTextEquals("https://latest.test")
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it == "credentials.describe" })
        }
    }

    @Test fun conflictDisablesSaveUntilCancelAndReopenAndDoesNotExposeTransportError() {
        val requests = mutableListOf<String>()
        withClient(requests) {
            val namespace = json("""{"ns":"llm-qoder","revision":1,"value":{}}""")
            rule.setContent { PocketTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    QoderConnectionCard(namespace, true, true, onBusy = {}, onChanged = {})
                }
            } }
            rule.onNodeWithText(QoderText.Edit.zh).performClick()
            rule.onNodeWithContentDescription(QoderText.Pat.zh).performTextInput("secret-fixture")
            rule.onNodeWithText(QoderText.Save.zh).performScrollTo().performClick()
            rule.onNodeWithText(QoderText.Save.zh).performScrollTo().assertIsNotEnabled()
            rule.onNodeWithText("unsafe-secret-fixture").assertDoesNotExist()
            assertEquals(1, requests.count { it == "models.settingsMutate" })
            assertFalse("credentials.set" in requests)
            rule.onNodeWithText(QoderText.Cancel.zh).performClick()
            rule.onNodeWithText(QoderText.Edit.zh).performScrollTo().performClick()
            rule.onNodeWithContentDescription(QoderText.Pat.zh).assertTextEquals("")
            rule.onNodeWithText(QoderText.Save.zh).performScrollTo().assertIsEnabled()
        }
    }

    private fun withClient(requests: MutableList<String>, block: () -> Unit) {
        val client = PocketClient()
        val onFrame = client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = kotlinx.coroutines.flow.emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) {
                val request = json(text)
                val op = checkNotNull(request["op"].str())
                requests += op
                check(op == "credentials.describe" || op == "models.settingsMutate")
                onFrame.invoke(client, buildJsonObject {
                    put("id", checkNotNull(request["id"]))
                    put("ok", op == "credentials.describe")
                    if (op == "credentials.describe") put("result", jsonOf("ref" to request["args"]["ref"].str(), "configured" to true, "writable" to true))
                    else put("error", jsonOf("code" to "settings/conflict", "message" to "unsafe-secret-fixture"))
                }.toString())
            }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        val field = Pocket.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val previous = field.get(Pocket)
        val previousRevision = Pocket.modelSettingsRevision.value
        field.set(Pocket, client)
        try { block() } finally {
            field.set(Pocket, previous)
            Pocket.modelSettingsRevision.value = previousRevision
            client.close()
        }
    }
}
