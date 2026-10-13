package dev.dsh.pocket.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
class ProviderCreationTest {
    @get:Rule val rule = createComposeRule()
    private fun json(value: String) = Json.parseToJsonElement(value)
    private val namespace = json("""{"ns":"llm-pi-ai","revision":7,"user":{},"value":{}}""")
    private val protocols = listOf("openai-completions", "openai-responses", "anthropic-messages")

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
        Pocket.ops.value = setOf("models.settingsMutate", "credentials.set")
    }

    @Test fun catalogAdoptionDoesNotRequireCustomApiNamespace() {
        val rows = listOf(json("""{"provider":"vendor","settingsNs":"llm-deepseek","configured":false}"""))
        val options = providerAddOptions(rows, listOf(json("""{"ns":"llm-deepseek"}""")))
        assertTrue(options.offered)
        assertTrue(options.enabled(emptyList()))
        assertNull(options.customNamespace)
        assertEquals(rows, options.catalog)
        assertFalse(providerAddOptions(rows, emptyList()).offered)
    }

    @Test fun customCreationNeedsHostProtocolsButNotDormantCatalogEntries() {
        val options = providerAddOptions(emptyList(), listOf(namespace))
        assertTrue(options.offered)
        assertFalse(options.enabled(emptyList()))
        assertTrue(options.enabled(protocols))
        assertEquals(namespace, options.customNamespace)
    }

    @Test fun reservedRoutesIncludeUnlistedUserAndDeploymentProfiles() {
        val view = json("""{"user":{"providers":{"hidden-user":{},"removed":null}},"value":{"providers":{"deployment":{}}}}""")
        val taken = providerTakenRoutes(listOf(json("""{"provider":"catalog"}""")), view)
        assertEquals(setOf("hidden-user", "deployment", "catalog"), taken)
        assertNotNull(providerRouteFailure("deployment", taken))
        for (route in listOf("", "1vendor", "UPPER", "with space", "trailing-")) assertNotNull(providerRouteFailure(route, taken))
        assertNull(providerRouteFailure("new-vendor", taken))
        assertTrue(providerEndpointValid(" https://api.example/v1 "))
        assertFalse(providerEndpointValid("file:///tmp/api"))
        assertFalse(providerEndpointValid("https:///missing-host"))
        assertEquals("Vendor (vendor)", providerOptionLabel(json("""{"displayName":"Vendor"}"""), "vendor"))
    }

    @Test fun incompleteCustomProfileCannotApply() {
        rule.setContent {
            PocketTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ProviderSettingsEditor(null, namespace, protocols, listOf("occupied"), true, onBusy = {}, onClose = {})
                }
            }
        }
        rule.onNodeWithText("创建提供商").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Provider ID").performScrollTo().performTextInput("occupied")
        rule.onNodeWithText("该 Provider ID 已被占用").assertExists()
        rule.onNodeWithText("创建提供商").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Provider ID").performScrollTo().performTextReplacement("valid-vendor")
        rule.onNodeWithContentDescription("API 地址").performScrollTo().performTextInput("file:///tmp/api")
        rule.onNodeWithText("请输入有效的 HTTP(S) 地址").assertExists()
        rule.onNodeWithText("创建提供商").performScrollTo().assertIsNotEnabled()
    }

    @Test fun creationConflictKeepsDraftRechecksIdAndRetriesCredentialWithoutSecondCreate() {
        val client = PocketClient()
        val onFrame = client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
        val revisions = mutableListOf<Long?>()
        val createdRoutes = mutableListOf<String?>()
        var mutations = 0
        var keyWrites = 0
        var closed = false
        val refreshed = json("""{"rows":[{"provider":"gateway"}],"namespaces":[{"ns":"llm-pi-ai","revision":8,"user":{"providers":{"gateway":{}}},"value":{"providers":{"gateway":{}}}}]}""")
        val written = json("""{"ns":"llm-pi-ai","revision":9,"user":{"providers":{"renamed":{"api":"openai-completions","baseURL":"https://example.test/v1","models":[{"id":"model-a"}],"apiKeyEnv":"RENAMED_API_KEY"}}},"value":{"providers":{"renamed":{"apiKeyEnv":"RENAMED_API_KEY"}}}}""")
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = kotlinx.coroutines.flow.emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) {
                val request = json(text)
                var failure: String? = null
                val result = when (request["op"].str()) {
                    "models.settingsMutate" -> {
                        mutations++
                        revisions += (request["args"]["expectedRevision"] as? JsonPrimitive)?.longOrNull
                        createdRoutes += request["args"]["ops"].arr()?.first()?.get("path").arr()?.last()?.str()
                        if (mutations == 1) failure = "settings/conflict"
                        written
                    }
                    "models.providers" -> refreshed
                    "credentials.set" -> {
                        keyWrites++
                        assertEquals("RENAMED_API_KEY", request["args"]["ref"].str())
                        if (keyWrites == 1) failure = "credential write failed"
                        json("""{"ok":true}""")
                    }
                    "models.providerGet" -> json("""{"provider":{"provider":"renamed","configured":true,"credential":{"configured":true,"writable":true}}}""")
                    else -> error("Unexpected operation ${request["op"]}")
                }
                val reply = buildJsonObject {
                    put("id", checkNotNull(request["id"]))
                    put("ok", failure == null)
                    if (failure == null) put("result", result)
                    else put("error", buildJsonObject { put("code", "test"); put("message", failure) })
                }
                onFrame.invoke(client, reply.toString())
            }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        val field = Pocket.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val previous = field.get(Pocket)
        field.set(Pocket, client)
        try {
            rule.setContent {
                PocketTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        ProviderSettingsEditor(null, namespace, protocols, emptyList(), true, onBusy = {}, onClose = { closed = it })
                    }
                }
            }
            rule.onNodeWithContentDescription("Provider ID").performTextInput("gateway")
            rule.onNodeWithContentDescription("API 密钥").performScrollTo().performTextInput("test-key")
            rule.onNodeWithContentDescription("API 地址").performScrollTo().performTextInput("https://example.test/v1")
            rule.onNodeWithText("添加模型").performScrollTo().performClick()
            rule.onNodeWithContentDescription("模型 ID 1").performScrollTo().performTextInput("model-a")
            rule.onNodeWithText("创建提供商").performScrollTo().performClick()
            rule.waitForIdle()
            assertFalse(closed)
            assertEquals(0, keyWrites)
            rule.onNodeWithText("回读最新配置并保留草稿").performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithText("创建提供商").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithContentDescription("Provider ID").performScrollTo().performTextReplacement("renamed")
            rule.onNodeWithContentDescription("模型 ID 1").performScrollTo().assertTextContains("model-a")
            rule.onNodeWithText("创建提供商").performScrollTo().performClick()
            rule.waitForIdle()
            assertFalse(closed)
            rule.onNodeWithContentDescription("Provider ID").performScrollTo().assertIsNotEnabled()
            rule.onNodeWithText("创建提供商").performScrollTo().performClick()
            rule.waitForIdle()
            assertTrue(closed)
            assertEquals(2, mutations)
            assertEquals(2, keyWrites)
            assertEquals(listOf<Long?>(7L, 8L), revisions)
            assertEquals(listOf<String?>("gateway", "renamed"), createdRoutes)
        } finally {
            field.set(Pocket, previous)
            client.close()
        }
    }
}
