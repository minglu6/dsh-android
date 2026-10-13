package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the real Pocket actor while Host unsubscribe/follow replies are deliberately delayed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class PocketFollowTest {
    private lateinit var client: PocketClient
    private val requests = Channel<JsonObject>(Channel.UNLIMITED)
    private val jobs = mutableListOf<Job>()
    private lateinit var restore: () -> Unit

    private fun field(name: String) = Pocket.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String): Any? = field(name).get(Pocket)
    private fun put(name: String, value: Any?) = field(name).set(Pocket, value)

    @Suppress("UNCHECKED_CAST")
    @Before fun setUp() {
        Pocket.init(RuntimeEnvironment.getApplication())
        val fields = listOf("client", "subId", "pendingFollowEpoch", "reducer", "inspectorLog", "permissionProjectionSeq",
            "modelProjectionSeq", "modelUsesDefault").associateWith(::get)
        val modelEpochs = listOf("modelCatalogEpoch", "modelSelectionEpoch").associateWith { (get(it) as AtomicLong).get() }
        val modelFlows = listOf("catalog", "model", "_catalogLoading", "_catalogError", "_modelPending")
            .associateWith { (get(it) as MutableStateFlow<Any?>).value }
        val epoch = get("followEpoch") as AtomicLong
        val originalEpoch = epoch.get()
        val attempts = get("followAttempts") as MutableMap<String, Int>
        val originalAttempts = attempts.toMap()
        val stray = get("stray") as MutableMap<String, MutableList<JsonObject>>
        val originalStray = stray.toMap()
        val chat = Pocket.chat.value
        val permission = Pocket.permission.value
        val feedback = Pocket.feedback.value
        val ops = Pocket.ops.value
        restore = {
            fields.forEach { (name, value) -> put(name, value) }
            modelEpochs.forEach { (name, value) -> (get(name) as AtomicLong).set(value) }
            modelFlows.forEach { (name, value) -> (get(name) as MutableStateFlow<Any?>).value = value }
            epoch.set(originalEpoch)
            attempts.clear(); attempts.putAll(originalAttempts)
            stray.clear(); stray.putAll(originalStray)
            Pocket.chat.value = chat
            Pocket.permission.value = permission
            Pocket.feedback.value = feedback
            Pocket.ops.value = ops
        }
        client = PocketClient()
        val transport = object : FrameTransport {
            override val route = Route.WsLocal
            override val incoming = emptyFlow<String>()
            override val closeReason: String? = null
            override suspend fun send(text: String) { requests.send(PocketJson.parseToJsonElement(text).jsonObject) }
            override fun close() {}
        }
        client.javaClass.getDeclaredField("transport").apply { isAccessible = true }.set(client, transport)
        put("client", client)
        put("subId", "sub-A")
        put("pendingFollowEpoch", null)
        put("reducer", TranscriptReducer())
        put("inspectorLog", SessionInspectorLog())
        put("permissionProjectionSeq", -1L)
        put("modelProjectionSeq", -1L)
        put("modelUsesDefault", false)
        invalidateModels()
        epoch.set(0)
        attempts.clear(); stray.clear()
        Pocket.chat.value = ChatState("A")
        Pocket.permission.value = emptyMap()
        Pocket.ops.value = emptySet()
    }

    @After fun tearDown() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
        client.close()
        requests.close()
        restore()
    }

    private suspend fun request(op: String): JsonObject = withTimeout(5000) {
        requests.receive().also { assertEquals(op, it["op"].str()) }
    }
    private fun reply(request: JsonObject, result: String) {
        val text = "{\"id\":${request["id"]},\"ok\":true,\"result\":$result}"
        client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }.invoke(client, text)
    }
    private fun push(sub: String, frame: String) {
        val value = PocketJson.parseToJsonElement("{\"ev\":\"follow\",\"subId\":\"$sub\",\"frame\":$frame}").jsonObject
        Pocket.javaClass.getDeclaredMethod("onPush", JsonObject::class.java).apply { isAccessible = true }.invoke(Pocket, value)
    }
    private fun projection(sub: String, session: String, seq: Int, mode: String) = push(sub,
        """{"type":"projection","sessionId":"$session","key":"permissions","seq":$seq,"value":{"currentValue":"$mode"}}""")
    private fun snapshot(sub: String, seq: Int = 10) = push(sub,
        """{"type":"snapshot","cursor":$seq,"records":[],"projections":{"asOfSeq":$seq,"values":{"permissions":{"currentValue":"workspace-write"}}}}""")
    private fun open(session: String) = Pocket.openSession(session).also { jobs += it }
    private suspend fun finish(job: Job) = withTimeout(5000) { job.join() }

    private fun invalidateModels() {
        Pocket.javaClass.getDeclaredMethod("invalidateModelCatalog").apply { isAccessible = true }.invoke(Pocket)
    }

    private fun reject(request: JsonObject, message: String = "writer held") {
        val text = """{"id":${request["id"]},"ok":false,"error":{"code":"session/writer-held","message":"$message"}}"""
        client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }.invoke(client, text)
    }

    private fun modelProjection(sub: String, session: String, seq: Int, next: String) = push(sub,
        """{"type":"projection","sessionId":"$session","key":"modelSelection","seq":$seq,"value":{"next":$next,"lastUsed":{"provider":"p","model":"last-used"}}}""")

    private fun modelCatalog(defaultModel: String = "default") = ModelCatalog.parse(PocketJson.parseToJsonElement(
        """{"groups":[{"id":"p","models":[{"id":"default","name":"Default"},{"id":"next","name":"Next"},{"id":"desktop","name":"Desktop"}]}],"default":{"provider":"p","model":"$defaultModel","reasoningEffort":"high"}}"""))

    @Test fun sidebarCreatesBlankSessionWithoutSendingPromptOrRequiringSelection() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val preparing = async { Pocket.prepareSidebarSession() }.also { jobs += it }
        reply(request("sessions.create"), """{"sessionId":"blank-sidebar"}""")
        val follow = request("follow")
        snapshot("sub-blank", 0)
        reply(follow, """{"subId":"sub-blank"}""")
        assertEquals("blank-sidebar", withTimeout(5000) { preparing.await() })
        assertEquals("blank-sidebar", Pocket.chat.value?.sessionId)
        assertTrue(Pocket.chat.value!!.loaded)
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun cancelledSidebarCreationCannotStealASelectedSession() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val preparing = async { Pocket.prepareSidebarSession() }.also { jobs += it }
        val create = request("sessions.create")
        val opening = open("B")
        val follow = request("follow")
        snapshot("sub-B")
        reply(follow, """{"subId":"sub-B"}""")
        finish(opening)
        reply(create, """{"sessionId":"late-blank"}""")
        assertNull(withTimeout(5000) { preparing.await() })
        assertEquals("B", Pocket.chat.value?.sessionId)
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun lateHistoryPageCannotLeakRawRecordsIntoAnotherSession() = runBlocking {
        push("sub-A", """{"type":"snapshot","cursor":10,"hasMore":true,"records":[{"type":"event","event":{"seq":10,"time":1,"type":"user/message","data":{"content":[{"type":"text","text":"A current"}]}}}]}""")
        val paging = Pocket.loadOlderHistory().also { jobs += it }
        val pageRequest = request("history.page")
        val opening = open("B")
        reply(request("unfollow"), """{"stopped":true}""")
        val follow = request("follow")
        push("sub-B", """{"type":"snapshot","cursor":2,"records":[{"type":"event","event":{"seq":2,"time":2,"type":"user/message","data":{"content":[{"type":"text","text":"B current"}]}}}]}""")
        reply(follow, """{"subId":"sub-B"}""")
        finish(opening)
        val before = Pocket.chat.value
        reply(pageRequest, """{"hasMore":false,"records":[{"type":"event","event":{"seq":1,"time":0,"type":"user/message","data":{"content":[{"type":"text","text":"A private history"}]}}}]}""")
        finish(paging)
        assertEquals(before, Pocket.chat.value)
        assertEquals("B", Pocket.chat.value?.sessionId)
        assertEquals(listOf(2L), Pocket.chat.value?.inspectorRecords?.map { it["seq"].long() })
    }

    @Test fun inspectorPagesOriginalEventsWithoutLosingLiveRecords() = runBlocking {
        push("sub-A", """{"type":"snapshot","cursor":10,"hasMore":true,"records":[{"type":"event","event":{"seq":10,"time":1234,"type":"custom/event","data":{"field":"original"}}}]}""")
        val paging = Pocket.loadOlderHistory().also { jobs += it }
        val pageRequest = request("history.page")
        push("sub-A", """{"type":"event","event":{"seq":11,"time":2345,"type":"custom/live","data":{"field":"live"}}}""")
        reply(pageRequest, """{"hasMore":false,"records":[{"type":"event","event":{"seq":1,"time":123,"type":"custom/older","data":{"field":"older"}}}]}""")
        finish(paging)
        val state = Pocket.chat.value!!
        assertEquals(listOf(1L, 10L, 11L), state.inspectorRecords.map { it["seq"].long() })
        assertEquals("original", state.inspectorRecords[1]["data"]["field"].str())
        assertFalse(state.loadingOlder)
        assertFalse(state.hasMore)
    }

    @Test fun detachedOldFramesCannotPoisonTheNewSessionBeforeUnsubscribeCompletes() = runBlocking {
        val opening = open("B")
        val unsubscribe = request("unfollow")
        assertEquals("B", Pocket.chat.value?.sessionId)
        projection("sub-A", "A", 1000, "auto")
        push("sub-A", """{"type":"event","event":{"type":"user/message","seq":1001,"time":1,"data":{"content":[{"type":"text","text":"old A message"}],"source":{"kind":"user"}}}}""")
        assertNull(Pocket.permission.value["B"])
        assertEquals(emptyList(), Pocket.chat.value?.items)
        reply(unsubscribe, "{\"stopped\":true}")
        val follow = request("follow")
        // New frames may be delivered before the RPC coroutine resumes.
        snapshot("sub-B")
        projection("sub-A", "A", 1002, "auto")
        reply(follow, "{\"subId\":\"sub-B\"}")
        finish(opening)
        assertEquals("workspace-write", Pocket.permission.value["B"])
        projection("sub-B", "A", 2000, "auto") // Misrouted owner on the accepted subscription is also rejected.
        projection("sub-B", "B", 11, "read-only")
        assertEquals("read-only", Pocket.permission.value["B"])
    }

    @Test fun lateFollowAcknowledgementCannotReplaceANewerSelection() = runBlocking {
        val openingB = open("B")
        reply(request("unfollow"), "{\"stopped\":true}")
        val followB = request("follow")
        val openingC = open("C")
        val followC = request("follow")
        snapshot("sub-C")
        reply(followC, "{\"subId\":\"sub-C\"}")
        finish(openingC)
        reply(followB, "{\"subId\":\"sub-B\"}")
        val staleUnsubscribe = request("unfollow")
        assertEquals("sub-B", staleUnsubscribe["args"]["subId"].str())
        reply(staleUnsubscribe, "{\"stopped\":true}")
        finish(openingB)
        projection("sub-B", "B", 1000, "auto")
        projection("sub-C", "C", 11, "read-only")
        assertEquals("C", Pocket.chat.value?.sessionId)
        assertEquals("read-only", Pocket.permission.value["C"])
        assertNull(Pocket.permission.value["B"])
    }

    @Test fun finishingOldChatCleanupCannotCloseTheNewChat() = runBlocking {
        val closing = Pocket.closeChat().also { jobs += it }
        val oldUnsubscribe = request("unfollow")
        assertNull(Pocket.chat.value)
        val opening = open("B")
        val follow = request("follow")
        snapshot("sub-B")
        reply(follow, "{\"subId\":\"sub-B\"}")
        finish(opening)
        reply(oldUnsubscribe, "{\"stopped\":true}")
        finish(closing)
        projection("sub-A", "A", 1000, "auto")
        projection("sub-B", "B", 11, "read-only")
        assertEquals("B", Pocket.chat.value?.sessionId)
        assertEquals("read-only", Pocket.permission.value["B"])
    }

    @Test fun modelSnapshotResumeAndLiveProjectionUseNextNotLastUsedAndRejectOldFrames() {
        Pocket.catalog.value = modelCatalog()
        push("sub-A", """{"type":"snapshot","cursor":10,"records":[],"projections":{"asOfSeq":10,"values":{"modelSelection":{"next":{"provider":"p","model":"next","reasoningEffort":"low"},"lastUsed":{"provider":"p","model":"last-used"}}}}}""")
        assertEquals(ModelChoice("p", "next", "Next", "low"), Pocket.model.value["A"])
        modelProjection("sub-A", "B", 100, """{"provider":"p","model":"desktop"}""")
        modelProjection("sub-A", "A", 9, """{"provider":"p","model":"desktop"}""")
        assertEquals("next", Pocket.model.value["A"]?.model)
        push("sub-A", """{"type":"resume","cursor":11,"projections":{"asOfSeq":11,"values":{"modelSelection":{"next":null,"lastUsed":{"provider":"p","model":"last-used"}}}}}""")
        assertEquals(ModelChoice("p", "default", "Default", "high"), Pocket.model.value["A"])
        modelProjection("sub-A", "A", 12, """{"provider":"p","model":"desktop","reasoningEffort":"max"}""")
        push("sub-A", """{"type":"snapshot","sessionId":"B","cursor":100,"records":[],"projections":{"asOfSeq":100,"values":{"modelSelection":{"next":null}}}}""")
        push("sub-A", """{"type":"resume","cursor":10,"projections":{"asOfSeq":10,"values":{"modelSelection":{"next":null}}}}""")
        assertEquals(ModelChoice("p", "desktop", "Desktop", "max"), Pocket.model.value["A"])
    }

    @Test fun modelSelectionWaitsForHostAndRejectsDuplicateWhileFailureKeepsOldSelection() = runBlocking {
        val previous = ModelChoice("p", "default", "Default", null)
        val desired = ModelChoice("p", "next", "Next", "high")
        Pocket.model.value = mapOf("A" to previous)
        val failure = async(start = CoroutineStart.UNDISPATCHED) { Pocket.errors.first() }
        var completed: Boolean? = null
        val selecting = Pocket.selectModel("A", desired) { completed = it }.also { jobs += it }
        val selection = request("models.select")
        assertEquals(desired, Pocket.modelPending.value)
        assertEquals(previous, Pocket.model.value["A"])
        assertNull(completed)
        var duplicate: Boolean? = null
        finish(Pocket.selectModel("A", desired) { duplicate = it }.also { jobs += it })
        assertEquals(false, duplicate)
        assertTrue(requests.tryReceive().isFailure)
        reject(selection)
        finish(selecting)
        assertEquals(false, completed)
        assertEquals(previous, Pocket.model.value["A"])
        assertNull(Pocket.modelPending.value)
        assertTrue(withTimeout(5000) { failure.await() }.contains("writer held"))
    }

    @Test fun successfulModelSelectionUsesHostNormalizedValue() = runBlocking {
        Pocket.catalog.value = modelCatalog()
        var completed = false
        val selecting = Pocket.selectModel("A", ModelChoice("p", "next", "Next", null)) { completed = it }
            .also { jobs += it }
        reply(request("models.select"), """{"selected":{"provider":"p","model":"next","reasoningEffort":"high"}}""")
        finish(selecting)
        assertTrue(completed)
        assertEquals(ModelChoice("p", "next", "Next", "high"), Pocket.model.value["A"])
        assertNull(Pocket.modelPending.value)
    }

    @Test fun newerDesktopProjectionOutranksDelayedSelectionAcknowledgement() = runBlocking {
        Pocket.catalog.value = modelCatalog()
        val selecting = Pocket.selectModel("A", ModelChoice("p", "next", "Next", null)).also { jobs += it }
        val selection = request("models.select")
        modelProjection("sub-A", "A", 30, """{"provider":"p","model":"desktop","reasoningEffort":"max"}""")
        reply(selection, """{"selected":{"provider":"p","model":"next"}}""")
        finish(selecting)
        assertEquals(ModelChoice("p", "desktop", "Desktop", "max"), Pocket.model.value["A"])
    }

    @Test fun connectionResetClearsPendingAndIgnoresLateSelectionCallback() = runBlocking {
        Pocket.catalog.value = modelCatalog()
        var completed: Boolean? = null
        val selecting = Pocket.selectModel("A", ModelChoice("p", "next", "Next", null)) { completed = it }
            .also { jobs += it }
        val selection = request("models.select")
        put("client", null)
        invalidateModels()
        assertNull(Pocket.catalog.value)
        assertNull(Pocket.modelPending.value)
        reply(selection, """{"selected":{"provider":"p","model":"next"}}""")
        finish(selecting)
        assertNull(completed)
        assertEquals(emptyMap(), Pocket.model.value)
    }

    @Test fun catalogRefreshFailureRetainsDataAndRetryRestoresDefaultSelection() = runBlocking {
        Pocket.catalog.value = modelCatalog()
        modelProjection("sub-A", "A", 10, "null")
        val loading = Pocket.reloadModelCatalog().also { jobs += it }
        val catalogRequest = request("models.catalog")
        assertTrue(Pocket.catalogLoading.value)
        assertEquals("default", Pocket.catalog.value?.defaultModel)
        reject(catalogRequest, "catalog offline")
        finish(loading)
        assertFalse(Pocket.catalogLoading.value)
        assertTrue(Pocket.catalogError.value.orEmpty().contains("catalog offline"))
        assertEquals("default", Pocket.model.value["A"]?.model)
        val retry = Pocket.reloadModelCatalog().also { jobs += it }
        reply(request("models.catalog"), """{"groups":[{"id":"p","models":[{"id":"next","name":"Next"}]}],"default":{"provider":"p","model":"next","reasoningEffort":"max"}}""")
        finish(retry)
        assertNull(Pocket.catalogError.value)
        assertEquals(ModelChoice("p", "next", "Next", "max"), Pocket.model.value["A"])
    }

    @Test fun newestCatalogRequestWinsAndHostResetRejectsLateResponse() = runBlocking {
        val first = Pocket.reloadModelCatalog().also { jobs += it }
        val old = request("models.catalog")
        val second = Pocket.reloadModelCatalog().also { jobs += it }
        val fresh = request("models.catalog")
        reply(fresh, """{"groups":[],"default":{"provider":"p","model":"new"}}""")
        finish(second)
        reply(old, """{"groups":[],"default":{"provider":"p","model":"old"}}""")
        finish(first)
        assertEquals("new", Pocket.catalog.value?.defaultModel)
        val last = Pocket.reloadModelCatalog().also { jobs += it }
        val stale = request("models.catalog")
        put("client", null)
        invalidateModels()
        reply(stale, """{"groups":[],"default":{"provider":"p","model":"stale"}}""")
        finish(last)
        assertNull(Pocket.catalog.value)
        assertFalse(Pocket.catalogLoading.value)
        assertNull(Pocket.catalogError.value)
    }

    @Test fun currentSessionModelProjectionBufferedBeforeFollowAcknowledgementIsApplied() = runBlocking {
        Pocket.catalog.value = modelCatalog()
        val opening = open("B")
        reply(request("unfollow"), """{"stopped":true}""")
        val follow = request("follow")
        modelProjection("sub-A", "A", 1000, """{"provider":"p","model":"desktop"}""")
        modelProjection("sub-B", "B", 10, """{"provider":"p","model":"next"}""")
        reply(follow, """{"subId":"sub-B"}""")
        finish(opening)
        assertEquals(ModelChoice("p", "next", "Next", null), Pocket.model.value["B"])
        assertNull(Pocket.model.value["A"])
    }
}
