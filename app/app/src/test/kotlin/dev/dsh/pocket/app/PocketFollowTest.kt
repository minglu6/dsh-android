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
        (get("transcriptPublisher") as TranscriptPublisher).cancel()
        val fields = listOf("client", "subId", "pendingFollowEpoch", "reducer", "inspectorLog", "permissionProjectionSeq",
            "modelProjectionSeq", "modelUsesDefault", "firstPromptSessionId", "continuityRepairAttempts",
            "desiredViewedSession", "completionReadySession", "completionViewClient", "completionViewSession").associateWith(::get)
        val modelEpochs = listOf("modelCatalogEpoch", "modelSelectionEpoch").associateWith { (get(it) as AtomicLong).get() }
        val modelFlows = listOf("catalog", "model", "_catalogLoading", "_catalogError", "_modelPending",
            "_firstPrompt", "permissionCatalog", "permissionCatalogLoading")
            .associateWith { (get(it) as MutableStateFlow<Any?>).value }
        val epoch = get("followEpoch") as AtomicLong
        val originalEpoch = epoch.get()
        val attempts = get("followAttempts") as MutableMap<String, Int>
        val originalAttempts = attempts.toMap()
        val stray = get("stray") as MutableMap<String, MutableList<JsonObject>>
        val originalStray = stray.toMap()
        val chat = Pocket.chat.value
        val sessions = Pocket.sessions.value
        val permission = Pocket.permission.value
        val feedback = Pocket.feedback.value
        val ops = Pocket.ops.value
        val active = Pocket.active.value
        val status = Pocket.status.value
        restore = {
            fields.forEach { (name, value) -> put(name, value) }
            modelEpochs.forEach { (name, value) -> (get(name) as AtomicLong).set(value) }
            modelFlows.forEach { (name, value) -> (get(name) as MutableStateFlow<Any?>).value = value }
            epoch.set(originalEpoch)
            attempts.clear(); attempts.putAll(originalAttempts)
            stray.clear(); stray.putAll(originalStray)
            Pocket.chat.value = chat
            Pocket.sessions.value = sessions
            Pocket.permission.value = permission
            Pocket.feedback.value = feedback
            Pocket.ops.value = ops
            Pocket.active.value = active
            Pocket.status.value = status
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
        put("continuityRepairAttempts", 0)
        put("reducer", TranscriptReducer())
        put("inspectorLog", SessionInspectorLog())
        put("permissionProjectionSeq", -1L)
        put("modelProjectionSeq", -1L)
        put("modelUsesDefault", false)
        put("firstPromptSessionId", null)
        (get("_firstPrompt") as MutableStateFlow<FirstPrompt?>).value = null
        invalidateModels()
        epoch.set(0)
        attempts.clear(); stray.clear()
        Pocket.chat.value = ChatState("A")
        Pocket.sessions.value = emptyList()
        Pocket.permission.value = emptyMap()
        Pocket.ops.value = emptySet()
        put("desiredViewedSession", null)
        put("completionReadySession", null)
        put("completionViewClient", null)
        put("completionViewSession", null)
        (get("completionReminders") as CompletionReminders).reset()
    }

    @After fun tearDown() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
        (get("transcriptPublisher") as TranscriptPublisher).cancel()
        (get("completionReminders") as CompletionReminders).reset()
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

    @Test fun unreadClearsOnlyAfterForegroundConversationHasARealSnapshot() = runBlocking {
        Pocket.ops.value = setOf("completion.view")
        fun state(revision: Int, ids: String = "[]") =
            """{"epoch":"host","revision":$revision,"unreadIds":$ids}"""
        fun completion(revision: Int, ids: String) {
            val event = PocketJson.parseToJsonElement("""{"ev":"completion","epoch":"host","revision":$revision,"unreadIds":$ids}""").jsonObject
            Pocket.javaClass.getDeclaredMethod("onPush", JsonObject::class.java).apply { isAccessible = true }.invoke(Pocket, event)
        }
        completion(1, """["A"]""")
        finish(Pocket.setViewedSession("A"))
        val beforeLoad = request("completion.view")
        assertTrue(beforeLoad["args"]["sessionId"] is JsonNull)
        reply(beforeLoad, state(1, """["A"]"""))
        assertEquals(setOf("A"), Pocket.completionUnread.value)
        snapshot("sub-A")
        val viewed = request("completion.view")
        assertEquals("A", viewed["args"]["sessionId"].str())
        completion(3, "[]")
        reply(viewed, state(2, """["A"]"""))
        finish(Pocket.setViewedSession(null))
        val background = request("completion.view")
        assertTrue(background["args"]["sessionId"] is JsonNull)
        reply(background, state(3))
        completion(4, """["A"]""")
        assertEquals(setOf("A"), Pocket.completionUnread.value)
        // A background follow frame is not a new read acknowledgement.
        snapshot("sub-A", 11)
        delay(100)
        assertTrue(requests.tryReceive().isFailure)
    }

    private fun invalidateModels() {
        Pocket.javaClass.getDeclaredMethod("invalidateModelCatalog").apply { isAccessible = true }.invoke(Pocket)
    }

    private fun reject(request: JsonObject, message: String = "writer held", code: String = "session/writer-held") {
        val text = """{"id":${request["id"]},"ok":false,"error":{"code":"$code","message":"$message"}}"""
        client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }.invoke(client, text)
    }

    private fun modelProjection(sub: String, session: String, seq: Int, next: String) = push(sub,
        """{"type":"projection","sessionId":"$session","key":"modelSelection","seq":$seq,"value":{"next":$next,"lastUsed":{"provider":"p","model":"last-used"}}}""")

    private fun modelCatalog(defaultModel: String = "default") = ModelCatalog.parse(PocketJson.parseToJsonElement(
        """{"groups":[{"id":"p","models":[{"id":"default","name":"Default"},{"id":"next","name":"Next"},{"id":"desktop","name":"Desktop"}]}],"default":{"provider":"p","model":"$defaultModel","reasoningEffort":"high"}}"""))

    private fun enableQueueActions() {
        Pocket.active.value = "queue-host"
        Pocket.status.value = mapOf("queue-host" to Dot.Online)
        Pocket.ops.value = setOf("queue.update")
    }

    private fun queueRow(text: String = "original", extra: String = "") =
        """{"id":"queued","role":"user","source":{"kind":"user","rpcId":"request"},"content":[{"type":"text","text":"$text"}$extra]}"""

    private fun queueProjection(seq: Int, row: String = queueRow(), sub: String = "sub-A", session: String = "A", steer: String = "") =
        push(sub, """{"type":"projection","sessionId":"$session","key":"inbox","seq":$seq,"value":{"next-turn":[$row],"next-step":[$steer]}}""")

    @Test fun queuedEditAndRemoveWaitForHostProjectionRatherThanMutationReply() = runBlocking {
        enableQueueActions()
        queueProjection(1)
        var completed: Boolean? = null
        val editing = Pocket.updateQueuedPrompt("A", "queued", QueueAction.Edit("revised")) { completed = it }.also { jobs += it }
        val edit = request("queue.update")
        assertEquals("A", edit["args"]["sessionId"].str())
        assertEquals("queued", edit["args"]["itemId"].str())
        assertEquals("edit", edit["args"]["action"]["kind"].str())
        assertEquals("revised", edit["args"]["action"]["content"].arr()!!.single()["text"].str())
        reply(edit, "{}")
        finish(editing)
        assertEquals(true, completed)
        assertEquals("original", Pocket.chat.value!!.queuedPrompts.single().text)
        queueProjection(2, queueRow("revised"))
        assertEquals("revised", Pocket.chat.value!!.queuedPrompts.single().text)
        val removing = Pocket.updateQueuedPrompt("A", "queued", QueueAction.Remove).also { jobs += it }
        val remove = request("queue.update")
        assertEquals("remove", remove["args"]["action"]["kind"].str())
        reply(remove, "{}")
        finish(removing)
        assertEquals("revised", Pocket.chat.value!!.queuedPrompts.single().text)
        queueProjection(3, "")
        assertTrue(Pocket.chat.value!!.queuedPrompts.isEmpty())
    }

    @Test fun queuedEditCannotEraseAttachmentsOrUnknownContent() = runBlocking {
        enableQueueActions()
        listOf("image", "file", "future-content").forEachIndexed { index, type ->
            queueProjection(index + 1, queueRow(extra = """,{"type":"$type"}"""))
            val before = Pocket.chat.value
            val failure = async(start = CoroutineStart.UNDISPATCHED) { Pocket.errors.first() }
            var completed: Boolean? = null
            finish(Pocket.updateQueuedPrompt("A", "queued", QueueAction.Edit("replacement")) { completed = it }.also { jobs += it })
            assertEquals(false, completed)
            assertEquals("含附件的排队消息不能编辑", withTimeout(5000) { failure.await() })
            assertTrue(requests.tryReceive().isFailure)
            assertEquals(before, Pocket.chat.value)
        }
    }

    @Test fun unconfirmedQueuedPromptDisablesAllActionsEvenAfterRpcAcknowledgement() = runBlocking {
        installHostSettingsFixture()
        enableQueueActions()
        push("sub-A", """{"type":"event","event":{"seq":1,"time":1,"type":"turn/start","data":{"turn":1}}}""")
        val sending = Pocket.send("waiting").also { jobs += it }
        val prompt = request("prompt")
        reply(prompt, "{}")
        finish(sending)
        val row = Pocket.chat.value!!.queuedPrompts.single()
        assertTrue(row.sending)
        listOf(QueueAction.Edit("new"), QueueAction.Remove, QueueAction.Steer).forEach { action ->
            var completed: Boolean? = null
            finish(Pocket.updateQueuedPrompt("A", row.id, action) { completed = it }.also { jobs += it })
            assertEquals(false, completed)
            assertTrue(requests.tryReceive().isFailure)
            assertEquals(row, Pocket.chat.value!!.queuedPrompts.single())
        }
    }

    @Test fun queuedActionsRequireOnlineCapabilityExistingRowAndRunningSteer() = runBlocking {
        enableQueueActions()
        queueProjection(1)
        suspend fun refused(action: QueueAction, id: String = "queued") {
            var completed: Boolean? = null
            finish(Pocket.updateQueuedPrompt("A", id, action) { completed = it }.also { jobs += it })
            assertEquals(false, completed)
            assertTrue(requests.tryReceive().isFailure)
            assertEquals("original", Pocket.chat.value!!.queuedPrompts.single().text)
        }
        refused(QueueAction.Steer)
        refused(QueueAction.Edit(" "))
        refused(QueueAction.Remove, "missing")
        Pocket.ops.value = emptySet()
        refused(QueueAction.Remove)
        Pocket.ops.value = setOf("queue.update")
        Pocket.status.value = mapOf("queue-host" to Dot.Offline)
        refused(QueueAction.Remove)
    }

    @Test fun queuedMutationErrorsKeepRowsAndExposeFailuresExceptConvergedSteer() = runBlocking {
        enableQueueActions()
        push("sub-A", """{"type":"event","event":{"seq":1,"time":1,"type":"turn/start","data":{"turn":1}}}""")
        queueProjection(2)
        val before = Pocket.chat.value
        for (action in listOf(QueueAction.Edit("draft"), QueueAction.Remove, QueueAction.Steer)) {
            val codes = listOf("session/writer-held", "session/queue-item-not-found", "session/steer-unavailable")
            for (code in codes) {
                val failure = async(start = CoroutineStart.UNDISPATCHED) { Pocket.errors.first() }
                var completed: Boolean? = null
                val updating = Pocket.updateQueuedPrompt("A", "queued", action) { completed = it }.also { jobs += it }
                reject(request("queue.update"), "queue rejected", code)
                finish(updating)
                val converged = action == QueueAction.Steer && code != "session/writer-held"
                assertEquals(converged, completed)
                if (converged) {
                    assertFalse(failure.isCompleted)
                    failure.cancelAndJoin()
                } else {
                    withTimeout(5000) { failure.await() }
                }
                assertEquals(before, Pocket.chat.value)
            }
        }
    }

    @Test fun queuedSteerMovesToTranscriptOnlyWhenHostProjectsIt() = runBlocking {
        enableQueueActions()
        push("sub-A", """{"type":"event","event":{"seq":1,"time":1,"type":"turn/start","data":{"turn":1}}}""")
        queueProjection(2)
        val steering = Pocket.updateQueuedPrompt("A", "queued", QueueAction.Steer).also { jobs += it }
        val steer = request("queue.update")
        assertEquals("steer", steer["args"]["action"]["kind"].str())
        reply(steer, "{}")
        finish(steering)
        assertEquals(1, Pocket.chat.value!!.queuedPrompts.size)
        assertTrue(Pocket.chat.value!!.items.none { it is Item.User })
        queueProjection(3, row = "", steer = queueRow())
        assertTrue(Pocket.chat.value!!.queuedPrompts.isEmpty())
        assertEquals("original", Pocket.chat.value!!.items.filterIsInstance<Item.User>().single().text)
    }

    @Test fun lateQueueMutationSuccessAndFailureCannotAffectNewSession() = runBlocking {
        enableQueueActions()
        queueProjection(1)
        val completions = mutableListOf<Boolean>()
        val editing = Pocket.updateQueuedPrompt("A", "queued", QueueAction.Edit("old draft")) { completions += it }.also { jobs += it }
        val edit = request("queue.update")
        val removing = Pocket.updateQueuedPrompt("A", "queued", QueueAction.Remove) { completions += it }.also { jobs += it }
        val remove = request("queue.update")
        val opening = open("B")
        reply(request("unfollow"), """{"stopped":true}""")
        val follow = request("follow")
        snapshot("sub-B", 10)
        reply(follow, """{"subId":"sub-B"}""")
        finish(opening)
        queueProjection(11, queueRow("new session"), sub = "sub-B", session = "B")
        val before = Pocket.chat.value
        val failure = async(start = CoroutineStart.UNDISPATCHED) { Pocket.errors.first() }
        reply(edit, "{}")
        reject(remove)
        finish(editing)
        finish(removing)
        assertEquals(before, Pocket.chat.value)
        assertTrue(completions.isEmpty())
        assertFalse(failure.isCompleted)
        failure.cancelAndJoin()
    }

    @Test fun busyQueueTextImagesAndFilesShareRequestIdentityAndFailureCleanup() = runBlocking {
        installHostSettingsFixture()
        push("sub-A", """{"type":"event","event":{"seq":1,"time":1,"type":"turn/start","data":{"turn":1}}}""")
        val image = PromptImage("image/png", "aW1hZ2U=")
        val senders: List<() -> Job> = listOf(
            { Pocket.send("same") },
            { Pocket.sendImages("", listOf(image)) },
            { Pocket.sendWithFiles("same", listOf(image), listOf(PromptFile("receipt-1"))) },
        )
        senders.forEachIndexed { index, send ->
            val sending = send().also { jobs += it }
            val prompt = request("prompt")
            assertEquals("queue", prompt["args"]["mode"].str())
            val pending = Pocket.chat.value!!.queuedPrompts.single()
            assertEquals(prompt["args"]["requestId"].str(), pending.id)
            assertTrue(pending.sending)
            assertEquals(if (index == 0) 0 else 1, pending.images)
            assertEquals(if (index == 2) 1 else 0, pending.files)
            assertTrue(Pocket.chat.value!!.items.none { it is Item.User })
            reject(prompt, "send rejected")
            finish(sending)
            assertTrue(Pocket.chat.value!!.queuedPrompts.isEmpty())
            assertTrue(Pocket.chat.value!!.items.none { it is Item.User })
        }
    }

    @Test fun hostSteerPreferenceKeepsBusyPromptInTranscriptAndCleansFailedEcho() = runBlocking {
        val settings = installHostSettingsFixture()
        settings.desktopSet(SharedSetting.BusySend, JsonPrimitive("steer"))
        push("sub-A", """{"type":"event","event":{"seq":1,"time":1,"type":"turn/start","data":{"turn":1}}}""")
        val sending = Pocket.sendWithFiles("current turn", emptyList(), listOf(PromptFile("receipt"))).also { jobs += it }
        val prompt = request("prompt")
        assertEquals("steer", prompt["args"]["mode"].str())
        assertTrue(Pocket.chat.value!!.queuedPrompts.isEmpty())
        assertEquals("current turn", Pocket.chat.value!!.items.filterIsInstance<Item.User>().single().text)
        reject(prompt)
        finish(sending)
        assertTrue(Pocket.chat.value!!.items.none { it is Item.User })
    }

    @Test fun delayedPromptFailureCannotDiscardAnotherSessionsEcho() = runBlocking {
        installHostSettingsFixture()
        push("sub-A", """{"type":"event","event":{"seq":1,"time":1,"type":"turn/start","data":{"turn":1}}}""")
        val sending = Pocket.send("same text").also { jobs += it }
        val oldPrompt = request("prompt")
        val opening = open("B")
        reply(request("unfollow"), """{"stopped":true}""")
        val follow = request("follow")
        snapshot("sub-B")
        reply(follow, """{"subId":"sub-B"}""")
        finish(opening)
        val currentSending = Pocket.send("same text").also { jobs += it }
        val currentPrompt = request("prompt")
        assertEquals("queue", currentPrompt["args"]["mode"].str())
        assertTrue(Pocket.chat.value!!.queuedPrompts.isEmpty())
        val before = Pocket.chat.value
        reject(oldPrompt)
        finish(sending)
        assertEquals(before, Pocket.chat.value)
        assertEquals("same text", Pocket.chat.value!!.items.filterIsInstance<Item.User>().single().text)
        reply(currentPrompt, "{}")
        finish(currentSending)
    }

    @Test fun newSessionsFirstPromptSurvivesCreateFollowAndSnapshotsWithOneIdentity() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        Pocket.ops.value = setOf("permissions.get", "feedback.get")
        val creating = Pocket.newSession("first prompt", null, null, null).also { jobs += it }
        val create = request("sessions.create")
        val first = Pocket.firstPrompt.value!!
        assertEquals("first prompt", first.text)
        assertNull(first.error)
        assertNull(Pocket.chat.value)
        reply(create, """{"sessionId":"new-session"}""")
        val follow = request("follow")
        val echo = Pocket.chat.value!!.items.filterIsInstance<Item.User>().single()
        assertEquals(first.requestId, echo.requestId)
        assertEquals("local-${first.requestId}", echo.key)
        assertFalse(Pocket.chat.value!!.loaded)
        snapshot("sub-new", 0)
        assertEquals(echo, Pocket.chat.value!!.items.single())
        reply(follow, """{"subId":"sub-new"}""")
        // Neither optional getter may sit between following a new session and its prompt.
        val prompt = request("prompt")
        assertTrue(Pocket.chat.value!!.loaded)
        assertEquals(echo, Pocket.chat.value!!.items.single())
        assertEquals(first.requestId, prompt["args"]["requestId"].str())
        assertEquals("new-session", prompt["args"]["sessionId"].str())
        assertEquals("queue", prompt["args"]["mode"].str())
        assertTrue(Pocket.chat.value!!.queuedPrompts.isEmpty())
        reply(prompt, "{}")
        finish(creating)
        assertNull(Pocket.firstPrompt.value)
        assertEquals(echo, Pocket.chat.value!!.items.single())
        snapshot("sub-new", 0)
        assertEquals(echo, Pocket.chat.value!!.items.single())
        push("sub-new", """{"type":"event","event":{"seq":1,"time":1,"type":"user/message","data":{"id":"host-first","role":"user","source":{"kind":"user","rpcId":"${first.requestId}"},"content":[{"type":"text","text":"first prompt"}]}}}""")
        val confirmed = Pocket.chat.value!!.items.filterIsInstance<Item.User>().single()
        assertEquals("first prompt", confirmed.text)
        assertEquals(echo.key, confirmed.key)
        assertFalse(confirmed.pending)
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun repeatedNewSessionAndOrdinarySendCannotOvertakeTheFirstPrompt() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val creating = Pocket.newSession("first", null, null, null).also { jobs += it }
        val create = request("sessions.create")
        val first = Pocket.firstPrompt.value
        finish(Pocket.newSession("second", null, null, null).also { jobs += it })
        assertEquals(first, Pocket.firstPrompt.value)
        assertTrue(requests.tryReceive().isFailure)
        reply(create, """{"sessionId":"new-session"}""")
        val follow = request("follow")
        finish(Pocket.send("second").also { jobs += it })
        finish(Pocket.newSession("third", null, null, null).also { jobs += it })
        assertTrue(requests.tryReceive().isFailure)
        reply(follow, """{"subId":"sub-new"}""")
        reply(request("prompt"), "{}")
        finish(creating)
        val sending = Pocket.send("second").also { jobs += it }
        reply(request("prompt"), "{}")
        finish(sending)
        assertEquals(listOf("first", "second"), Pocket.chat.value!!.items.filterIsInstance<Item.User>().map { it.text })
    }

    @Test fun createFailureRetainsTextUntilDismissAndAllowsANewAttempt() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val creating = Pocket.newSession("retained draft", null, null, null).also { jobs += it }
        val create = request("sessions.create")
        val requestId = Pocket.firstPrompt.value!!.requestId
        reject(create, "create rejected")
        finish(creating)
        assertEquals(requestId, Pocket.firstPrompt.value?.requestId)
        assertEquals("retained draft", Pocket.firstPrompt.value?.text)
        assertTrue(Pocket.firstPrompt.value!!.error!!.contains("create rejected"))
        assertNull(Pocket.chat.value)
        finish(Pocket.dismissFailedFirstPrompt().also { jobs += it })
        assertNull(Pocket.firstPrompt.value)
        val retry = Pocket.newSession("edited draft", null, null, null).also { jobs += it }
        val nextCreate = request("sessions.create")
        assertTrue(Pocket.firstPrompt.value!!.requestId != requestId)
        reject(nextCreate)
        finish(retry)
    }

    @Test fun modelConfigurationFailureRetainsEchoAndDismissReturnsToNewSession() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val creating = Pocket.newSession("model draft", null, null, ModelChoice("p", "next", "Next", null)).also { jobs += it }
        reply(request("sessions.create"), """{"sessionId":"new-session"}""")
        val selection = request("models.select")
        val first = Pocket.firstPrompt.value!!
        assertEquals(first.requestId, Pocket.chat.value!!.items.filterIsInstance<Item.User>().single().requestId)
        reject(selection, "model rejected")
        finish(creating)
        assertEquals(first.text, Pocket.firstPrompt.value?.text)
        assertTrue(Pocket.firstPrompt.value!!.error!!.contains("model rejected"))
        assertTrue(requests.tryReceive().isFailure)
        finish(Pocket.dismissFailedFirstPrompt().also { jobs += it })
        assertNull(Pocket.firstPrompt.value)
        assertNull(Pocket.chat.value)
        assertTrue((get("reducer") as TranscriptReducer).items().isEmpty())
    }

    @Test fun promptFailureRetainsEchoUntilDismissUnsubscribesAndRemovesIt() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val creating = Pocket.newSession("prompt draft", null, null, null).also { jobs += it }
        reply(request("sessions.create"), """{"sessionId":"new-session"}""")
        reply(request("follow"), """{"subId":"sub-new"}""")
        val prompt = request("prompt")
        val first = Pocket.firstPrompt.value!!
        reject(prompt, "prompt rejected")
        finish(creating)
        assertEquals(first.text, Pocket.firstPrompt.value?.text)
        assertTrue(Pocket.firstPrompt.value!!.error!!.contains("prompt rejected"))
        assertEquals(first.requestId, Pocket.chat.value!!.items.filterIsInstance<Item.User>().single().requestId)
        val dismissing = Pocket.dismissFailedFirstPrompt().also { jobs += it }
        val unsubscribe = request("unfollow")
        assertEquals("sub-new", unsubscribe["args"]["subId"].str())
        assertNull(Pocket.firstPrompt.value)
        assertNull(Pocket.chat.value)
        assertTrue((get("reducer") as TranscriptReducer).items().isEmpty())
        reply(unsubscribe, """{"stopped":true}""")
        finish(dismissing)
    }

    @Test fun changingSessionDuringCreateCannotRestoreOrSendTheOldSubmission() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val creating = Pocket.newSession("obsolete", null, null, null).also { jobs += it }
        val create = request("sessions.create")
        val opening = open("B")
        val follow = request("follow")
        assertNull(Pocket.firstPrompt.value)
        reply(follow, """{"subId":"sub-B"}""")
        finish(opening)
        reply(create, """{"sessionId":"obsolete-session"}""")
        finish(creating)
        assertEquals("B", Pocket.chat.value?.sessionId)
        assertTrue(Pocket.chat.value!!.items.isEmpty())
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun closeDuringCreateInvalidatesItEvenWhenAnotherFirstPromptStarts() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val creating = Pocket.newSession("obsolete", null, null, null).also { jobs += it }
        val oldCreate = request("sessions.create")
        finish(Pocket.closeChat().also { jobs += it })
        assertNull(Pocket.firstPrompt.value)
        val next = Pocket.newSession("current", null, null, null).also { jobs += it }
        val nextCreate = request("sessions.create")
        val first = Pocket.firstPrompt.value
        reply(oldCreate, """{"sessionId":"obsolete-session"}""")
        finish(creating)
        assertEquals(first, Pocket.firstPrompt.value)
        assertNull(Pocket.chat.value)
        assertTrue(requests.tryReceive().isFailure)
        reject(nextCreate)
        finish(next)
    }

    @Test fun changingSessionDuringModelConfigurationCannotSendOrApplyOldSelection() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val creating = Pocket.newSession("obsolete", null, null, ModelChoice("p", "next", "Next", null)).also { jobs += it }
        reply(request("sessions.create"), """{"sessionId":"obsolete-session"}""")
        val selection = request("models.select")
        val opening = open("B")
        reply(request("follow"), """{"subId":"sub-B"}""")
        finish(opening)
        reply(selection, """{"selected":{"provider":"p","model":"next"}}""")
        finish(creating)
        assertNull(Pocket.model.value["obsolete-session"])
        assertNull(Pocket.firstPrompt.value)
        assertEquals("B", Pocket.chat.value?.sessionId)
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun closeDuringFirstFollowUnsubscribesItsLateReceiptWithoutPrompting() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val creating = Pocket.newSession("obsolete", null, null, null).also { jobs += it }
        reply(request("sessions.create"), """{"sessionId":"obsolete-session"}""")
        val follow = request("follow")
        finish(Pocket.closeChat().also { jobs += it })
        reply(follow, """{"subId":"sub-obsolete"}""")
        val unsubscribe = request("unfollow")
        assertEquals("sub-obsolete", unsubscribe["args"]["subId"].str())
        reply(unsubscribe, """{"stopped":true}""")
        finish(creating)
        assertNull(Pocket.firstPrompt.value)
        assertNull(Pocket.chat.value)
        assertTrue(requests.tryReceive().isFailure)
    }

    private fun enableFirstPermission() {
        Pocket.permissionCatalog.value = PermissionCatalog.parse(PocketJson.parseToJsonElement(
            """{"options":[{"value":"team","name":"Team"}],"defaultOptions":[{"value":"team","name":"Team"}],"defaultPreset":"team"}"""))
        Pocket.permissionCatalogLoading.value = false
        Pocket.ops.value = setOf("permissions.set", "permissions.get", "feedback.get")
    }

    @Test fun firstPromptWaitsForBothConfigurationWritesButNotOptionalReads() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        enableFirstPermission()
        val creating = Pocket.newSession("configured", null, "team", ModelChoice("p", "next", "Next", null)).also { jobs += it }
        reply(request("sessions.create"), """{"sessionId":"new-session"}""")
        val selection = request("models.select")
        assertTrue(requests.tryReceive().isFailure)
        assertEquals("configured", Pocket.chat.value!!.items.filterIsInstance<Item.User>().single().text)
        reply(selection, """{"selected":{"provider":"p","model":"next"}}""")
        val permission = request("permissions.set")
        assertEquals("new-session", permission["args"]["sessionId"].str())
        assertEquals("team", permission["args"]["name"].str())
        assertTrue(requests.tryReceive().isFailure)
        assertEquals("next", Pocket.model.value["new-session"]?.model)
        reply(permission, """{"current":"team"}""")
        reply(request("follow"), """{"subId":"sub-new"}""")
        val prompt = request("prompt")
        assertEquals("team", Pocket.permission.value["new-session"])
        assertEquals(Pocket.firstPrompt.value!!.requestId, prompt["args"]["requestId"].str())
        reply(prompt, "{}")
        finish(creating)
        assertNull(Pocket.firstPrompt.value)
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun permissionConfigurationFailureRetainsDraftAndCannotSendBeforeDismiss() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        enableFirstPermission()
        val creating = Pocket.newSession("permission draft", null, "team", null).also { jobs += it }
        reply(request("sessions.create"), """{"sessionId":"new-session"}""")
        reject(request("permissions.set"), "permission rejected")
        finish(creating)
        assertEquals("permission draft", Pocket.firstPrompt.value?.text)
        assertTrue(Pocket.firstPrompt.value!!.error!!.contains("permission rejected"))
        assertNull(Pocket.permission.value["new-session"])
        finish(Pocket.send("cannot bypass failed permission").also { jobs += it })
        assertTrue(requests.tryReceive().isFailure)
        finish(Pocket.dismissFailedFirstPrompt().also { jobs += it })
        assertNull(Pocket.chat.value)
        assertNull(Pocket.firstPrompt.value)
        assertTrue((get("reducer") as TranscriptReducer).items().isEmpty())
    }

    @Test fun sidebarCreationCannotOvertakeAFirstPromptStartedWhileItsCreateWasPending() = runBlocking {
        Pocket.chat.value = null
        put("subId", null)
        val preparing = async { Pocket.prepareSidebarSession() }.also { jobs += it }
        val sidebarCreate = request("sessions.create")
        val creating = Pocket.newSession("first", null, null, null).also { jobs += it }
        val create = request("sessions.create")
        val first = Pocket.firstPrompt.value
        reply(sidebarCreate, """{"sessionId":"obsolete-sidebar"}""")
        assertNull(withTimeout(5000) { preparing.await() })
        assertEquals(first, Pocket.firstPrompt.value)
        assertNull(Pocket.chat.value)
        assertTrue(requests.tryReceive().isFailure)
        reject(create)
        finish(creating)
    }

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

    private suspend fun onActor(action: () -> Unit) =
        withContext((get("main") as CoroutineScope).coroutineContext) { action() }

    private fun streamBaseline(sub: String = "sub-A") = push(sub,
        """{"type":"snapshot","cursor":1,"records":[{"type":"event","event":{"seq":1,"time":100,"type":"turn/start","data":{"turn":1}}}],"projections":{},"assistantStream":{"revision":0}}""")

    private fun streamStart(sub: String = "sub-A") = push(sub,
        """{"type":"assistant-stream","frame":{"type":"start","attemptId":"attempt","turn":1,"step":1,"startedAfterSeq":1,"revision":1}}""")

    @Test fun completionPublishesPendingTextAndLateStartCannotReopenTheTurn() = runBlocking {
        onActor {
            streamBaseline()
            streamStart()
            repeat(100) { index ->
                push("sub-A", """{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"attempt","revision":${index + 2},"index":$index,"time":150,"chunk":{"type":"text-delta","index":0,"text":"x"}}}""")
            }
            val answer = "x".repeat(100)
            push("sub-A", """{"type":"event","event":{"seq":2,"time":200,"type":"assistant/message","data":{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"$answer"}]}}}}""")
            push("sub-A", """{"type":"assistant-stream","frame":{"type":"end","attemptId":"attempt","revision":102,"index":100,"outcome":{"kind":"committed","seq":2,"eventType":"assistant/message"}}}""")
            push("sub-A", """{"type":"event","event":{"seq":3,"time":250,"type":"turn/end","data":{"turn":1}}}""")
            assertEquals(answer, Pocket.chat.value!!.items.filterIsInstance<Item.Text>().single().text)
            assertFalse(Pocket.chat.value!!.running)
            streamStart()
            assertFalse(Pocket.chat.value!!.running)
            assertTrue(Pocket.chat.value!!.items.filterIsInstance<Item.Text>().none { it.live })
        }
        val completed = Pocket.chat.value
        delay(100)
        assertEquals(completed, Pocket.chat.value)
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun streamGapDetachesOldFramesAndRecoversFromFreshBaseline() = runBlocking {
        onActor {
            streamBaseline()
            streamStart()
            push("sub-A", """{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"attempt","revision":3,"index":0,"time":150,"chunk":{"type":"text-delta","index":0,"text":"incomplete"}}}""")
            assertNull(get("subId"))
        }
        val unfollow = request("unfollow")
        assertEquals("sub-A", unfollow["args"]["subId"].str())
        onActor {
            push("sub-A", """{"type":"event","event":{"seq":999,"type":"turn/start","data":{"turn":99}}}""")
        }
        reply(unfollow, """{"stopped":true}""")
        val follow = request("follow")
        assertNull(follow["args"]["afterSeq"].long())
        reply(follow, """{"subId":"recovered"}""")
        withTimeout(5000) { while (get("subId") != "recovered") delay(1) }
        onActor {
            push("recovered", """{"type":"snapshot","cursor":3,"records":[{"type":"event","event":{"seq":2,"type":"assistant/message","data":{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"recovered answer"}]}}}},{"type":"event","event":{"seq":3,"type":"turn/end","data":{"turn":1}}}],"projections":{},"assistantStream":{"revision":5}}""")
            assertFalse((get("reducer") as TranscriptReducer).needsResync)
            assertFalse(Pocket.chat.value!!.running)
            assertEquals("recovered answer", Pocket.chat.value!!.items.filterIsInstance<Item.Text>().single().text)
            assertTrue(Pocket.chat.value!!.turns.none { it.turn == 99 })
        }
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun activeBaselineRestoresRunningEvenWhenMergedMetadataRemembersTheOldStart() = runBlocking {
        onActor {
            streamBaseline()
            Pocket.sessions.value = listOf(SessionSummary("A", "Active", "/repo", true, false, 1, null))
            push("sub-A", """{"type":"snapshot","cursor":2,"records":[{"type":"event","event":{"seq":2,"type":"step/start","data":{"turn":1,"step":1}}}],"projections":{},"assistantStream":{"revision":2,"activeAttempt":{"attemptId":"attempt","turn":1,"step":1,"startedAfterSeq":2,"nextIndex":1,"stream":[{"type":"text-chunks","index":0,"time0":150,"texts":["prefix"],"dt":[]}]}}}""")
            assertTrue(Pocket.chat.value!!.running)
            assertEquals(100L, Pocket.chat.value!!.runStartedAt)
            assertEquals("prefix", Pocket.chat.value!!.items.filterIsInstance<Item.Text>().single().text)
            push("sub-A", """{"type":"event","event":{"seq":3,"time":250,"type":"turn/end","data":{"turn":1}}}""")
            assertFalse(Pocket.chat.value!!.running)
        }
    }

    @Test fun followFailureKeepsAlreadyReceivedTextInsteadOfCancellingItsLastPaint() = runBlocking {
        onActor {
            streamBaseline()
            streamStart()
            push("sub-A", """{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"attempt","revision":2,"index":0,"time":150,"chunk":{"type":"text-delta","index":0,"text":"last received text"}}}""")
            val failure = jsonOf("ev" to "follow.error", "subId" to "sub-A",
                "error" to jsonOf("code" to "frame/too-large", "message" to "stream failed"))
            Pocket.javaClass.getDeclaredMethod("onPush", JsonObject::class.java).apply { isAccessible = true }.invoke(Pocket, failure)
            assertNull(get("subId"))
            assertEquals("last received text", Pocket.chat.value!!.items.filterIsInstance<Item.Text>().single().text)
            assertTrue(Pocket.chat.value!!.running) // Failure is not a fabricated turn completion.
        }
        val shown = Pocket.chat.value
        delay(100)
        assertEquals(shown, Pocket.chat.value)
    }
}
