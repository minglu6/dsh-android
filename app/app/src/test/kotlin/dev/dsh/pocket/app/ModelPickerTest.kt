package dev.dsh.pocket.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import dev.dsh.pocket.protocol.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@OptIn(ExperimentalTestApi::class)
class ModelPickerTest {
    @get:Rule val rule = createComposeRule()
    private val catalog = ModelCatalog.parse(PocketJson.parseToJsonElement("""{
      "default":{"provider":"team","model":"flash"},
      "groups":[{"id":"team","name":"Team models","models":[
        {"id":"flash","name":"DeepSeek Flash","reasoning":{"defaultEffort":"high","efforts":[{"id":"high","name":"深入"},{"id":"low","name":"快速"}]}},
        {"id":"pro","name":"DeepSeek Pro","reasoning":{"efforts":[{"id":"high","name":"深入"}]}},
        {"id":"plain","name":"Plain Model"},
        {"id":"qwen","name":"Qwen Coder"},
        {"id":"glm","name":"GLM Coding"}
      ]}]}"""))
    private val current = ModelChoice("team", "flash", "DeepSeek Flash", "high")

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        Pocket.prefs.language = AppLanguage.Zh
        Pocket.prefs.appearance = Appearance.Light
    }

    private fun mount(content: @Composable () -> Unit) {
        rule.setContent {
            PocketTheme {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomEnd) {
                    Box { content() }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun rootSeparatesModelAndEffortAndBackPreservesSelection() {
        var picks = 0
        mount { ModelPicker(catalog, current, {}, { _, _ -> picks++ }) }
        rule.onNodeWithText("推理等级").performClick()
        rule.onNodeWithText("快速").assertExists()
        rule.onNodeWithTag("modelSearch").assertDoesNotExist()
        rule.onNodeWithText("深入").assertIsSelected()
        rule.onNodeWithContentDescription("返回").assertDoesNotExist()
        rule.onNodeWithText("推理等级").assertDoesNotExist()
        rule.onNodeWithTag("modelPicker").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Back) }
        rule.onNodeWithText("模型").performClick()
        rule.onNodeWithTag("modelSearch").assertExists()
        rule.onNodeWithContentDescription("返回").assertDoesNotExist()
        rule.onNodeWithText("模型").assertDoesNotExist()
        rule.onNodeWithText("快速").assertDoesNotExist()
        assertEquals(0, picks)
    }

    @Test fun changingModelUsesProviderDefaultAndClosesOnlyAfterSuccess() {
        var picked: ModelChoice? = null
        var complete: ((Boolean) -> Unit)? = null
        val open = mutableStateOf(true)
        mount {
            if (open.value) ModelPicker(catalog, current, { open.value = false }, { value, done -> picked = value; complete = done }, openModels = true)
        }
        rule.onNodeWithText("DeepSeek Pro").performClick()
        rule.onNodeWithText("DeepSeek Pro").assertIsNotEnabled()
        rule.onNodeWithText("Plain Model").assertIsNotEnabled()
        rule.onNodeWithTag("modelPicker").assertExists()
        assertEquals(ModelChoice("team", "pro", "DeepSeek Pro", null), picked)
        rule.runOnIdle { assertNotNull(complete).invoke(true) }
        rule.onNodeWithTag("modelPicker").assertDoesNotExist()
    }

    @Test fun rejectionKeepsOldSelectionAndAllowsRetry() {
        var complete: ((Boolean) -> Unit)? = null
        val open = mutableStateOf(true)
        mount {
            if (open.value) ModelPicker(catalog, current, { open.value = false }, { _, done -> complete = done }, openModels = true)
        }
        rule.onNodeWithText("DeepSeek Pro").performClick()
        rule.runOnIdle { assertNotNull(complete).invoke(false) }
        rule.onNodeWithText("DeepSeek Flash").assertIsSelected()
        rule.onNodeWithText("DeepSeek Pro").assertIsEnabled().assertIsNotSelected()
        rule.onNodeWithTag("modelPicker").assertExists()
    }

    @Test fun selectingCurrentModelDoesNotResetItsEffort() {
        var calls = 0
        val open = mutableStateOf(true)
        mount {
            if (open.value) ModelPicker(catalog, current.copy(effort = "low"), { open.value = false }, { _, _ -> calls++ }, openModels = true)
        }
        rule.onNodeWithText("DeepSeek Flash").performClick()
        rule.onNodeWithTag("modelPicker").assertDoesNotExist()
        assertEquals(0, calls)
    }

    @Test fun absentDefaultEffortOffersProviderDefaultAndUsesHostNames() {
        var picked: ModelChoice? = null
        mount { ModelPicker(catalog, ModelChoice("team", "pro", "DeepSeek Pro", "high"), {}, { value, _ -> picked = value }) }
        rule.onNodeWithText("推理等级").performClick()
        rule.onNodeWithText("深入").assertIsSelected()
        rule.onNode(isSelectable() and isNotSelected()).performClick()
        assertEquals(ModelChoice("team", "pro", "DeepSeek Pro", null), picked)
    }

    @Test fun modelWithoutReasoningHasNoEffortEntry() {
        mount { ModelPicker(catalog, ModelChoice("team", "plain", "Plain Model", null), {}, { _, _ -> }) }
        rule.onNodeWithText("模型").assertExists()
        rule.onNodeWithText("推理等级").assertDoesNotExist()
    }

    @Test fun fuzzySearchCanBeClearedAfterNoResults() {
        mount { ModelPicker(catalog, current, {}, { _, _ -> }, openModels = true) }
        rule.onNodeWithTag("modelSearch").performTextInput("dsf")
        rule.onNodeWithText("DeepSeek Flash").assertExists()
        rule.onNodeWithText("DeepSeek Pro").assertDoesNotExist()
        rule.onNodeWithTag("modelSearch").performTextReplacement("zzzz")
        rule.onNodeWithText("没有匹配的模型").assertExists()
        rule.onNodeWithContentDescription("清除搜索").performClick()
        rule.onNodeWithText("DeepSeek Pro").assertExists()
        rule.onNodeWithTag("modelSearch").assertIsFocused()
    }

    @Test fun loadingAndFailureAreNotMisrepresentedAsEmptyCatalog() {
        val loading = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        mount { ModelPicker(null, null, {}, { _, _ -> }, loading = loading.value, error = error.value, openModels = true) }
        rule.onNodeWithText("暂无可用模型").assertDoesNotExist()
        rule.runOnIdle { loading.value = false; error.value = "连接失败" }
        rule.onNodeWithText("连接失败").assertExists()
        rule.onNodeWithText("重试").assertIsEnabled()
        rule.onNodeWithText("暂无可用模型").assertDoesNotExist()
    }

    @Test fun keyboardBackLeavesSubmenuBeforeClosingPicker() {
        val open = mutableStateOf(true)
        mount { if (open.value) ModelPicker(catalog, current, { open.value = false }, { _, _ -> }) }
        rule.onNodeWithText("模型").performClick()
        rule.onNodeWithTag("modelSearch").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Escape) }
        rule.onNodeWithText("推理等级").assertExists()
        rule.onNodeWithTag("modelPicker").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Back) }
        rule.onNodeWithTag("modelPicker").assertDoesNotExist()
    }

    @Test fun keyboardCanChooseSearchResultWithoutMovingFocusFromSearch() {
        var picked: ModelChoice? = null
        mount { ModelPicker(catalog, current, {}, { value, _ -> picked = value }, openModels = true) }
        rule.onNodeWithTag("modelSearch").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }
        rule.onNodeWithTag("modelSearch").assertIsFocused()
        rule.onNodeWithTag("modelSearch").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Enter) }
        assertEquals("pro", picked?.model)
    }

    @Test fun releaseBadgeFollowsCatalogIdentityWithoutChangingSelectionPayload() {
        val flash = ModelInfo("deepseek-official", "deepseek-flash", "DeepSeek-V4.1-Flash", null, emptyList(), null)
        val pro = flash.copy(id = "deepseek-pro", name = "DeepSeek-V4.1-Pro")
        val models = catalog.copy(groups = listOf(ModelGroup(flash.provider, "DeepSeek", listOf(flash, pro))))
        var picked: ModelChoice? = null
        val selected = mutableStateOf(ModelChoice(flash.provider, flash.id, flash.name, null))
        mount {
            ModelPicker(models, selected.value, {}, { choice, done -> picked = choice; selected.value = choice; done(true) })
        }
        rule.onNodeWithTag("modelReleaseBadge", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("模型").performClick()
        rule.onNodeWithTag("modelReleaseBadge", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText(pro.name).performClick()
        assertEquals(ModelChoice(pro.provider, pro.id, pro.name, null), picked)
        rule.onNodeWithText(flash.name).performClick()
        assertEquals(ModelChoice(flash.provider, flash.id, flash.name, null), picked)
    }

    @Test
    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    fun composerPickerAppliesDraftAndReopensAtItsCurrentEffort() {
        val payload = """{"default":{"provider":"p","model":"flash"},"groups":[{"id":"p","name":"Provider","models":[
            {"id":"flash","name":"DeepSeek Flash"},
            {"id":"pro","name":"DeepSeek Pro","reasoning":{"defaultEffort":"high","efforts":[{"id":"high","name":"High"},{"id":"low","name":"Low"}]}}
        ]}]}"""
        val client = PocketClient()
        val onFrame = client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = kotlinx.coroutines.flow.emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) {
                val request = PocketJson.parseToJsonElement(text)
                check(request["op"].str() == "models.catalog")
                onFrame.invoke(client, """{"id":${request["id"]},"ok":true,"result":$payload}""")
            }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        val field = Pocket.javaClass.getDeclaredField("client").apply { isAccessible = true }
        val previous = field.get(Pocket)
        field.set(Pocket, client)
        try {
            Pocket.active.value = "model-ui"
            Pocket.status.value = mapOf("model-ui" to Dot.Online)
            Pocket.chat.value = null
            Pocket.catalog.value = ModelCatalog.parse(PocketJson.parseToJsonElement(payload))
            Pocket.ops.value = setOf("models.catalog", "models.select")
            rule.setContent { PocketTheme { ChatRoot({}) } }
            rule.onNodeWithTag("composerInput").performTextInput("保留这段草稿")
            rule.onNodeWithTag("composerModel").performClick()
            rule.onNodeWithText("模型").performClick()
            rule.waitForIdle()
            com.github.takahirom.roborazzi.captureScreenRoboImage("build/screenshots/10e-model-picker-composer.png")
            rule.onNodeWithText("DeepSeek Pro").performClick()
            rule.onNodeWithTag("modelPicker").assertDoesNotExist()
            rule.onNodeWithTag("composerModelName", useUnmergedTree = true).assertTextEquals("DeepSeek Pro")
            rule.onNodeWithTag("composerInput").assertTextContains("保留这段草稿")
            rule.onNodeWithTag("composerModel").performClick()
            rule.onNodeWithText("推理等级").performClick()
            rule.onNodeWithText("Low").performClick()
            rule.onNodeWithTag("modelPicker").assertDoesNotExist()
            rule.onNodeWithTag("composerModel").assertContentDescriptionEquals("DeepSeek Pro · Low")
        } finally {
            client.close()
            field.set(Pocket, previous)
        }
    }
}
