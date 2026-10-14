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

    @Test fun modelsScreenLoadsAndEditsNativelyWithoutHostUiCapability() {
        val client = PocketClient()
        val onFrame = client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
        val requests = mutableListOf<String>()
        var name = "Gateway"
        val provider = json("""{"provider":"gateway","configured":true,"active":true,"declared":true,"settingsNs":"llm-pi-ai","settingsPath":["providers","gateway"],"credential":{"configured":true,"writable":true}}""")
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = kotlinx.coroutines.flow.emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) {
                val request = json(text)
                val op = checkNotNull(request["op"].str())
                requests += op
                val result = when (op) {
                    "models.providers" -> buildJsonObject {
                        put("rows", JsonArray(listOf(JsonObject(provider.jsonObject + ("displayName" to JsonPrimitive(name))))))
                        put("namespaces", JsonArray(listOf(namespace)))
                        put("protocols", JsonArray(protocols.map(::JsonPrimitive)))
                        put("writable", true)
                    }
                    "models.providerGet" -> buildJsonObject {
                        put("provider", provider)
                        put("namespace", namespace)
                    }
                    else -> error("Unexpected operation $op")
                }
                onFrame.invoke(client, buildJsonObject {
                    put("id", checkNotNull(request["id"]))
                    put("ok", true)
                    put("result", result)
                }.toString())
            }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        val field = Pocket.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val previous = field.get(Pocket)
        val previousRevision = Pocket.modelSettingsRevision.value
        field.set(Pocket, client)
        Pocket.ops.value += "models.providers"
        try {
            rule.setContent { PocketTheme { ModelsSettingsScreen {} } }
            rule.onNodeWithText("Gateway").assertExists()
            rule.onNodeWithText("插件自定义界面").assertDoesNotExist()
            rule.runOnIdle {
                name = "Updated Gateway"
                Pocket.modelSettingsRevision.value++
            }
            rule.onNodeWithText("Updated Gateway").assertExists()
            rule.onNodeWithText("编辑").performClick()
            rule.onNodeWithContentDescription("API 密钥").assertExists()
            rule.onNodeWithText("保存").assertExists()
            assertTrue("models.providers" in requests)
            assertTrue("models.providerGet" in requests)
            assertFalse("ui.open" in requests)
        } finally {
            field.set(Pocket, previous)
            Pocket.modelSettingsRevision.value = previousRevision
            client.close()
        }
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
        assertEquals(listOf("text", "image"), modelInputSelection(json("""{"id":"x","input":[]}"""), false, listOf("text", "image")))
        assertEquals(listOf("text"), modelInputSelection(json("""{"id":"x","inputModalities":[]}"""), true, emptyList()))
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

    @Test fun capacitiesUseDesktopCompactSpelling() {
        assertEquals("1M", modelCapacitySpelling(JsonPrimitive(1_000_000)))
        assertEquals("256K", modelCapacitySpelling(JsonPrimitive(256_000)))
        assertEquals("2.3M", modelCapacitySpelling(JsonPrimitive(2_300_000)))
        assertEquals("777", modelCapacitySpelling(JsonPrimitive(777)))
        assertEquals("", modelCapacitySpelling(null))
    }

    @Test fun catalogUsesCompactRowsAndOnlyShowsModelOptionsWhenExpanded() {
        var models by mutableStateOf(json("""[{"id":"deepseek-flash","name":"DeepSeek-V4.1-Flash"},{"id":"deepseek-v4-pro","name":"DeepSeek-V4-Pro"}]""") as JsonArray)
        mount { NativeModelList(models, true, false, true, emptyList(), null, { models = it }, null) }
        rule.onNodeWithText("模型目录").assertExists()
        rule.onNodeWithText("正在使用适配器默认模型").assertExists()
        rule.onNodeWithContentDescription("上下文窗口 1").assertDoesNotExist()
        rule.onNodeWithContentDescription("模型选项 1").performClick()
        rule.onNodeWithContentDescription("上下文窗口 1").assertExists()
        rule.onNodeWithText("文本").assertExists()
        rule.onNodeWithContentDescription("删除模型 1").performClick()
        rule.runOnIdle { assertEquals(listOf("deepseek-v4-pro"), models.map { it["id"].str() }) }
        rule.onNodeWithContentDescription("上下文窗口 1").assertDoesNotExist()
    }

    @Test fun modelFormEditsNativeFieldsWithoutLosingUnknownMetadataAndResets() {
        val initial = json("""{"id":"vision","name":"Vision","inputModalities":["text","image"],"imagePixelBudget":100,"future":true}""")
        var models by mutableStateOf(JsonArray(listOf(initial)))
        var reset = false
        mount { NativeModelList(models, true, true, true, emptyList(), null, { models = it }, { reset = true }) }
        rule.onNodeWithContentDescription("显示名称 1").performTextReplacement("Updated")
        rule.onNodeWithContentDescription("模型选项 1").performScrollTo().performClick()
        rule.onNodeWithContentDescription("上下文窗口 1").performScrollTo().performTextInput("1M")
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

    @Test fun existingProviderExpandsAndCollapsesAdvancedConfiguration() {
        Pocket.ops.value += "credentials.unset"
        val snapshot = json("""{"provider":{"provider":"gateway","settingsNs":"llm-pi-ai","settingsPath":["providers","gateway"],"declared":true,"credential":{"configured":true,"writable":true}},"namespace":{"ns":"llm-pi-ai","revision":7,"user":{"providers":{"gateway":{"api":"openai-responses"}}},"value":{"providers":{"gateway":{}}}},"inheritedModels":[]}""")
        mount { ProviderSettingsEditor(snapshot, null, protocols, emptyList(), true, onBusy = {}, onClose = {}) }
        rule.onNodeWithText("API 密钥").assertExists()
        rule.onNodeWithText("API 地址").assertDoesNotExist()
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
        rule.onNodeWithContentDescription("模型 ID 1").assertIsNotEnabled()
        rule.onNodeWithText("保存").performScrollTo().assertIsNotEnabled()
    }

    @Test fun addModesPreserveDraftAndProtocolUsesProductNames() {
        mount { AddModelProvider(listOf(json("""{"provider":"openai"}""")), namespace, protocols, emptyList(), true, {}, {}) }
        rule.onNodeWithText("自定义模型 API").performClick()
        rule.onNodeWithContentDescription("Provider ID").performTextInput("my-gateway")
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
            rule.onNodeWithText("保存").performClick()
            rule.waitForIdle()
            assertFalse(closed)
            rule.onNodeWithText("保存").performClick()
            rule.waitForIdle()
            assertFalse(closed)
            rule.onNodeWithText("保存").performClick()
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
