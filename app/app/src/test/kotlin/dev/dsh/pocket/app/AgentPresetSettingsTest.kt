package dev.dsh.pocket.app

import androidx.compose.ui.test.*
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import org.junit.After
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
class AgentPresetSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val clientField = Pocket.javaClass.getDeclaredField("client").apply { isAccessible = true }
    private var previousClient: Any? = null
    private var previousHost: String? = null
    private var previousOps = emptySet<String>()
    private var previousStatus = emptyMap<String, Dot>()
    private var previousRoster = emptyList<JsonObject>()
    private var previousSettingsRevision = 0L
    private val clients = mutableListOf<PocketClient>()
    private lateinit var preferences: HostSettingsFixture
    private val requests = mutableListOf<JsonElement>()
    private var revision = 7L
    private var default = "standard"
    private var writable = true
    private var extra = emptyList<JsonElement>()
    private var mutate: suspend (JsonElement) -> JsonElement = { request ->
        revision++
        default = request["args"]["ops"].arr()!!.single()["value"].str()!!
        jsonOf("ok" to true)
    }
    private var read: suspend (JsonElement) -> JsonElement = { jsonOf("content" to "- id: ${it["args"]["id"].str()}\n  config: {}") }

    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        previousClient = clientField.get(Pocket)
        previousHost = Pocket.active.value
        previousOps = Pocket.ops.value
        previousStatus = Pocket.status.value
        previousRoster = Pocket.presetRoster.value
        previousSettingsRevision = Pocket.modelSettingsRevision.value
        preferences = installHostSettingsFixture()
        preferences.desktopSet(SharedSetting.CodeWork, JsonPrimitive(true))
        Pocket.active.value = "preset-test"
        Pocket.status.value = mapOf("preset-test" to Dot.Online)
        Pocket.ops.value = setOf("presets.settings", "presets.read", "models.settingsMutate", "sessions.create")
        clientField.set(Pocket, client { request ->
            requests += request
            when (request["op"].str()) {
                "presets.settings" -> roster()
                "models.settingsMutate" -> mutate(request)
                "presets.read" -> read(request)
                else -> error("Unexpected request ${request["op"]}")
            }
        })
    }

    @After fun cleanup() {
        clients.forEach(PocketClient::close)
        clientField.set(Pocket, previousClient)
        Pocket.active.value = previousHost
        Pocket.ops.value = previousOps
        Pocket.status.value = previousStatus
        Pocket.presetRoster.value = previousRoster
        Pocket.modelSettingsRevision.value = previousSettingsRevision
    }

    private fun roster(): JsonElement = buildJsonObject {
        put("writable", writable)
        put("namespace", jsonOf("revision" to revision))
        put("presets", JsonArray(listOf("standard", "ptc", "minimal", "cordis").map { id ->
            jsonOf("id" to id, "isDefault" to (id == default))
        } + extra))
    }

    private fun client(handle: suspend (JsonElement) -> JsonElement): PocketClient {
        val client = PocketClient()
        val onFrame = client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) {
                val request = Json.parseToJsonElement(text)
                val result = handle(request)
                onFrame.invoke(client, buildJsonObject {
                    put("id", checkNotNull(request["id"]))
                    put("ok", true)
                    put("result", result)
                }.toString())
            }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        clients += client
        return client
    }

    private fun mount(onBack: () -> Unit = {}, onCreator: (() -> Unit)? = null) {
        rule.setContent { PocketTheme { AgentPresetSettingsScreen(onBack, onCreator) } }
        rule.waitForIdle()
    }

    private fun select(id: String) = rule.onNodeWithTag("preset-select-$id").performScrollTo().performClick()
    private fun writes() = requests.filter { it["op"].str() == "models.settingsMutate" }

    @Test fun cardClickWritesObservedRevisionAndConfirmsActualDefault() {
        mount()
        select("ptc")
        rule.onNodeWithTag("preset-select-ptc").assertIsSelected()
        rule.onNodeWithTag("preset-select-standard").assertIsNotSelected()
        assertEquals(1, writes().size)
        val args = writes().single()["args"]
        assertEquals(7L, (args["expectedRevision"] as JsonPrimitive).long)
        assertEquals("agent-preset-registry", args["ns"].str())
        assertEquals(listOf("presets.settings", "models.settingsMutate", "presets.settings"), requests.map { it["op"].str() })
    }

    @Test fun desktopDefaultChangeUpdatesOpenPageAndNextWriteUsesFreshRevision() {
        mount()
        rule.runOnIdle {
            revision = 8L
            default = "ptc"
            Pocket.modelSettingsRevision.value++
        }
        rule.onNodeWithTag("preset-select-ptc").assertIsSelected()
        rule.onNodeWithTag("preset-select-standard").assertIsNotSelected()
        assertTrue(writes().isEmpty())
        select("standard")
        assertEquals(8L, (writes().single()["args"]["expectedRevision"] as JsonPrimitive).long)
        rule.onNodeWithTag("preset-select-standard").assertIsSelected()
    }

    @Test fun notificationDuringSaveWaitsForReadbackAndPreservesConflict() {
        val release = CompletableDeferred<Unit>()
        mutate = {
            release.await()
            jsonOf("ok" to false, "error" to jsonOf("message" to "settings/conflict"))
        }
        mount()
        select("ptc")
        rule.runOnIdle {
            revision = 8L
            default = "minimal"
            Pocket.modelSettingsRevision.value++
        }
        rule.waitForIdle()
        // No external refresh may race the in-flight write's authoritative read-back.
        assertEquals(1, requests.count { it["op"].str() == "presets.settings" })
        rule.runOnIdle { release.complete(Unit) }
        rule.onNodeWithTag("preset-select-minimal").assertIsSelected()
        rule.onNodeWithText("settings/conflict").assertExists()
        select("standard")
        assertEquals(8L, (writes().last()["args"]["expectedRevision"] as JsonPrimitive).long)
    }

    @Test fun externalRefreshKeepsOpenPresetHelp() {
        mount()
        rule.onNodeWithTag("preset-usage-standard").performScrollTo().performClick()
        rule.runOnIdle {
            revision++
            default = "ptc"
            Pocket.modelSettingsRevision.value++
        }
        rule.onNodeWithTag("presetGuideDialog").assertExists()
        rule.onNodeWithTag("presetGuideTabUsage").assertIsSelected()
        rule.onNodeWithTag("presetGuideClose").performClick()
        rule.onNodeWithTag("preset-select-ptc").assertIsSelected()
        assertTrue(writes().isEmpty())
    }

    @Test fun conflictReadsNewRevisionAndRetryDoesNotReuseStaleCas() {
        var attempts = 0
        mutate = { request ->
            attempts++
            revision++
            if (attempts == 1) jsonOf("ok" to false, "error" to jsonOf("message" to "settings/conflict"))
            else {
                default = request["args"]["ops"].arr()!!.single()["value"].str()!!
                jsonOf("ok" to true)
            }
        }
        mount()
        select("ptc")
        rule.onNodeWithTag("preset-select-standard").assertIsSelected()
        rule.onNodeWithText("settings/conflict").assertExists()
        select("ptc")
        rule.onNodeWithTag("preset-select-ptc").assertIsSelected()
        assertEquals(listOf(7L, 8L), writes().map { (it["args"]["expectedRevision"] as JsonPrimitive).long })
    }

    @Test fun higherPriorityOverrideNeverShowsRequestedCardAsDefault() {
        mutate = { revision++; jsonOf("ok" to true) }
        mount()
        select("ptc")
        rule.onNodeWithTag("preset-select-standard").assertIsSelected()
        rule.onNodeWithTag("preset-select-ptc").assertIsNotSelected()
        rule.onNodeWithText("更高优先级覆盖", substring = true).assertExists()
        assertEquals(2, requests.count { it["op"].str() == "presets.settings" })
    }

    @Test fun helpAndConfigurationCloseOnlyTheirOwnLayerWithoutMutatingDefault() {
        var backs = 0
        mount(onBack = { backs++ })
        rule.onNodeWithTag("preset-explanation-standard").performScrollTo().performClick()
        rule.onNodeWithTag("presetGuideTabExplanation").assertIsSelected()
        rule.onNodeWithTag("presetGuideTabUsage").performClick().assertIsSelected()
        rule.onNodeWithTag("presetGuideClose").performClick()
        rule.onNodeWithTag("presetGuideDialog").assertDoesNotExist()
        rule.onNodeWithTag("preset-usage-standard").performClick()
        rule.onNodeWithTag("presetGuideTabUsage").assertIsSelected()
        rule.onNodeWithTag("presetGuideClose").performClick()
        rule.onNodeWithTag("preset-view-standard").performClick()
        rule.onNodeWithTag("preset-configuration-dialog").assertExists()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithTag("preset-configuration-close").performClick()
        rule.onNodeWithTag("preset-configuration-dialog").assertDoesNotExist()
        rule.onNodeWithTag("preset-select-standard").assertIsSelected()
        assertEquals(0, backs)
        assertTrue(writes().isEmpty())
    }

    @Test fun brokenAndReadOnlyCardsRejectSelectionButConfigurationRemainsReadable() {
        writable = false
        extra = listOf(jsonOf("id" to "broken", "name" to "Broken", "broken" to "invalid YAML"))
        mount()
        rule.onNodeWithTag("preset-select-ptc").assertIsNotEnabled()
        rule.onNodeWithTag("preset-select-broken").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("preset-explanation-broken").assertDoesNotExist()
        rule.onNodeWithTag("preset-view-broken").performScrollTo().performClick()
        rule.onNodeWithText("- id: broken\n  config: {}").assertExists()
        rule.onNodeWithTag("preset-configuration-dismiss").performClick()
        assertTrue(writes().isEmpty())
        assertEquals("broken", requests.last()["args"]["id"].str())
    }

    @Test fun brokenCardIsDisabledEvenWhenHealthyCardsAreWritable() {
        extra = listOf(jsonOf("id" to "broken", "broken" to "invalid YAML"))
        mount()
        rule.onNodeWithTag("preset-select-ptc").assertIsEnabled()
        rule.onNodeWithTag("preset-select-broken").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("preset-view-broken").performScrollTo().performClick()
        rule.onNodeWithTag("preset-configuration-dialog").assertExists()
        assertTrue(writes().isEmpty())
    }

    @Test fun supersededReadCannotReplaceAnotherPresetConfiguration() {
        val release = CompletableDeferred<Unit>()
        read = { request ->
            val id = request["args"]["id"].str()
            if (id == "standard") release.await()
            jsonOf("content" to "preset: $id")
        }
        mount()
        rule.onNodeWithTag("preset-view-standard").performScrollTo().performClick()
        rule.onNodeWithTag("preset-configuration-dialog").assertDoesNotExist()
        rule.onNodeWithTag("preset-view-ptc").performScrollTo().performClick()
        rule.onNodeWithText("preset: ptc").assertExists()
        rule.runOnIdle { release.complete(Unit) }
        rule.onNodeWithText("preset: standard").assertDoesNotExist()
        rule.onNodeWithText("preset: ptc").assertExists()
        assertTrue(writes().isEmpty())
    }

    @Test fun configFailureRetryIsIndependentOfDefaultSelection() {
        var attempts = 0
        read = {
            attempts++
            if (attempts == 1) error("composition unavailable")
            jsonOf("content" to "recovered: true")
        }
        mount()
        rule.onNodeWithTag("preset-view-standard").performScrollTo().performClick()
        rule.onNodeWithText("composition unavailable", substring = true).assertExists()
        rule.onNodeWithTag("preset-view-standard").performScrollTo().performClick()
        rule.onNodeWithText("recovered: true").assertExists()
        rule.onNodeWithTag("preset-configuration-dismiss").performClick()
        select("ptc")
        rule.onNodeWithTag("preset-select-ptc").assertIsSelected()
        assertEquals(2, attempts)
        assertEquals(1, writes().size)
    }

    @Test fun lateOldHostWriteCannotReadOrUpdateNewHostState() {
        val release = CompletableDeferred<Unit>()
        mutate = { release.await(); jsonOf("ok" to true) }
        mount()
        select("ptc")
        rule.waitUntil { writes().size == 1 }
        var nextReads = 0
        val next = client { request ->
            assertEquals("presets.settings", request["op"].str())
            nextReads++
            jsonOf("writable" to true, "namespace" to jsonOf("revision" to 21), "presets" to listOf(jsonOf("id" to "other", "name" to "Other host", "isDefault" to true)))
        }
        rule.runOnIdle {
            clientField.set(Pocket, next)
            Pocket.active.value = "other-host"
            Pocket.status.value = mapOf("other-host" to Dot.Online)
        }
        rule.onNodeWithTag("preset-select-other").assertIsSelected()
        rule.runOnIdle { release.complete(Unit) }
        rule.waitForIdle()
        rule.onNodeWithTag("preset-select-other").assertIsSelected()
        rule.onNodeWithTag("preset-select-ptc").assertDoesNotExist()
        assertEquals(1, nextReads)
        assertEquals(1, requests.count { it["op"].str() == "presets.settings" })
        assertEquals("other", Pocket.presetRoster.value.single()["id"].str())
    }

    @Test fun hostCodingPreferenceHidesOnlyShippedCodingModesAndCreatorDoesNotWriteDefault() {
        var created = 0
        extra = listOf(jsonOf("id" to "custom", "name" to "My mode"))
        mount(onCreator = { created++ })
        rule.runOnIdle { preferences.desktopSet(SharedSetting.CodeWork, JsonPrimitive(false)) }
        rule.onNodeWithTag("preset-select-ptc").assertDoesNotExist()
        rule.onNodeWithTag("preset-select-minimal").assertDoesNotExist()
        rule.onNodeWithTag("preset-select-custom").assertExists()
        rule.onNodeWithTag("preset-creator").performScrollTo().performClick()
        assertEquals(1, created)
        assertTrue(writes().isEmpty())
        assertFalse(isBuiltInPreset(jsonOf("id" to "standard", "name" to "My standard")))
        assertFalse(presetRequiresCodingTools(jsonOf("id" to "ptc", "name" to "My PTC")))
        assertFalse(isBuiltInPreset(jsonOf("id" to "unknown")))
    }

    @Test fun disconnectDisablesRemoteActionsButRetainsConfigurationAndHelp() {
        var created = 0
        mount(onCreator = { created++ })
        rule.onNodeWithTag("preset-view-standard").performScrollTo().performClick()
        val requestCount = requests.size
        rule.runOnIdle {
            Pocket.status.value = mapOf("preset-test" to Dot.Offline)
            Pocket.ops.value = emptySet()
        }
        rule.onNodeWithTag("preset-configuration-dialog").assertExists()
        rule.onNodeWithText("- id: standard\n  config: {}").assertExists()
        rule.onNodeWithTag("preset-configuration-close").performClick()
        rule.onNodeWithTag("preset-select-ptc").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("preset-view-ptc").assertIsNotEnabled()
        rule.onNodeWithTag("preset-creator").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("当前主机仅支持查看预设，默认设置为只读").assertDoesNotExist()
        rule.onNodeWithTag("preset-usage-standard").performScrollTo().performClick()
        rule.onNodeWithTag("presetGuideDialog").assertExists()
        rule.onNodeWithTag("presetGuideClose").performClick()
        assertEquals(requestCount, requests.size)
        assertEquals(0, created)
        assertTrue(writes().isEmpty())
    }

    @Test fun reconnectRefreshesRevisionWithoutResubmittingSelection() {
        mount()
        rule.runOnIdle { Pocket.status.value = mapOf("preset-test" to Dot.Offline) }
        rule.onNodeWithTag("preset-select-ptc").assertIsNotEnabled()
        rule.runOnIdle {
            revision = 12L
            default = "minimal"
            Pocket.status.value = mapOf("preset-test" to Dot.Online)
        }
        rule.onNodeWithTag("preset-select-minimal").assertIsSelected()
        assertTrue(writes().isEmpty())
        select("ptc")
        assertEquals(12L, (writes().single()["args"]["expectedRevision"] as JsonPrimitive).long)
    }

    @Test fun narrowScreenUsesSingleColumn() {
        mount()
        val first = rule.onNodeWithTag("preset-select-standard").fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag("preset-select-ptc").fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, second.left)
        assertTrue(second.top > first.bottom)
        rule.onNodeWithTag("preset-creator").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h1000dp-xhdpi")
    fun wideDarkScreenUsesTwoColumnsWithIndependentActions() {
        preferences.desktopSet(SharedSetting.Appearance, JsonPrimitive("dark"))
        mount()
        val first = rule.onNodeWithTag("preset-select-standard").fetchSemanticsNode().boundsInRoot
        val second = rule.onNodeWithTag("preset-select-ptc").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, second.top)
        assertTrue(second.left > first.right)
        rule.onNodeWithTag("preset-view-ptc").performClick()
        rule.onNodeWithTag("preset-configuration-dialog").assertExists()
        assertTrue(writes().isEmpty())
    }
}
