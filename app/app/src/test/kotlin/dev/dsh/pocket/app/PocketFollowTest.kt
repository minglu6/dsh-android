package dev.dsh.pocket.app

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
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Exercises the real Pocket actor while Host unsubscribe/follow replies are deliberately delayed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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
        val fields = listOf("client", "subId", "pendingFollowEpoch", "reducer", "permissionProjectionSeq").associateWith(::get)
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
        put("permissionProjectionSeq", -1L)
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
}
