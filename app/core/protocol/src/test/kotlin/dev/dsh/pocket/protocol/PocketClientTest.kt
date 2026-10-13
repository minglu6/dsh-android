package dev.dsh.pocket.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PocketClientTest {
    private class FakeTransport : FrameTransport {
        override val route = Route.WsLocal
        override val closeReason: String? = null
        private val frames = Channel<String>(Channel.UNLIMITED)
        override val incoming = frames.receiveAsFlow()
        val requests = mutableListOf<JsonObject>()
        var onSend: suspend () -> Unit = {}

        override suspend fun send(text: String) {
            val request = PocketJson.parseToJsonElement(text).jsonObject
            requests += request
            if (request["op"].str() == "hello") {
                reply(request, jsonOf("proto" to 1))
            } else {
                onSend()
            }
        }

        fun reply(request: JsonObject, result: JsonElement) {
            frames.trySend(jsonOf("id" to request["id"], "ok" to true, "result" to result).toString()).getOrThrow()
        }

        fun reject(request: JsonObject, code: String, message: String) {
            frames.trySend(jsonOf(
                "id" to request["id"],
                "ok" to false,
                "error" to jsonOf("code" to code, "message" to message),
            ).toString()).getOrThrow()
        }

        override fun close() {
            frames.close()
        }
    }

    private suspend fun TestScope.withClient(block: suspend (PocketClient, FakeTransport) -> Unit) {
        val transport = FakeTransport()
        val client = PocketClient(backgroundScope)
        try {
            client.connect(TransportFactory { transport }, Auth.Token("test-token"))
            assertEquals(0, pendingCount(client))
            block(client, transport)
        } finally {
            client.close()
        }
    }

    // Inspect retention without adding a production diagnostic API solely for tests.
    private fun pendingCount(client: PocketClient): Int {
        val field = PocketClient::class.java.getDeclaredField("pending")
        field.isAccessible = true
        return (field.get(client) as Map<*, *>).size
    }

    private suspend fun TestScope.assertNextRequestIgnoresLateReply(
        client: PocketClient,
        transport: FakeTransport,
        previous: JsonObject,
    ) {
        transport.onSend = {}
        val next = async { client.call("next") }
        runCurrent()
        val request = transport.requests.last()
        assertTrue(previous["id"] != request["id"])
        assertEquals(1, pendingCount(client))
        transport.reply(previous, jsonOf("value" to "stale"))
        runCurrent()
        assertFalse(next.isCompleted)
        assertEquals(1, pendingCount(client))
        val expected = jsonOf("value" to "fresh")
        transport.reply(request, expected)
        runCurrent()
        assertEquals(expected, next.await())
        assertEquals(0, pendingCount(client))
    }

    @Test fun ownDefaultDeadlineIsRequestErrorAndLateReplyCannotCompleteNextRequest() = runTest {
        withClient { client, transport ->
            val request = async {
                assertFailsWith<PocketError> { client.call("slow") }
            }
            runCurrent()
            val sent = transport.requests.last()
            assertEquals(1, pendingCount(client))
            advanceTimeBy(59_999)
            runCurrent()
            assertFalse(request.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            val error = request.await()
            assertEquals("timeout", error.code)
            assertEquals("timeout: RPC slow timed out after 60000ms", error.message)
            assertEquals(0, pendingCount(client))
            assertNextRequestIgnoresLateReply(client, transport, sent)
        }
    }

    @Test fun callerCancellationDuringSendAndReplyWaitIsPreservedAndCleanedUp() = runTest {
        for (duringSend in listOf(true, false)) {
            withClient { client, transport ->
                if (duringSend) transport.onSend = { awaitCancellation() }
                var observed: Throwable? = null
                val request = launch {
                    observed = runCatching { client.call("cancelled") }.exceptionOrNull()
                }
                runCurrent()
                val sent = transport.requests.last()
                assertEquals(1, pendingCount(client))
                request.cancel(CancellationException("caller stopped"))
                runCurrent()
                request.join()
                assertIs<CancellationException>(observed)
                assertEquals("caller stopped", observed?.message)
                assertEquals(0, pendingCount(client))
                assertNextRequestIgnoresLateReply(client, transport, sent)
            }
        }
    }

    @Test fun outerTimeoutDuringSendAndReplyWaitRemainsCancellationAndIsCleanedUp() = runTest {
        for (duringSend in listOf(true, false)) {
            withClient { client, transport ->
                if (duringSend) transport.onSend = { awaitCancellation() }
                val request = async {
                    assertFailsWith<TimeoutCancellationException> {
                        withTimeout(50) { client.call("outer-timeout", timeoutMs = 500) }
                    }
                }
                runCurrent()
                val sent = transport.requests.last()
                assertEquals(1, pendingCount(client))
                advanceTimeBy(50)
                runCurrent()
                request.await()
                assertEquals(0, pendingCount(client))
                assertNextRequestIgnoresLateReply(client, transport, sent)
            }
        }
    }

    @Test fun sendFailureIsTransportErrorAndDoesNotRetainPendingRequest() = runTest {
        withClient { client, transport ->
            transport.onSend = { throw IllegalStateException("send broke") }
            val error = assertFailsWith<PocketError> { client.call("send-failure") }
            assertEquals("transport", error.code)
            assertEquals("transport: send broke", error.message)
            assertEquals(0, pendingCount(client))
            assertNextRequestIgnoresLateReply(client, transport, transport.requests.last())
        }
    }

    @Test fun serverErrorIsPreservedAndDoesNotRetainPendingRequest() = runTest {
        withClient { client, transport ->
            val request = async { assertFailsWith<PocketError> { client.call("rejected") } }
            runCurrent()
            val sent = transport.requests.last()
            assertEquals(1, pendingCount(client))
            transport.reject(sent, "denied", "not allowed")
            runCurrent()
            val error = request.await()
            assertEquals("denied", error.code)
            assertEquals("denied: not allowed", error.message)
            assertEquals(0, pendingCount(client))
            assertNextRequestIgnoresLateReply(client, transport, sent)
        }
    }
}
