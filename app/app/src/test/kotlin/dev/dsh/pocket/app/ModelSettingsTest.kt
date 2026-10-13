package dev.dsh.pocket.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
class ModelSettingsTest {
    @get:Rule val rule = createComposeRule()
    private fun json(text: String) = Json.parseToJsonElement(text)
    private val namespace = json("""{"ns":"llm-pi-ai","revision":7,"user":{},"value":{}}""")
    private val protocols = listOf("openai-completions", "openai-responses", "anthropic-messages")

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
        Pocket.ops.value = setOf("models.settingsMutate", "credentials.set")
    }

    private fun mount(content: @Composable () -> Unit) {
        rule.setContent { PocketTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() } } }
    }

    @Test fun apiKeysMatchDesktopPasteRulesWithoutRejectingBase64Padding() {
        assertNull(providerKeyFailure(""))
        assertNull(providerKeyFailure("  sk-valid  "))
        assertNull(providerKeyFailure("ABCD=="))
        listOf("  ", "密钥", "key with space", "API_KEY=secret", "'secret'", "\"secret\"", "`secret`").forEach {
            assertNotNull(providerKeyFailure(it))
        }
    }

    @Test fun nativeInputPatchPreservesUnknownFieldsAndDropsDeepSeekImageLimits() {
        val model = json("""{"id":"vision","inputModalities":["text","image"],"imagePixelBudget":100,"imageMaxBytes":200,"reasoning":{"efforts":["high"]},"future":{"a":1}}""")
        val textOnly = changeModelInput(model, true, listOf("text"))
        assertNull(textOnly["imagePixelBudget"])
        assertNull(textOnly["imageMaxBytes"])
        assertEquals(model["future"], textOnly["future"])
        assertEquals(model["reasoning"], textOnly["reasoning"])
        assertEquals(listOf("text"), modelInputSelection(textOnly, true, listOf("text", "image")))
        assertEquals(listOf("image"), modelInputSelection(json("""{"id":"x"}"""), false, listOf("image")))
    }

    @Test fun capacitiesUseDecimalSuffixesAndRejectInvalidValuesAndTrimmedDuplicates() {
        assertEquals(JsonPrimitive(2300000), modelCapacity("2.3M"))
        assertEquals(JsonPrimitive(256000), modelCapacity("256K"))
        assertNull(modelCapacity(" "))
        for (value in listOf("1.2", "-1", "nope", "0")) {
            assertNotNull(validateProviderModels(JsonArray(listOf(patchModel(json("""{"id":"x"}"""), "maxTokens", modelCapacity(value))))))
        }
        assertNotNull(validateProviderModels(json("""[{"id":"x"},{"id":" x "}]""") as JsonArray))
        assertNotNull(validateProviderModels(json("""[{"id":"x","name":""}]""") as JsonArray))
    }

    @Test fun modelFormEditsNativeFieldsWithoutLosingUnknownMetadataAndResets() {
        val initial = json("""{"id":"vision","name":"Vision","inputModalities":["text","image"],"imagePixelBudget":100,"future":true}""")
        var models by mutableStateOf(JsonArray(listOf(initial)))
        var reset = false
        mount { NativeModelList(models, true, true, true, emptyList(), null, { models = it }, { reset = true }) }
        rule.onNodeWithText("显示名称（可选）").performTextReplacement("Updated")
        rule.onNodeWithText("容量与输入类型").performScrollTo().performClick()
        rule.onNodeWithText("上下文窗口").performScrollTo().performTextInput("1M")
        rule.onNodeWithText("图片").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals("Updated", models[0]["name"].str())
            assertEquals(JsonPrimitive(1000000), models[0]["contextWindow"])
            assertEquals(JsonPrimitive(true), models[0]["future"])
            assertNull(models[0]["imagePixelBudget"])
        }
        rule.onNodeWithText("恢复默认模型").performScrollTo().performClick()
        assertTrue(reset)
    }

    @Test fun existingProviderStartsCollapsedAndAccountDoesNotOfferKeyOrEndpoint() {
        Pocket.ops.value += "credentials.unset"
        val snapshot = json("""{"provider":{"provider":"gateway","settingsNs":"llm-pi-ai","settingsPath":["providers","gateway"],"declared":true,"credential":{"configured":true,"writable":true}},"namespace":{"ns":"llm-pi-ai","revision":7,"user":{"providers":{"gateway":{"api":"openai-responses"}}},"value":{"providers":{"gateway":{}}}},"inheritedModels":[]}""")
        mount { ProviderSettingsEditor(snapshot, null, protocols, emptyList(), true, onBusy = {}, onClose = {}) }
        rule.onNodeWithText("API 密钥").assertExists()
        rule.onNodeWithText("API 地址").assertDoesNotExist()
        rule.onNodeWithText("清除已保存的密钥").assertDoesNotExist()
        rule.onNodeWithText("自定义设置").performClick()
        rule.onNodeWithText("API 地址").assertExists()
        rule.onNodeWithText("OpenAI Responses").assertExists()
        rule.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/22-models-edit.png")
        rule.onNodeWithText("自定义设置").performClick()
        rule.onNodeWithText("API 地址").assertDoesNotExist()
    }

    @Test fun accountOnlyEditsModelsAndReadOnlyDisablesMutation() {
        val snapshot = json("""{"provider":{"provider":"deepseek-account","settingsPath":[]},"namespace":{"ns":"account-settings","revision":1,"user":{},"value":{}},"inheritedModels":[{"id":"deepseek-v4"}]}""")
        mount { ProviderSettingsEditor(snapshot, null, emptyList(), emptyList(), false, onBusy = {}, onClose = {}) }
        rule.onNodeWithText("API 密钥").assertDoesNotExist()
        rule.onNodeWithText("API 地址").assertDoesNotExist()
        rule.onNodeWithText("模型 ID").assertIsNotEnabled()
        rule.onNodeWithText("应用").performScrollTo().assertIsNotEnabled()
    }

    @Test fun addModesPreserveDraftAndProtocolUsesProductNames() {
        mount { AddModelProvider(listOf(json("""{"provider":"openai"}""")), namespace, protocols, emptyList(), true, {}, {}) }
        rule.onNodeWithText("自定义模型 API").performClick()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("my-gateway")
        rule.onNodeWithText("OpenAI Chat Completions").assertExists()
        rule.onNodeWithText("第三方模型提供商").performScrollTo().performClick()
        rule.onNodeWithText("Provider ID").assertDoesNotExist()
        rule.onNodeWithText("自定义模型 API").performClick()
        rule.onAllNodesWithText("my-gateway", substring = false).assertCountEquals(2)
        rule.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/22-models-add.png")
    }

    @Test fun discoveredCandidatesAreExplicitlySelectedAndDoNotOverwriteExistingModels() {
        val existing = listOf(json("""{"id":"a","contextWindow":777}"""))
        val candidates = listOf(json("""{"id":"a","contextWindow":100}"""), json("""{"id":"b","inputModalities":["image"]}"""), json("""{"id":"c"}"""))
        var adopted = emptyList<JsonElement>()
        // A modal owns its window and viewport; do not mount it in the form's
        // vertically unbounded scroll fixture.
        rule.setContent {
            PocketTheme {
                ModelCandidatePicker(candidates, existing, {}, { adopted = it })
            }
        }
        rule.onNodeWithText("c").performClick()
        rule.onNodeWithText("添加所选模型").performClick()
        assertEquals(listOf("b"), adopted.map { it["id"].str() })
        assertEquals(JsonArray(listOf(JsonPrimitive("image"))), adoptedModel(adopted.single())["input"])
        assertEquals(JsonPrimitive(777), existing.single()["contextWindow"])
    }
    @Test fun conflictKeepsDraftAndCredentialFailureRetriesOnlyCredential() {
        val client = PocketClient()
        val onFrame = client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
        var mutations = 0
        var keyWrites = 0
        var closed = false
        val revisions = mutableListOf<Long?>()
        val snapshot = json("""{"provider":{"provider":"gateway","declared":true,"settingsPath":["providers","gateway"],"derivedCredential":{"configured":false,"writable":true}},"namespace":{"ns":"llm-pi-ai","revision":7,"user":{"providers":{"gateway":{"api":"openai-completions"}}},"value":{"providers":{"gateway":{"api":"openai-completions"}}}}}""")
        val written = json("""{"ns":"llm-pi-ai","revision":8,"user":{"providers":{"gateway":{"api":"openai-completions","apiKeyEnv":"GATEWAY_API_KEY"}}},"value":{"providers":{"gateway":{"api":"openai-completions","apiKeyEnv":"GATEWAY_API_KEY"}}}}""")
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
                        if (mutations == 1) failure = "settings/conflict"
                        written
                    }
                    "credentials.set" -> {
                        keyWrites++
                        if (keyWrites == 1) failure = "credential write failed"
                        json("""{"ok":true}""")
                    }
                    "models.providerGet" -> json("""{"provider":{"credential":{"configured":true,"writable":true}}}""")
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
            mount { ProviderSettingsEditor(snapshot, null, protocols, emptyList(), true, onBusy = {}, onClose = { closed = true }) }
            rule.onNode(hasSetTextAction()).performTextInput("test-key")
            rule.onNodeWithText("应用").performClick()
            rule.waitForIdle()
            assertFalse(closed)
            rule.onNodeWithText("应用").performClick()
            rule.waitForIdle()
            assertFalse(closed)
            rule.onNodeWithText("应用").performClick()
            rule.waitForIdle()
            assertTrue(closed)
            assertEquals(2, mutations)
            assertEquals(2, keyWrites)
            assertEquals(listOf<Long?>(7L, 7L), revisions)
        } finally {
            client.close()
            field.set(Pocket, previous)
        }
    }
}
