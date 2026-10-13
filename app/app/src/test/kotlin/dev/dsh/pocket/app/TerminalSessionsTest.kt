package dev.dsh.pocket.app

import android.content.Context
import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalSessionsTest {
    private val host = "terminal-test-${UUID.randomUUID()}"
    private lateinit var client: PocketClient
    private val requests = Channel<JsonObject>(Channel.UNLIMITED)
    private val jobs = mutableListOf<Job>()
    private lateinit var restore: () -> Unit

    private fun field(name: String) = Pocket.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun put(name: String, value: Any?) = field(name).set(Pocket, value)

    @Before fun setUp() {
        Pocket.init(RuntimeEnvironment.getApplication())
        // Robolectric replaces Application/SharedPreferences between methods, while Pocket's
        // singleton can survive. Bind this fixture to the current application's real storage.
        val oldPrefs = Pocket.prefs
        put("prefs", AppPrefs(RuntimeEnvironment.getApplication()))
        val oldClient = field("client").get(Pocket)
        val oldActive = Pocket.active.value
        val oldOps = Pocket.ops.value
        val oldMeta = Pocket.terminalMeta.value
        val oldSub = field("terminalSubId").get(Pocket)
        restore = {
            put("prefs", oldPrefs)
            put("client", oldClient)
            put("terminalSubId", oldSub)
            Pocket.active.value = oldActive
            Pocket.ops.value = oldOps
            Pocket.terminalMeta.value = oldMeta
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
        put("terminalSubId", null)
        Pocket.active.value = host
        Pocket.terminalMeta.value = null
        Pocket.ops.value = setOf("terminal.list", "terminal.create", "terminal.follow", "terminal.close")
    }

    @After fun tearDown(): Unit = runBlocking {
        // No test leaves an outstanding follow RPC; detach is fire-and-forget on its captured client.
        Pocket.closeTerminalFollow()
        disconnect()
        jobs.forEach { it.cancelAndJoin() }
        restore()
        val storage = RuntimeEnvironment.getApplication().getSharedPreferences("app", Context.MODE_PRIVATE)
        val editor = storage.edit()
        storage.all.keys.filter { host in it }.forEach(editor::remove)
        editor.commit()
        requests.close()
    }

    private suspend fun request(op: String): JsonObject = withTimeout(5000) {
        requests.receive().also { assertEquals(op, it["op"].str()) }
    }

    private fun reply(request: JsonObject, result: String) {
        val text = "{\"id\":${request["id"]},\"ok\":true,\"result\":$result}"
        client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }.invoke(client, text)
    }

    private fun disconnect() {
        // This lightweight transport has no reader pump. Deliver the same failure that the
        // real transport pump's finally block reports, rather than waiting for an RPC timeout.
        client.javaClass.getDeclaredMethod("fail", String::class.java).apply { isAccessible = true }
            .invoke(client, "test transport disconnected")
        client.close()
    }

    private fun push(sub: String, id: String) {
        val frame = PocketJson.parseToJsonElement("""{"ev":"terminal","subId":"$sub","frame":{"type":"snapshot","screen":"$id","info":{"id":"$id","state":"running","cwd":"/workspace/$id"}}}""").jsonObject
        Pocket.javaClass.getDeclaredMethod("onPush", JsonObject::class.java).apply { isAccessible = true }.invoke(Pocket, frame)
    }

    private fun open(owner: String, id: String? = null) = Pocket.openTerminal(owner, terminalId = id).also { jobs += it }
    private suspend fun finish(job: Job) = withTimeout(5000) { job.join() }

    private suspend fun attach(owner: String, id: String): Job {
        val opening = open(owner, id)
        reply(request("terminal.list"), """{"terminals":[{"id":"$id","state":"running"}],"platform":"darwin"}""")
        val follow = request("terminal.follow")
        // The bridge is allowed to push the snapshot before the RPC receipt.
        push("sub-$id", id)
        reply(follow, """{"subId":"sub-$id","attachmentId":"att-$id"}""")
        reply(request("terminal.resize"), """{"accepted":true}""")
        finish(opening)
        return opening
    }

    @Test fun preferencesPersistOwnersAndSelectionsWithoutCrossHostLeakage() {
        val prefs = Pocket.prefs
        prefs.rememberTerminal(host, "same-owner", "first")
        prefs.rememberTerminal(host, "same-owner", "selected")
        prefs.rememberTerminal("$host-other", "same-owner", "other-host")
        prefs.discoverTerminal(host, "prior-conversation", "created-but-not-attached")
        prefs.setTerminalPanelOwner(host, "current-chat", "prior-conversation")
        val restarted = AppPrefs(RuntimeEnvironment.getApplication())
        assertEquals(setOf("same-owner", "prior-conversation"), restarted.terminalOwnerIds(host).toSet())
        assertEquals("selected", restarted.selectedTerminalId(host, "same-owner"))
        assertEquals("other-host", restarted.selectedTerminalId("$host-other", "same-owner"))
        assertNull(restarted.selectedTerminalId(host, "prior-conversation"))
        assertEquals("prior-conversation", restarted.terminalPanelOwner(host, "current-chat"))
        restarted.reconcileTerminals(host, "same-owner", listOf("first"))
        assertNull(restarted.selectedTerminalId(host, "same-owner"))
        assertEquals("other-host", restarted.selectedTerminalId("$host-other", "same-owner"))
        restarted.reconcileTerminals(host, "prior-conversation", emptyList())
        assertNull(restarted.terminalPanelOwner(host, "current-chat"))
        assertEquals(listOf("same-owner"), restarted.terminalOwnerIds(host))
    }

    @Test fun savedExitedSelectionWinsOverFirstRunningAndMissingExplicitNeverCreates() {
        val first = TerminalSessionEntry("owner", "first", state = "running")
        val selected = TerminalSessionEntry("owner", "selected", state = "exited")
        assertEquals(selected, selectTerminal(listOf(first, selected), "selected", null))
        assertEquals(first, selectTerminal(listOf(first, selected), "selected", "first"))
        assertEquals(selected, selectTerminal(listOf(selected), null, null))
        assertNull(selectTerminal(emptyList(), null, null))
        assertFailsWith<PocketError> { selectTerminal(listOf(first), "gone", null) }
        assertFailsWith<PocketError> { selectTerminal(listOf(first), "first", "gone") }
    }

    @Test fun missingExplicitIdentityMakesNoCreateOrFollowRequest() = runBlocking {
        val opening = open("owner", "gone")
        reply(request("terminal.list"), """{"terminals":[{"id":"other","state":"running"}]}""")
        finish(opening)
        assertNull(Pocket.terminalMeta.value)
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun detachAndReopenResumesSavedSelectionWithoutWritingCommands() = runBlocking {
        attach("owner", "chosen")
        assertEquals("chosen", Pocket.prefs.selectedTerminalId(host, "owner"))
        val detach = Pocket.closeTerminalFollow()
        reply(request("unfollow"), "{}")
        finish(detach)
        assertEquals("chosen", Pocket.terminal.plainText(), "detached output remains available to copy")
        val opening = open("owner")
        reply(request("terminal.list"), """{"terminals":[{"id":"first","state":"running"},{"id":"chosen","state":"running"}]}""")
        val follow = request("terminal.follow")
        assertEquals("chosen", follow["args"]["id"].str())
        push("resumed", "chosen")
        reply(follow, """{"subId":"resumed","attachmentId":"new-attachment"}""")
        reply(request("terminal.resize"), """{"accepted":true}""")
        finish(opening)
        assertEquals("chosen", Pocket.terminalMeta.value?.id)
        assertNull(Pocket.terminalPlatform.value) // Older bridges must stay unknown, not infer an OS.
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun lateUnfollowAndTerminateCannotEraseNewAttachment() = runBlocking {
        attach("owner", "old")
        val termination = async { Pocket.terminateTerminal("owner", "old") }.also { jobs += it }
        val close = request("terminal.close")
        val detach = Pocket.closeTerminalFollow()
        val unfollow = request("unfollow")
        attach("owner", "new")
        reply(unfollow, "{}")
        finish(detach)
        reply(close, """{"closed":true}""")
        termination.await()
        assertEquals("new", Pocket.terminalMeta.value?.id)
        assertEquals("new", Pocket.prefs.selectedTerminalId(host, "owner"))
        assertEquals("new", Pocket.terminal.plainText())
    }

    @Test fun responseFromPreviousHostCannotAttachOrPruneSavedIdentity() = runBlocking {
        Pocket.prefs.rememberTerminal(host, "owner", "retained")
        val opening = open("owner")
        val listing = request("terminal.list")
        Pocket.active.value = "$host-other"
        reply(listing, """{"terminals":[],"platform":"win32"}""")
        finish(opening)
        assertNull(Pocket.terminalMeta.value)
        assertEquals("retained", Pocket.prefs.selectedTerminalId(host, "owner"))
        assertNull(Pocket.prefs.selectedTerminalId("$host-other", "owner"))
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun cancelledCreateStaysDiscoverableButIsNotSavedAsSuccessfulAttachment() = runBlocking {
        val opening = Pocket.openTerminal("owner", forceNew = true).also { jobs += it }
        val create = request("terminal.create")
        val id = assertNotNull(create["args"]["id"].str())
        opening.cancel()
        reply(create, """{"terminal":{"id":"$id","state":"running"},"platform":"linux"}""")
        finish(opening)
        assertEquals(listOf("owner"), Pocket.prefs.terminalOwnerIds(host))
        assertNull(Pocket.prefs.selectedTerminalId(host, "owner"))
        assertNull(Pocket.terminalMeta.value)
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun catalogueRecoversPriorOwnersAndFailedTransportDoesNotPruneThem() = runBlocking {
        Pocket.prefs.rememberTerminal(host, "prior", "retained")
        Pocket.prefs.rememberTerminal("$host-other", "foreign", "must-not-query")
        val listing = async { Pocket.terminalSessions("current") }.also { jobs += it }
        reply(request("terminal.list"), """{"terminals":[],"platform":"linux"}""")
        val prior = request("terminal.list")
        assertEquals("prior", prior["args"]["sessionId"].str())
        reply(prior, """{"terminals":[{"id":"retained","title":"Shell","cwd":"/project","state":"running"}],"platform":"linux"}""")
        val catalog = listing.await()
        assertEquals(listOf(TerminalSessionEntry("prior", "retained", "Shell", "running", "/project")), catalog.entries)
        assertEquals("linux", catalog.platform)
        val failure = async { runCatching { Pocket.terminalSessions("prior") } }.also { jobs += it }
        request("terminal.list")
        disconnect()
        assertTrue(withTimeout(5000) { failure.await() }.isFailure)
        assertEquals("retained", AppPrefs(RuntimeEnvironment.getApplication()).selectedTerminalId(host, "prior"))
    }
}
