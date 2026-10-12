package dev.dsh.pocket.protocol

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Credentials for one connection attempt: either a device token (hello) or a pairing code (pair). */
sealed interface Auth {
    data class Token(val token: String) : Auth
    data class Pair(val code: String, val deviceName: String) : Auth
}

data class HelloResult(val raw: JsonObject, val route: Route? = null) {
    val ops: Set<String> get() = raw["ops"].arr().orEmpty().mapNotNull { it.str() }.toSet()
    val deviceId: String? get() = raw["deviceId"].str() ?: raw["device"]["id"].str()
    /** Present only after a successful pairing. */
    val issuedToken: String? get() = raw["token"].str()
}

/**
 * Client for the dsh-plugin-pocket protocol v1 over any [FrameTransport]
 * (WebSocket text frames or iroh newline-JSON). Auth tokens are identical
 * across transports.
 */
class PocketClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    enum class State { Idle, Connecting, Open, Closed }

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private var transport: FrameTransport? = null
    private var pump: Job? = null
    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 4096, onBufferOverflow = BufferOverflow.SUSPEND)
    /** Server pushes (`ev` frames). */
    val events: SharedFlow<JsonObject> = _events
    @Volatile var closeReason: String? = null; private set
    /** Live route from the active transport (iroh may upgrade relay→direct). */
    val route: Route? get() = transport?.route

    /** Connect over loopback WebSocket (dev/tests only). */
    suspend fun connect(url: String, auth: Auth, timeoutMs: Long = 10_000): HelloResult =
        connect(listOf(WsTransport.factory(url)), auth, timeoutMs)

    /** Connect using an ordered list of transports (iroh first; optional loopback WS). */
    suspend fun connect(factories: List<TransportFactory>, auth: Auth, timeoutMs: Long = 10_000): HelloResult =
        withTimeout(timeoutMs) {
            close()
            _state.value = State.Connecting
            closeReason = null
            val t = dialInOrder(factories)
            attach(t)
            val result = when (auth) {
                is Auth.Token -> call("hello", jsonOf("proto" to 1, "token" to auth.token, "app" to "dsh-android"))
                is Auth.Pair -> call("pair", jsonOf("proto" to 1, "code" to auth.code, "name" to auth.deviceName))
            }
            _state.value = State.Open
            HelloResult(result.obj() ?: JsonObject(emptyMap()), t.route)
        }

    suspend fun connect(factory: TransportFactory, auth: Auth, timeoutMs: Long = 10_000): HelloResult =
        connect(listOf(factory), auth, timeoutMs)

    private fun attach(t: FrameTransport) {
        transport = t
        pump = scope.launch {
            try {
                t.incoming.collect { onFrame(it) }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            } finally {
                if (_state.value == State.Open || _state.value == State.Connecting) {
                    fail(t.closeReason ?: "closed")
                }
            }
        }
    }

    private fun fail(reason: String) {
        closeReason = reason
        _state.value = State.Closed
        val err = PocketError("transport", reason)
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
    }

    private fun onFrame(text: String) {
        val m = runCatching { PocketJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val id = m["id"].long()
        if (id != null && pending.containsKey(id)) { pending.remove(id)?.complete(m); return }
        if (m["ev"].str() == "ping") {
            scope.launch { runCatching { transport?.send("""{"op":"pong"}""") } }
            return
        }
        if (m["ev"] != null) scope.launch { _events.emit(m) }
    }

    /** Send one op; returns `result` or throws [PocketError]. */
    suspend fun call(op: String, args: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = 60_000): JsonElement {
        val t = transport ?: throw PocketError("transport", "not connected")
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        val frame = buildJsonObject { put("id", id); put("op", op); put("args", args) }
        try {
            t.send(frame.toString())
        } catch (e: Exception) {
            pending.remove(id)
            throw PocketError("transport", e.message ?: "send failed")
        }
        val reply = try { withTimeout(timeoutMs) { d.await() } } finally { pending.remove(id) }
        if (reply["ok"].bool() == true) return reply["result"] ?: JsonNull
        throw PocketError(reply["error"]["code"].str() ?: "error", reply["error"]["message"].str() ?: "")
    }

    // ---- typed helpers
    suspend fun listSessions(): List<SessionSummary> = call("sessions.list")["items"].arr().orEmpty().mapNotNull(SessionSummary::parse)
    suspend fun createSession(cwd: String? = null, agentPreset: String? = null): String =
        call("sessions.create", jsonOf("cwd" to cwd, "agentPreset" to agentPreset))["sessionId"].str() ?: throw PocketError("bad-reply", "no sessionId")
    suspend fun prompt(sessionId: String, text: String, mode: String, images: List<PromptImage> = emptyList()) =
        call("prompt", buildJsonObject {
            put("sessionId", sessionId); put("text", text); put("mode", mode)
            if (images.isNotEmpty()) putJsonArray("images") {
                images.forEach { img ->
                    add(buildJsonObject {
                        put("mediaType", img.mediaType); put("data", img.data)
                        img.name?.let { put("name", it) }
                    })
                }
            }
        })
    suspend fun cancel(sessionId: String) = call("cancel", jsonOf("sessionId" to sessionId))
    suspend fun executeCommand(sessionId: String, line: String): JsonElement =
        call("commands.execute", jsonOf("sessionId" to sessionId, "line" to line))
    suspend fun exportSession(sessionId: String, includeDescendants: Boolean = true): JsonElement =
        call("session.export", jsonOf("sessionId" to sessionId, "includeDescendants" to includeDescendants), timeoutMs = 180_000)
    suspend fun listFiles(sessionId: String, path: String = "."): JsonElement =
        call("files.list", jsonOf("sessionId" to sessionId, "path" to path))
    suspend fun readFile(sessionId: String, path: String): JsonElement =
        call("files.read", jsonOf("sessionId" to sessionId, "path" to path))
    suspend fun listJobs(sessionId: String): JsonElement =
        call("jobs.list", jsonOf("sessionId" to sessionId))
    suspend fun killJob(sessionId: String, jobId: String): JsonElement =
        call("jobs.kill", jsonOf("sessionId" to sessionId, "jobId" to jobId))
    suspend fun listTerminals(sessionId: String): JsonElement =
        call("terminal.list", jsonOf("sessionId" to sessionId))
    suspend fun createTerminal(sessionId: String, cols: Int = 80, rows: Int = 24, id: String? = null): JsonElement =
        call("terminal.create", jsonOf("sessionId" to sessionId, "cols" to cols, "rows" to rows, "id" to id))
    suspend fun followTerminal(sessionId: String, id: String, attachmentId: String? = null): JsonElement =
        call("terminal.follow", jsonOf("sessionId" to sessionId, "id" to id, "attachmentId" to attachmentId))
    suspend fun writeTerminal(sessionId: String, id: String, attachmentId: String, data: String): JsonElement =
        call("terminal.write", jsonOf("sessionId" to sessionId, "id" to id, "attachmentId" to attachmentId, "data" to data))
    suspend fun resizeTerminal(sessionId: String, id: String, attachmentId: String, cols: Int, rows: Int): JsonElement =
        call("terminal.resize", jsonOf("sessionId" to sessionId, "id" to id, "attachmentId" to attachmentId, "cols" to cols, "rows" to rows))
    suspend fun closeTerminal(sessionId: String, id: String): JsonElement =
        call("terminal.close", jsonOf("sessionId" to sessionId, "id" to id))
    suspend fun modelCatalog(): ModelCatalog = ModelCatalog.parse(call("models.catalog"))
    suspend fun selectModel(sessionId: String, provider: String, model: String, effort: String?) =
        call("models.select", jsonOf("sessionId" to sessionId, "provider" to provider, "model" to model, "reasoningEffort" to effort))
    suspend fun follow(sessionId: String, afterSeq: Long? = null, maxMessages: Int? = 50): String =
        call("follow", jsonOf("sessionId" to sessionId, "afterSeq" to afterSeq, "maxMessages" to maxMessages))["subId"].str() ?: ""
    /** Load older turns above the current snapshot window (desktop history.page). */
    suspend fun historyPage(
        sessionId: String,
        throughSeq: Long,
        beforeSeq: Long? = null,
        maxMessages: Int = 100,
    ): JsonElement = call(
        "history.page",
        jsonOf(
            "sessionId" to sessionId,
            "throughSeq" to throughSeq,
            "beforeSeq" to beforeSeq,
            "maxMessages" to maxMessages,
        ),
    )
    suspend fun unfollow(subId: String) = call("unfollow", jsonOf("subId" to subId))
    suspend fun answerApproval(approvalId: String, outcome: String) =
        call("approval.answer", jsonOf("approvalId" to approvalId, "outcome" to outcome))
    suspend fun permissionCatalog(): List<String> = call("permissions.catalog")["options"].arr().orEmpty().mapNotNull { it["value"].str() }
    suspend fun permission(sessionId: String): String? = call("permissions.get", jsonOf("sessionId" to sessionId))["current"].str()
    suspend fun setPermission(sessionId: String, name: String): String? = call("permissions.set", jsonOf("sessionId" to sessionId, "name" to name))["current"].str()
    suspend fun presets(): List<String> = call("presets.list")["presets"].arr().orEmpty().mapNotNull { it["id"].str() }

    fun close() {
        pump?.cancel(); pump = null
        transport?.close(); transport = null
        if (_state.value != State.Idle) _state.value = State.Closed
    }

    companion object {
        /** Loopback WS must bypass system HTTP proxies that break local upgrades. */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
