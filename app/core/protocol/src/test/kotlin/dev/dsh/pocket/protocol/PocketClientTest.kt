package dev.dsh.pocket.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
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
    private class FakeTransport(
        private val closeGate: CompletableDeferred<Unit>? = null,
    ) : FrameTransport {
        override val route = Route.WsLocal
        override val closeReason: String? = null
        private val frames = Channel<String>(Channel.UNLIMITED)
        override val incoming = flow {
            try {
                emitAll(frames.receiveAsFlow())
            } finally {
                if (closeGate != null) withContext(NonCancellable) {
                    closeGate.await()
                    throw IllegalStateException("late failure from old transport")
                }
            }
        }
        val requests = mutableListOf<JsonObject>()
        var onSend: suspend () -> Unit = {}
        var onHello: suspend () -> Unit = {}
        var closed = false

        override suspend fun send(text: String) {
            val request = PocketJson.parseToJsonElement(text).jsonObject
            requests += request
            if (request["op"].str() == "hello") {
                onHello()
                reply(request, jsonOf("proto" to 1))
            } else {
                onSend()
            }
        }

        fun reply(request: JsonObject, result: JsonElement) {
            frames.trySend(jsonOf("id" to request["id"], "ok" to true, "result" to result).toString()).getOrThrow()
        }

        fun push(frame: JsonObject) {
            frames.trySend(frame.toString()).getOrThrow()
        }

        fun reject(request: JsonObject, code: String, message: String) {
            frames.trySend(jsonOf(
                "id" to request["id"],
                "ok" to false,
                "error" to jsonOf("code" to code, "message" to message),
            ).toString()).getOrThrow()
        }

        override fun close() {
            closed = true
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

    @Test fun durableChildAddressSurvivesEveryTranscriptAndControlRequest() = runTest {
        withClient { client, transport ->
            transport.onSend = {
                val request = transport.requests.last()
                transport.reply(request, jsonOf("subId" to "child-follow"))
            }
            val child = SessionAddress("child", "parent", "continuable")
            client.follow(child, afterSeq = 17)
            client.historyPage(child, throughSeq = 17, beforeSeq = 5)
            client.sessionProjections(child)
            client.prompt(child, "continue", "queue", requestId = "request")
            client.cancel(child)
            val addressed = transport.requests.filter { it["op"].str() != "hello" }
            assertEquals(listOf("follow", "history.page", "sessions.projections", "prompt", "cancel"),
                addressed.map { it["op"].str() })
            addressed.forEach { request ->
                assertEquals("child", request["args"]["sessionId"].str())
                assertEquals("parent", request["args"]["parentSessionId"].str())
                assertEquals("continuable", request["args"]["subagentMode"].str())
            }
            client.follow(SessionAddress("parent"))
            val root = transport.requests.last()["args"] as JsonObject
            assertEquals("parent", root["sessionId"].str())
            assertFalse(root.containsKey("parentSessionId"))
            assertFalse(root.containsKey("subagentMode"))
        }
    }

    @Test fun oneShotAndUnknownChildrenRejectControlBeforeSendingAnyRpc() = runTest {
        withClient { client, transport ->
            val initial = transport.requests.size
            listOf("one-shot", "unknown").forEach { mode ->
                val address = SessionAddress("child", "parent", mode)
                assertEquals("subagent/read-only", assertFailsWith<PocketError> {
                    client.prompt(address, "unsupported", "queue")
                }.code)
                assertEquals("subagent/read-only", assertFailsWith<PocketError> { client.cancel(address) }.code)
            }
            assertEquals("subagent/attachment-invalid", assertFailsWith<PocketError> {
                client.prompt(SessionAddress("child", "parent", "continuable"), "file", "queue",
                    files = listOf(PromptFile("receipt")))
            }.code)
            assertEquals(initial, transport.requests.size)
        }
    }

    @Test fun partialOrSelfParentAddressesAreRejectedInsteadOfBecomingRoots() {
        assertFailsWith<IllegalArgumentException> { SessionAddress("child", "parent") }
        assertFailsWith<IllegalArgumentException> { SessionAddress("child", subagentMode = "unknown") }
        assertFailsWith<IllegalArgumentException> { SessionAddress("child", "child", "continuable") }
        assertFailsWith<IllegalArgumentException> { SessionAddress("child", "parent", "external") }
    }

    @Test fun replacementConnectionUsesTheSavedChildAddressNotItsBareId() = runTest {
        val client = PocketClient(backgroundScope)
        val first = FakeTransport()
        val replacement = FakeTransport()
        val address = SessionAddress("child", "parent", "continuable")
        try {
            client.connect(TransportFactory { first }, Auth.Token("first"))
            first.onSend = { first.reply(first.requests.last(), jsonOf("subId" to "first")) }
            client.follow(address)
            client.connect(TransportFactory { replacement }, Auth.Token("replacement"))
            replacement.onSend = { replacement.reply(replacement.requests.last(), jsonOf("subId" to "replacement")) }
            client.follow(address, afterSeq = 42)
            val request = replacement.requests.last()
            assertEquals("parent", request["args"]["parentSessionId"].str())
            assertEquals("child", request["args"]["sessionId"].str())
            assertEquals("continuable", request["args"]["subagentMode"].str())
            assertEquals(42L, request["args"]["afterSeq"].long())
            assertTrue(first.closed)
        } finally { client.close() }
    }
    @Test fun officeCancellationBeforeRenderReplyReleasesTheRequestedHandle() = runTest {
        withClient { client, transport ->
            transport.onSend = {
                val request = transport.requests.last()
                if (request["op"].str() == "office.release") transport.reply(request, jsonOf("released" to true))
            }
            val download = launch { client.withOfficePreview("s1", "report.docx") { error("must not receive a lease") } }
            runCurrent()
            val opening = transport.requests.last()
            assertEquals("office.render", opening["op"].str())
            advanceTimeBy(120_001)
            assertTrue(download.isActive)
            download.cancel()
            download.join()
            val release = transport.requests.last()
            assertEquals("office.release", release["op"].str())
            assertEquals(opening["args"]["subId"].str(), release["args"]["handle"].str())
            assertEquals("s1", release["args"]["sessionId"].str())
            assertEquals(0, pendingCount(client))
        }
    }

    @Test fun officePagesCarryRevisionAndReleaseOnDownloadFailure() = runTest {
        withClient { client, transport ->
            transport.onSend = {
                val request = transport.requests.last()
                when (request["op"].str()) {
                    "office.render" -> transport.reply(request, jsonOf("handle" to request["args"]["subId"],
                        "version" to "v1", "generation" to "g1"))
                    "office.read" -> transport.reply(request, jsonOf("data" to "JVBERg=="))
                    "office.release" -> transport.reply(request, jsonOf("released" to true))
                }
            }
            assertFailsWith<IllegalStateException> {
                client.withOfficePreview("s1", "report.docx") { reader ->
                    reader.read(128L, 256)
                    error("disk full")
                }
            }
            val page = transport.requests.single { it["op"].str() == "office.read" }
            assertEquals(128L, page["args"]["offset"].long())
            assertEquals(256, page["args"]["length"].int())
            assertEquals("v1", page["args"]["version"].str())
            assertEquals("g1", page["args"]["generation"].str())
            assertEquals("office.release", transport.requests.last()["op"].str())
        }
    }

    @Test fun officeReaderCannotReadOrReleaseAgainstAReplacementHost() = runTest {
        val client = PocketClient(backgroundScope)
        val first = FakeTransport()
        val second = FakeTransport()
        try {
            client.connect(TransportFactory { first }, Auth.Token("first"))
            first.onSend = {
                val request = first.requests.last()
                if (request["op"].str() == "office.render") {
                    first.reply(request, jsonOf("handle" to request["args"]["subId"],
                        "version" to "v1", "generation" to "g1"))
                }
            }
            client.withOfficePreview("s1", "report.docx") { reader ->
                client.connect(TransportFactory { second }, Auth.Token("second"))
                assertFailsWith<PocketError> { reader.read(0, 128) }
            }
            assertFalse(second.requests.any { it["op"].str()?.startsWith("office.") == true })
        } finally { client.close() }
    }


    @Test fun fileWatchReceivesReadyBeforeOpeningReplyAndIgnoresOtherSubscriptions() = runTest {
        withClient { client, transport ->
            transport.onSend = {
                val request = transport.requests.last()
                if (request["op"].str() == "unfollow") transport.reply(request, jsonOf("stopped" to true))
            }
            val frames = async { client.followFiles("s1", "src").take(1).toList() }
            runCurrent()
            val opening = transport.requests.last()
            assertEquals("files.follow", opening["op"].str())
            val subscription = opening["args"]["subId"].str()
            transport.push(jsonOf("ev" to "files", "subId" to "f-old", "frame" to jsonOf("kind" to "change")))
            transport.push(jsonOf("ev" to "files", "subId" to subscription, "frame" to jsonOf("kind" to "ready")))
            runCurrent()
            transport.reply(opening, jsonOf("subId" to subscription))
            assertEquals("ready", frames.await().single()["kind"].str())
            assertEquals("unfollow", transport.requests.last()["op"].str())
            assertEquals(subscription, transport.requests.last()["args"]["subId"].str())
        }
    }

    @Test fun cancellingFileWatchBeforeOpeningReplyStillUnfollows() = runTest {
        withClient { client, transport ->
            transport.onSend = {
                val request = transport.requests.last()
                if (request["op"].str() == "unfollow") transport.reply(request, jsonOf("stopped" to true))
            }
            val watch = launch { client.followFiles("s1", "old-directory").collect {} }
            runCurrent()
            val opening = transport.requests.last()
            watch.cancel()
            watch.join()
            assertEquals("unfollow", transport.requests.last()["op"].str())
            assertEquals(opening["args"]["subId"].str(), transport.requests.last()["args"]["subId"].str())
            transport.reply(opening, jsonOf("subId" to opening["args"]["subId"]))
            runCurrent()
            assertEquals(0, pendingCount(client))
        }
    }

    @Test fun fileWatchDisconnectReleasesCollectorAndNewConnectionCanSubscribe() = runTest {
        val client = PocketClient(backgroundScope)
        val first = FakeTransport()
        val second = FakeTransport()
        try {
            client.connect(TransportFactory { first }, Auth.Token("test-token"))
            val watch = async {
                runCatching { client.followFiles("s1", ".").collect {} }.exceptionOrNull()
            }
            runCurrent()
            first.reply(first.requests.last(), jsonOf("subId" to first.requests.last()["args"]["subId"]))
            runCurrent()
            client.close()
            assertIs<PocketError>(watch.await())
            client.connect(TransportFactory { second }, Auth.Token("test-token"))
            second.onSend = {
                val request = second.requests.last()
                if (request["op"].str() == "files.follow") {
                    second.push(jsonOf("ev" to "files", "subId" to request["args"]["subId"],
                        "frame" to jsonOf("kind" to "ready")))
                }
                second.reply(request, jsonOf("subId" to request["args"]["subId"]))
            }
            assertEquals("ready", client.followFiles("s1", ".").take(1).toList().single()["kind"].str())
        } finally {
            client.close()
        }
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

    @Test fun workspaceChangeCallsKeepExplicitSessionEventAndFileIndex() = runTest {
        withClient { client, transport ->
            val summary = async { client.workspaceChangesSummary("specified-session", 42L) }
            runCurrent()
            val summaryRequest = transport.requests.last()
            assertEquals("workspaceChanges.summary", summaryRequest["op"].str())
            assertEquals("specified-session", summaryRequest["args"]["sessionId"].str())
            assertEquals(42L, summaryRequest["args"]["seq"].long())
            val summaryReply = jsonOf("summary" to jsonOf("turn" to 2, "total" to 9))
            transport.reply(summaryRequest, summaryReply)
            assertEquals(summaryReply, summary.await())

            val diff = async { client.workspaceChangesDiff("another-session", 91L, 2) }
            runCurrent()
            val diffRequest = transport.requests.last()
            assertEquals("workspaceChanges.diff", diffRequest["op"].str())
            assertEquals("another-session", diffRequest["args"]["sessionId"].str())
            assertEquals(91L, diffRequest["args"]["seq"].long())
            assertEquals(2, diffRequest["args"]["index"].int())
            val diffReply = jsonOf("diff" to jsonOf("kind" to "binary", "path" to "a.bin", "display" to "a.bin"))
            transport.reply(diffRequest, diffReply)
            assertEquals(diffReply, diff.await())
        }
    }

    @Test fun workspaceDiffCancellationPropagatesAndReleasesPendingRequest() = runTest {
        withClient { client, transport ->
            val diff = async { client.workspaceChangesDiff("specified-session", 42L, 0) }
            runCurrent()
            val request = transport.requests.last()
            diff.cancel()
            assertFailsWith<CancellationException> { diff.await() }
            runCurrent()
            assertEquals(0, pendingCount(client))
            assertNextRequestIgnoresLateReply(client, transport, request)
        }
    }

    /** Adversarial but deterministic scheduling: newly launched per-frame jobs run backwards. */
    private class LifoDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() {
            while (tasks.isNotEmpty()) tasks.removeLast().run()
        }
    }

    @Test fun pushesStayInWireOrderIncludingTerminalUnderAdversarialScheduling() = runTest {
        val dispatcher = LifoDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val client = PocketClient(scope)
        val transport = FakeTransport()
        val received = mutableListOf<JsonObject>()
        try {
            val connecting = async { client.connect(TransportFactory { transport }, Auth.Token("test-token")) }
            runCurrent()
            dispatcher.drain()
            runCurrent()
            connecting.await()
            scope.launch { client.events.collect { received += it } }
            dispatcher.drain()
            for (start in 0 until 10_000 step 64) {
                for (seq in start until minOf(start + 64, 10_000)) {
                    transport.push(jsonOf("ev" to "follow", "seq" to seq))
                }
                dispatcher.drain()
            }
            transport.push(jsonOf("ev" to "turn/end", "seq" to 10_000))
            dispatcher.drain()
            assertEquals((0..10_000).toList(), received.map { it["seq"].int() })
            assertEquals("turn/end", received.last()["ev"].str())
        } finally {
            client.close()
            scope.cancel()
            dispatcher.drain()
        }
    }

    @Test fun handshakePushWaitsForFirstSubscriberAndIsDeliveredOnce() = runTest {
        val transport = FakeTransport()
        val client = PocketClient(backgroundScope)
        transport.onHello = {
            transport.push(jsonOf("ev" to "first", "seq" to 1))
            transport.push(jsonOf("ev" to "second", "seq" to 2))
        }
        try {
            client.connect(TransportFactory { transport }, Auth.Token("test-token"))
            runCurrent()
            val received = async { client.events.take(2).toList() }
            runCurrent()
            assertEquals(listOf(1, 2), received.await().map { it["seq"].int() })
            assertEquals(PocketClient.State.Open, client.state.value)
        } finally {
            client.close()
        }
    }

    @Test fun stalledConsumerDoesNotBlockRpcButOverflowFailsPendingAndCloses() = runTest {
        withClient { client, transport ->
            val gate = CompletableDeferred<Unit>()
            val received = mutableListOf<JsonObject>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                client.events.collect {
                    received += it
                    gate.await()
                }
            }
            transport.push(jsonOf("ev" to "follow", "seq" to 0))
            runCurrent()
            transport.push(jsonOf("ev" to "follow", "seq" to 1))
            runCurrent()
            val rpc = async { client.call("quick") }
            runCurrent()
            transport.reply(transport.requests.last(), jsonOf("done" to true))
            runCurrent()
            assertEquals(true, rpc.await()["done"].bool())

            val pending = async { assertFailsWith<PocketError> { client.call("pending") } }
            runCurrent()
            repeat(PocketClient.MAX_PENDING_PUSHES + 1) {
                transport.push(jsonOf("ev" to "follow", "seq" to it + 2))
            }
            runCurrent()
            assertEquals(PocketClient.State.Closed, client.state.value)
            assertTrue(transport.closed)
            assertTrue(client.closeReason.orEmpty().contains("overflow"))
            assertEquals("transport", pending.await().code)
            assertEquals(0, pendingCount(client))
            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf(0), received.map { it["seq"].int() })
        }
    }

    @Test fun pushByteBudgetFailsWithoutWaitingForFrameCountLimit() = runTest {
        withClient { client, transport ->
            val payload = "x".repeat(1024 * 1024)
            repeat(9) { transport.push(jsonOf("ev" to "follow", "data" to payload)) }
            runCurrent()
            assertEquals(PocketClient.State.Closed, client.state.value)
            assertTrue(client.closeReason.orEmpty().contains("overflow"))
            assertTrue(transport.closed)
        }
    }

    @Test fun oldPumpLateFailureCannotCloseNewConnectionOrCompleteItsRpc() = runTest {
        val closeGate = CompletableDeferred<Unit>()
        val old = FakeTransport(closeGate)
        val fresh = FakeTransport()
        val client = PocketClient(backgroundScope)
        try {
            client.connect(TransportFactory { old }, Auth.Token("old"))
            old.push(jsonOf("ev" to "stale"))
            runCurrent()
            val oldRpc = async { assertFailsWith<PocketError> { client.call("old-request") } }
            runCurrent()
            client.connect(TransportFactory { fresh }, Auth.Token("new"))
            assertEquals("transport", oldRpc.await().code)
            closeGate.complete(Unit)
            runCurrent()
            assertEquals(PocketClient.State.Open, client.state.value)
            assertFalse(fresh.closed)
            val received = async { client.events.take(1).toList() }
            val rpc = async { client.call("fresh-request") }
            runCurrent()
            fresh.push(jsonOf("ev" to "fresh"))
            fresh.reply(fresh.requests.last(), jsonOf("fresh" to true))
            runCurrent()
            assertEquals(true, rpc.await()["fresh"].bool())
            assertEquals("fresh", received.await().single()["ev"].str())
        } finally {
            closeGate.complete(Unit)
            client.close()
        }
    }

    @Test fun handshakeTimeoutClosesTransportAndClearsPending() = runTest {
        val transport = FakeTransport()
        val client = PocketClient(backgroundScope)
        transport.onHello = { awaitCancellation() }
        assertFailsWith<TimeoutCancellationException> {
            client.connect(TransportFactory { transport }, Auth.Token("test-token"), timeoutMs = 50)
        }
        assertTrue(transport.closed)
        assertEquals(PocketClient.State.Closed, client.state.value)
        assertEquals(0, pendingCount(client))
    }

    @Test fun jobOutputBeforeSubscribeReplyIsRetainedAndCollectorCancellationUnfollows() = runTest {
        withClient { client, transport ->
            transport.onSend = {
                val request = transport.requests.last()
                when (request["op"].str()) {
                    "jobs.follow" -> {
                        transport.push(jsonOf("ev" to "job", "subId" to "j1", "frame" to jsonOf("type" to "opened", "from" to 4)))
                        transport.reply(request, jsonOf("subId" to "j1"))
                    }
                    "unfollow" -> transport.reply(request, jsonOf("stopped" to true))
                }
            }
            val frames = async { client.followJob("s1", "bash-1").take(1).toList() }
            runCurrent()
            assertEquals("opened", frames.await().single()["type"].str())
            assertEquals("j1", transport.requests.last()["args"]["subId"].str())
            assertEquals("unfollow", transport.requests.last()["op"].str())
        }
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
