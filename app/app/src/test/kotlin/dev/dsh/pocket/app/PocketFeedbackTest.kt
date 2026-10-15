package dev.dsh.pocket.app

import dev.dsh.pocket.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PocketFeedbackTest {
    private lateinit var client: PocketClient
    private lateinit var restore: () -> Unit
    private val requests = Channel<JsonObject>(Channel.UNLIMITED)
    private val jobs = mutableListOf<Job>()
    private fun field(name: String) = Pocket.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun reset() = Pocket.javaClass.getDeclaredMethod("resetFeedback", Boolean::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(Pocket, true)

    @Suppress("UNCHECKED_CAST")
    @Before fun setup() {
        Pocket.init(RuntimeEnvironment.getApplication())
        installHostSettingsFixture()
        val oldFields = listOf("client", "feedbackLoaded", "feedbackGeneration").associateWith { field(it).get(Pocket) }
        val oldFlows = listOf("chat", "active", "status", "ops", "feedback", "feedbackStatus", "feedbackPending", "feedbackFailures")
            .associateWith { (field(it).get(Pocket) as MutableStateFlow<Any?>).value }
        restore = {
            oldFields.forEach { (name, value) -> field(name).set(Pocket, value) }
            oldFlows.forEach { (name, value) -> (field(name).get(Pocket) as MutableStateFlow<Any?>).value = value }
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
        field("client").set(Pocket, client)
        Pocket.active.value = "host"
        Pocket.status.value = mapOf("host" to Dot.Online)
        Pocket.chat.value = ChatState("session")
        Pocket.ops.value = setOf("feedback.get", "feedback.set", "sessionFeedback.record")
        reset()
    }

    @After fun cleanup() = runBlocking {
        jobs.forEach { it.cancelAndJoin() }
        client.close()
        requests.close()
        restore()
    }

    private suspend fun request(op: String): JsonObject = withTimeout(5000) {
        requests.receive().also { assertEquals(op, it["op"].str()) }
    }
    private fun reply(request: JsonObject, result: JsonElement) = frame(jsonOf("id" to request["id"], "ok" to true, "result" to result))
    private fun fail(request: JsonObject, code: String, details: JsonElement? = null) =
        frame(jsonOf("id" to request["id"], "ok" to false, "error" to jsonOf("code" to code, "message" to "internal carrier detail", "details" to details)))
    private fun frame(value: JsonObject) {
        client.javaClass.getDeclaredMethod("onFrame", String::class.java).apply { isAccessible = true }.invoke(client, value.toString())
    }
    private fun item(rating: String = "positive", version: String = "v1") = jsonOf("messageId" to "m1", "rating" to rating, "version" to version)
    private suspend fun finish(job: Job) = withTimeout(5000) { job.join() }
    private fun choose(rating: String = "positive", onDialog: () -> Unit = {}) =
        Pocket.chooseMessageFeedback("m1", rating, onDialog).also { jobs += it }
    private fun submit(rating: String = "negative", note: String = "", result: (Result<Unit>) -> Unit = {}) =
        Pocket.setMessageFeedback("m1", rating, "task-result", note, result).also { jobs += it }

    @Test fun coldMatchingClickRetractsObservedVersionAndDoubleClickDoesNotWriteTwice() = runBlocking {
        var opened = 0
        val first = choose { opened++ }
        val load = request("feedback.get")
        finish(choose { opened++ })
        assertEquals(setOf("m1"), Pocket.feedbackPending.value.keys)
        reply(load, jsonOf("items" to listOf(item())))
        val retract = request("feedback.set")
        assertEquals(JsonNull, retract["args"]["rating"])
        assertEquals("v1", retract["args"]["ifVersion"].str())
        reply(retract, jsonOf("absent" to true))
        finish(first)
        assertEquals(0, opened)
        assertTrue(Pocket.feedback.value.isEmpty())
        assertTrue(Pocket.feedbackPending.value.isEmpty())
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun changingRatingRequiresSubmitAndConflictAdoptsCurrentWithoutRetry() = runBlocking {
        var opened = 0
        val choosing = choose("negative") { opened++ }
        reply(request("feedback.get"), jsonOf("items" to listOf(item())))
        finish(choosing)
        assertEquals(1, opened)
        assertEquals("positive", Pocket.feedback.value["m1"]?.rating)
        assertTrue(requests.tryReceive().isFailure)
        var result: Result<Unit>? = null
        val saving = submit(note = "  explanation  ") { result = it }
        val write = request("feedback.set")
        assertEquals("v1", write["args"]["ifVersion"].str())
        assertEquals("explanation", write["args"]["note"].str())
        fail(write, "version-conflict", jsonOf("code" to "version-conflict", "current" to item("positive", "v2")))
        finish(saving)
        assertEquals("version-conflict", (result!!.exceptionOrNull() as PocketError).code)
        assertEquals("v2", Pocket.feedback.value["m1"]?.version)
        assertTrue(requests.tryReceive().isFailure)
        val retry = submit()
        val retried = request("feedback.set")
        assertEquals("v2", retried["args"]["ifVersion"].str())
        assertNull(retried["args"]["note"])
        reply(retried, item("negative", "v3"))
        finish(retry)
        assertEquals("negative", Pocket.feedback.value["m1"]?.rating)
    }

    @Test fun nullConflictRemovesStaleRatingAndNextExplicitWriteUsesAbsentVersion() = runBlocking {
        val saving = submit()
        reply(request("feedback.get"), jsonOf("items" to listOf(item())))
        fail(request("feedback.set"), "version-conflict", jsonOf("code" to "version-conflict", "current" to JsonNull))
        finish(saving)
        assertNull(Pocket.feedback.value["m1"])
        val retry = submit()
        val write = request("feedback.set")
        assertNull(write["args"]["ifVersion"])
        reply(write, item("negative", "v2"))
        finish(retry)
        assertEquals("v2", Pocket.feedback.value["m1"]?.version)
    }

    @Test fun failedLoadRemainsVisibleAndIsRetriedBeforeSubmission() = runBlocking {
        var opened = false
        val choosing = choose { opened = true }
        fail(request("feedback.get"), "session-not-found")
        finish(choosing)
        assertTrue(opened)
        assertEquals(FeedbackLoadStatus.Error, Pocket.feedbackStatus.value)
        val saving = submit()
        reply(request("feedback.get"), jsonOf("items" to emptyList<Any>()))
        val write = request("feedback.set")
        fail(write, "note-too-large", jsonOf("code" to "note-too-large", "maxBytes" to 10))
        finish(saving)
        assertEquals(FeedbackLoadStatus.Ready, Pocket.feedbackStatus.value)
        assertTrue(Pocket.feedback.value.isEmpty())
        assertTrue(requests.tryReceive().isFailure)
    }

    @Test fun retractConflictShowsRowErrorAndPreservesAuthoritativeOtherRating() = runBlocking {
        val choosing = choose()
        reply(request("feedback.get"), jsonOf("items" to listOf(item())))
        fail(request("feedback.set"), "version-conflict", jsonOf("code" to "version-conflict", "current" to item("negative", "v2")))
        finish(choosing)
        assertEquals("negative", Pocket.feedback.value["m1"]?.rating)
        assertEquals("version-conflict", Pocket.feedbackFailures.value["m1"])
        var opened = false
        finish(choose { opened = true })
        assertTrue(opened)
        assertNull(Pocket.feedbackFailures.value["m1"])
    }

    @Test fun oldSessionReplyCannotOpenDialogOrOverwriteNewSessionState() = runBlocking {
        var opened = false
        val choosing = choose { opened = true }
        val load = request("feedback.get")
        Pocket.chat.value = ChatState("new-session")
        reset()
        Pocket.feedback.value = mapOf("new" to MessageFeedback("new", "negative", "new-version"))
        reply(load, jsonOf("items" to listOf(item())))
        finish(choosing)
        assertFalse(opened)
        assertEquals(setOf("new"), Pocket.feedback.value.keys)
        assertEquals(FeedbackLoadStatus.Cold, Pocket.feedbackStatus.value)
        assertTrue(Pocket.feedbackPending.value.isEmpty())
    }

    @Test fun reopenedDialogSubmissionQueuesBehindPriorReceiptUsingItsCommittedVersion() = runBlocking {
        var firstDone = false
        var secondDone = false
        val first = submit("positive") { firstDone = it.isSuccess }
        reply(request("feedback.get"), jsonOf("items" to emptyList<Any>()))
        val firstWrite = request("feedback.set")
        val second = submit("negative") { secondDone = it.isSuccess }
        reply(firstWrite, item("positive", "v1"))
        val secondWrite = request("feedback.set")
        assertEquals("v1", secondWrite["args"]["ifVersion"].str())
        assertEquals("negative", secondWrite["args"]["rating"].str())
        assertTrue("m1" in Pocket.feedbackPending.value, "Second request keeps controls pending")
        reply(secondWrite, item("negative", "v2"))
        finish(first); finish(second)
        assertTrue(firstDone && secondDone)
        assertEquals("negative", Pocket.feedback.value["m1"]?.rating)
        assertTrue(Pocket.feedbackPending.value.isEmpty())
    }

    @Test fun disconnectDuringSubmitReleasesTheStillOpenFormWithoutPublishingStaleRating() = runBlocking {
        var result: Result<Unit>? = null
        val saving = submit { result = it }
        reply(request("feedback.get"), jsonOf("items" to emptyList<Any>()))
        val write = request("feedback.set")
        field("client").set(Pocket, null)
        Pocket.status.value = mapOf("host" to Dot.Offline)
        reset()
        fail(write, "transport")
        finish(saving)
        assertEquals("disconnected", (result!!.exceptionOrNull() as PocketError).code)
        assertTrue(Pocket.feedback.value.isEmpty())
        assertTrue(Pocket.feedbackPending.value.isEmpty())
    }

    @Test fun sessionFeedbackPreservesBusinessCodeAndOmitsBlankText() = runBlocking {
        var result: Result<Unit>? = null
        val saving = Pocket.recordSessionFeedback(null, "   ") { result = it }.also { jobs += it }
        val write = request("sessionFeedback.record")
        assertNull(write["args"]["text"])
        fail(write, "session-not-found", jsonOf("code" to "session-not-found"))
        finish(saving)
        assertEquals("session-not-found", (result!!.exceptionOrNull() as PocketError).code)
    }
}
